package io.github.vasyaod.acp.core

import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class AcpClientOptions(
    /** ws:// or wss:// URL of the remote ACP agent. */
    val url: String,
    /** Sent on the WebSocket upgrade: `Authorization` plus any user-configured extra headers. */
    val headers: Map<String, String> = emptyMap(),
    val clientName: String = "jetbrains-acp-plugin",
    val clientTitle: String = "JetBrains ACP plugin",
    val clientVersion: String = "0.1.0",
    val requestTimeoutMs: Long = 30_000,
)

/** Everything the agent tells the UI. Called on background threads. */
interface AcpListener {
    fun onSessionUpdate(sessionId: String, update: SessionUpdate) {}

    fun onLocalEvent(event: LocalEvent) {}

    /** The connection ended without us closing it. Every session on it is gone. */
    fun onDisconnected(error: AcpConnectionException) {}
}

/**
 * A generic ACP client over one WebSocket connection: initialize, session/new,
 * session/prompt, session/cancel, and the client side of every agent request
 * (fs, terminal, permission), all executed on this machine.
 */
class AcpClient(
    private val options: AcpClientOptions,
    private val fs: FileSystemHandler,
    processStarter: ProcessStarter,
    private val permissions: PermissionPolicy,
    private val listener: AcpListener,
    private val transportFactory: TransportFactory = WebSocketTransportFactory(),
) {
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "acp-client").apply { isDaemon = true } }
    private val terminals = TerminalManager(processStarter) { e -> safe { listener.onLocalEvent(e) } }
    private val sessions = ConcurrentHashMap<String, SessionState>()
    private val peer = JsonRpcPeer(Handler(), executor)
    @Volatile private var closing = false
    /** Set once initialize succeeded; before that a failure is thrown by connect(), not reported as a drop. */
    @Volatile private var ready = false
    @Volatile var agentInfo: AgentInfo? = null
        private set

    private class SessionState(val cwd: Path) {
        @Volatile var cancelled = false
        /** Tool calls not yet completed/failed, in arrival order: id -> kind. */
        val active = LinkedHashMap<String, String>()

        fun guessToolCall(vararg kinds: String): String? = synchronized(active) {
            active.entries.lastOrNull { it.value in kinds }?.key ?: active.keys.lastOrNull()
        }
    }

    val isConnected: Boolean get() = agentInfo != null && !peer.closed

    /** Opens the socket and runs `initialize`. Throws [AcpConnectionException] on any failure. */
    fun connect(): AgentInfo {
        val transport = transportFactory.connect(options.url, options.headers, peer)
        peer.attach(transport)
        peer.onClosed = { err ->
            terminals.killAll()
            sessions.clear()
            if (!closing && ready && err != null) safe { listener.onDisconnected(err) }
        }
        val result = await(
            peer.request(
                "initialize",
                mapOf(
                    "protocolVersion" to PROTOCOL_VERSION,
                    "clientCapabilities" to mapOf(
                        "fs" to mapOf("readTextFile" to true, "writeTextFile" to true),
                        "terminal" to true,
                    ),
                    "clientInfo" to mapOf(
                        "name" to options.clientName,
                        "title" to options.clientTitle,
                        "version" to options.clientVersion,
                    ),
                ),
            ),
            "initialize",
        ).asObj()
        val info = result.obj("agentInfo")
        val agent = AgentInfo(info.str("name"), info.str("version"), result.int("protocolVersion"), result)
        agentInfo = agent
        ready = true
        return agent
    }

    /** `session/new` with [cwd] (absolute, on this machine). Every prompt in one chat reuses the returned id. */
    fun newSession(cwd: Path): String {
        val abs = cwd.toAbsolutePath().normalize()
        val res = await(peer.request("session/new", mapOf("cwd" to abs.toString(), "mcpServers" to emptyList<Any>())), "session/new").asObj()
        val id = res.str("sessionId") ?: throw AcpRpcException(RpcError.INTERNAL_ERROR, "session/new returned no sessionId")
        sessions[id] = SessionState(abs)
        return id
    }

    /** One turn. Completes with the stop reason (`end_turn`, `cancelled`, …) or fails with
     *  [AcpRpcException] / [AcpConnectionException]. */
    fun prompt(sessionId: String, text: String): CompletableFuture<String> {
        sessions[sessionId]?.cancelled = false
        return peer.request(
            "session/prompt",
            mapOf("sessionId" to sessionId, "prompt" to listOf(mapOf("type" to "text", "text" to text))),
        ).thenApply { it.asObj().str("stopReason") ?: "end_turn" }
    }

    /** `session/cancel` (a notification). The running prompt then answers `cancelled`. */
    fun cancel(sessionId: String) {
        sessions[sessionId]?.cancelled = true
        peer.notify("session/cancel", mapOf("sessionId" to sessionId))
    }

    /** Forget a chat locally (its "always allow" answers too). The agent has no session/close. */
    fun forgetSession(sessionId: String) {
        sessions.remove(sessionId)
        permissions.forget(sessionId)
    }

    fun close() {
        closing = true
        terminals.killAll()
        peer.close()
        executor.shutdownNow()
    }

    private fun await(f: CompletableFuture<Any?>, what: String): Any? = try {
        f.get(options.requestTimeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    } catch (e: TimeoutException) {
        throw AcpConnectionException(FailureKind.DROPPED, "The agent did not answer $what in ${options.requestTimeoutMs / 1000}s.")
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
        }
    }

    private fun session(params: Map<String, Any?>): Pair<String, SessionState> {
        val id = params.str("sessionId") ?: throw RpcError(RpcError.INVALID_PARAMS, "sessionId is required")
        val s = sessions[id] ?: throw RpcError(RpcError.INVALID_PARAMS, "unknown sessionId: $id")
        return id to s
    }

    private fun resolve(state: SessionState, raw: String?): Path {
        if (raw.isNullOrBlank()) throw RpcError(RpcError.INVALID_PARAMS, "path is required")
        val p = Paths.get(raw)
        return (if (p.isAbsolute) p else state.cwd.resolve(p)).normalize()
    }

    private inner class Handler : RpcHandler {
        override fun onNotification(method: String, params: Map<String, Any?>) {
            if (method != "session/update") return
            val sid = params.str("sessionId") ?: return
            val update = SessionUpdate.parse(params.obj("update"))
            sessions[sid]?.let { s ->
                synchronized(s.active) {
                    when (update) {
                        is SessionUpdate.ToolCall ->
                            if (update.status in DONE) s.active.remove(update.toolCallId) else s.active[update.toolCallId] = update.kind
                        is SessionUpdate.ToolCallUpdate ->
                            if (update.status in DONE) s.active.remove(update.toolCallId)
                        else -> {}
                    }
                }
            }
            listener.onSessionUpdate(sid, update)
        }

        override fun onRequest(method: String, params: Map<String, Any?>): Any? = when (method) {
            "fs/read_text_file" -> {
                val (sid, s) = session(params)
                val path = resolve(s, params.str("path"))
                val text = try {
                    fs.readTextFile(path)
                } catch (e: java.nio.file.NoSuchFileException) {
                    throw RpcError(RpcError.INTERNAL_ERROR, "file not found: $path")
                }
                safe { listener.onLocalEvent(LocalEvent.FileRead(sid, s.guessToolCall("read", "edit"), path.toString())) }
                mapOf("content" to TextSlice.lines(text, params.int("line"), params.int("limit")))
            }
            "fs/write_text_file" -> {
                val (sid, s) = session(params)
                val path = resolve(s, params.str("path"))
                val content = params.str("content") ?: ""
                fs.writeTextFile(path, content)
                safe { listener.onLocalEvent(LocalEvent.FileWritten(sid, s.guessToolCall("edit"), path.toString(), content.length)) }
                null
            }
            "terminal/create" -> {
                val (sid, s) = session(params)
                val command = params.str("command") ?: throw RpcError(RpcError.INVALID_PARAMS, "command is required")
                val env = LinkedHashMap<String, String>()
                for (item in params["env"].asList()) {
                    val e = item.asObj()
                    val name = e.str("name") ?: continue
                    env[name] = e.str("value") ?: ""
                }
                val cwd = params.str("cwd")?.takeIf { it.isNotBlank() }?.let { resolve(s, it) } ?: s.cwd
                val spec = CommandSpec(command, params["args"].asList().map { it.toString() }, cwd, env)
                val limit = (params["outputByteLimit"] as? Number)?.toLong()
                mapOf("terminalId" to terminals.create(sid, s.guessToolCall("execute"), spec, limit))
            }
            "terminal/output" -> terminals.output(params.str("terminalId"))
            "terminal/wait_for_exit" -> terminals.waitForExit(params.str("terminalId"))
            "terminal/kill" -> { terminals.kill(params.str("terminalId")); null }
            "terminal/release" -> { terminals.release(params.str("terminalId")); null }
            "session/request_permission" -> {
                val (sid, s) = session(params)
                val call = params.obj("toolCall")
                val req = PermissionRequest(
                    sessionId = sid,
                    toolCallId = call.str("toolCallId"),
                    title = call.str("title") ?: "",
                    kind = call.str("kind") ?: "other",
                    rawInput = call["rawInput"],
                    options = params["options"].asList().map {
                        val o = it.asObj()
                        PermissionOption(o.str("optionId") ?: "", o.str("name") ?: "", o.str("kind") ?: "")
                    },
                )
                val option = if (s.cancelled) null else permissions.decide(req)
                if (option == null || s.cancelled) {
                    mapOf("outcome" to mapOf("outcome" to "cancelled"))
                } else {
                    mapOf("outcome" to mapOf("outcome" to "selected", "optionId" to option))
                }
            }
            else -> throw RpcError(RpcError.METHOD_NOT_FOUND, "method not supported by this client: $method")
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        private val DONE = setOf("completed", "failed")
    }
}
