package io.github.vasyaod.acp.core

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class CommandSpec(
    val command: String,
    val args: List<String>,
    val cwd: Path,
    val env: Map<String, String>,
)

/** Where a started process reports to. Calls may come from any thread. */
interface ProcessSink {
    fun onOutput(text: String)

    /** Exactly once. [signal] is set (and [exitCode] null) when the process was killed. */
    fun onExit(exitCode: Int?, signal: String?)
}

interface RunningProcess {
    fun kill()
}

/** Starts a local process. The IDE uses GeneralCommandLine/OSProcessHandler, tests use [LocalProcessStarter]. */
fun interface ProcessStarter {
    fun start(spec: CommandSpec, sink: ProcessSink): RunningProcess
}

/** java.lang.ProcessBuilder, stdout+stderr merged, stdin closed. */
class LocalProcessStarter : ProcessStarter {
    override fun start(spec: CommandSpec, sink: ProcessSink): RunningProcess {
        val pb = ProcessBuilder(listOf(spec.command) + spec.args)
            .directory(spec.cwd.toFile())
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
        pb.environment().putAll(spec.env)
        val proc = pb.start()
        val killed = java.util.concurrent.atomic.AtomicBoolean(false)
        val pump = Thread({
            InputStreamReader(proc.inputStream, StandardCharsets.UTF_8).use { r ->
                val buf = CharArray(4096)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    if (n > 0) sink.onOutput(String(buf, 0, n))
                }
            }
            val rc = proc.waitFor()
            if (killed.get()) sink.onExit(null, "SIGKILL") else sink.onExit(rc, null)
        }, "acp-terminal-pump")
        pump.isDaemon = true
        pump.start()
        return object : RunningProcess {
            override fun kill() {
                if (!proc.isAlive) return
                killed.set(true)
                proc.descendants().forEach { it.destroyForcibly() }
                proc.destroyForcibly()
            }
        }
    }

    private fun nullDevice(): java.io.File =
        java.io.File(if (System.getProperty("os.name").lowercase().contains("win")) "NUL" else "/dev/null")
}

/**
 * The ACP terminals of one connection: output kept up to `outputByteLimit` bytes
 * (trimmed from the start, on a character boundary), exit status, kill and release.
 */
class TerminalManager(
    private val starter: ProcessStarter,
    private val events: (LocalEvent) -> Unit,
) {
    private val seq = AtomicInteger()
    private val terminals = ConcurrentHashMap<String, Terminal>()

    inner class Terminal(
        val id: String,
        val sessionId: String,
        val toolCallId: String?,
        private val limit: Long,
    ) : ProcessSink {
        private val out = StringBuilder()
        private var bytes = 0L
        @Volatile var truncated = false
            private set
        val exit = CompletableFuture<Map<String, Any?>>()
        lateinit var process: RunningProcess

        override fun onOutput(text: String) {
            synchronized(out) {
                out.append(text)
                bytes += utf8Length(text)
                if (bytes > limit) trim()
            }
            events(LocalEvent.CommandOutput(sessionId, toolCallId, id, text))
        }

        override fun onExit(exitCode: Int?, signal: String?) {
            if (exit.complete(mapOf("exitCode" to exitCode, "signal" to signal))) {
                events(LocalEvent.CommandExited(sessionId, toolCallId, id, exitCode, signal))
            }
        }

        private fun trim() {
            var excess = bytes - limit
            var i = 0
            while (excess > 0 && i < out.length) {
                val n = charUtf8(out[i])
                excess -= n
                bytes -= n
                i++
            }
            if (i < out.length && Character.isLowSurrogate(out[i])) {
                bytes -= charUtf8(out[i])
                i++
            }
            out.delete(0, i)
            truncated = true
        }

        fun text(): String = synchronized(out) { out.toString() }
    }

    fun create(sessionId: String, toolCallId: String?, spec: CommandSpec, outputByteLimit: Long?): String {
        val id = "term-${seq.incrementAndGet()}"
        val limit = outputByteLimit?.takeIf { it > 0 } ?: DEFAULT_LIMIT
        val t = Terminal(id, sessionId, toolCallId, limit)
        terminals[id] = t
        events(LocalEvent.CommandStarted(sessionId, toolCallId, id, describe(spec), spec.cwd.toString()))
        try {
            t.process = starter.start(spec, t)
        } catch (e: Exception) {
            terminals.remove(id)
            t.onExit(127, null)
            throw RpcError(RpcError.INTERNAL_ERROR, "could not start ${spec.command}: ${e.message}")
        }
        return id
    }

    private fun get(id: String?): Terminal =
        terminals[id ?: ""] ?: throw RpcError(RpcError.INVALID_PARAMS, "unknown terminalId: $id")

    fun output(id: String?): Map<String, Any?> {
        val t = get(id)
        val result = linkedMapOf<String, Any?>("output" to t.text(), "truncated" to t.truncated)
        if (t.exit.isDone) result["exitStatus"] = t.exit.get()
        return result
    }

    fun waitForExit(id: String?): Map<String, Any?> = get(id).exit.get()

    fun kill(id: String?) {
        get(id).process.kill()
    }

    fun release(id: String?) {
        val t = terminals.remove(id ?: "") ?: return
        if (!t.exit.isDone) t.process.kill()
    }

    fun killAll() {
        for (t in terminals.values.toList()) {
            if (!t.exit.isDone) runCatching { t.process.kill() }
        }
        terminals.clear()
    }

    fun ids(): Set<String> = terminals.keys.toSet()

    companion object {
        /** Used when the agent sets no `outputByteLimit`, so a chatty command cannot eat the IDE's heap. */
        const val DEFAULT_LIMIT = 10L * 1024 * 1024

        fun describe(spec: CommandSpec): String =
            if (spec.command in setOf("/bin/sh", "sh", "bash", "/bin/bash", "zsh", "/bin/zsh") &&
                spec.args.size == 2 && spec.args[0] == "-c"
            ) spec.args[1] else (listOf(spec.command) + spec.args).joinToString(" ")

        private fun charUtf8(c: Char): Int = when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            Character.isSurrogate(c) -> 2
            else -> 3
        }

        fun utf8Length(s: String): Long {
            var n = 0L
            for (c in s) n += charUtf8(c)
            return n
        }
    }
}
