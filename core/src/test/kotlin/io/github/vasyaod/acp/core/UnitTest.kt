package io.github.vasyaod.acp.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class UnitTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `json round trip`() {
        val src = """{"a":1,"b":[true,false,null,"x\"y\né"],"c":{"d":-2.5,"e":1e3},"f":""}"""
        val v = Json.parse(src)
        assertEquals(v, Json.parse(Json.stringify(v)))
        assertEquals(1L, v.asObj()["a"])
        assertEquals("x\"y\né", v.asObj()["b"].asList()[3])
        assertEquals(1000.0, v.asObj().obj("c")["e"])
    }

    @Test
    fun `line and limit slicing`() {
        val t = "a\nb\nc\nd"
        assertEquals(t, TextSlice.lines(t, null, null))
        assertEquals("b\nc\n", TextSlice.lines(t, 2, 2))
        assertEquals("c\nd", TextSlice.lines(t, 3, null))
        assertEquals("d", TextSlice.lines(t, 4, 10))
        assertEquals("", TextSlice.lines(t, 9, 1))
        assertEquals("a\n", TextSlice.lines(t, null, 1))
    }

    @Test
    fun `permission choice maps to the agent's option ids`() {
        val opts = listOf(
            PermissionOption("allow", "Allow", "allow_once"),
            PermissionOption("allow_always", "Always allow", "allow_always"),
            PermissionOption("reject", "Reject", "reject_once"),
        )
        assertEquals("allow", PermissionPolicy.optionFor(PermissionChoice.ALLOW_ONCE, opts))
        assertEquals("allow_always", PermissionPolicy.optionFor(PermissionChoice.ALLOW_ALWAYS, opts))
        assertEquals("reject", PermissionPolicy.optionFor(PermissionChoice.REJECT, opts))
        val custom = listOf(PermissionOption("x1", "Yes", "allow_once"), PermissionOption("x2", "No", "reject_once"))
        assertEquals("x1", PermissionPolicy.optionFor(PermissionChoice.ALLOW_ALWAYS, custom))
        assertNull(PermissionPolicy.optionFor(PermissionChoice.REJECT, emptyList()))
    }

    @Test
    fun `auto-allow skips the prompt`() {
        var asked = 0
        val p = PermissionPolicy({ asked++; PermissionChoice.REJECT }, autoAllow = { true })
        val req = PermissionRequest("s", "t", "rm -rf build", "execute", null, listOf(PermissionOption("allow", "Allow", "allow_once")))
        assertEquals("allow", p.decide(req))
        assertEquals(0, asked)
    }

    @Test
    fun `terminal output respects outputByteLimit and reports truncation`() {
        val tm = TerminalManager(LocalProcessStarter()) {}
        val id = tm.create("s", null, CommandSpec("/bin/sh", listOf("-c", "printf 0123456789abcdef"), dir, emptyMap()), 5)
        assertEquals(0, tm.waitForExit(id)["exitCode"])
        val out = tm.output(id)
        assertEquals("bcdef", out["output"])
        assertEquals(true, out["truncated"])
        assertEquals(mapOf("exitCode" to 0, "signal" to null), out["exitStatus"])
        tm.release(id)
        assertTrue(tm.ids().isEmpty())
    }

    @Test
    fun `truncation keeps whole characters`() {
        // A fake process so the chunking is exact: "aé€😀" = 1 + 2 + 3 + 4 bytes.
        lateinit var sink: ProcessSink
        val tm = TerminalManager({ _, s -> sink = s; object : RunningProcess { override fun kill() {} } }) {}
        val id = tm.create("s", null, CommandSpec("x", emptyList(), dir, emptyMap()), 5)
        sink.onOutput("aé€😀")
        assertEquals("😀", tm.output(id)["output"])
        sink.onOutput("b")
        assertEquals("😀b", tm.output(id)["output"]) // exactly 5 bytes
        sink.onOutput("c")
        assertEquals("bc", tm.output(id)["output"]) // never half a surrogate pair
        assertEquals(true, tm.output(id)["truncated"])
    }

    @Test
    fun `environment and cwd reach the process, exit code is reported`() {
        val tm = TerminalManager(LocalProcessStarter()) {}
        val id = tm.create("s", null, CommandSpec("/bin/sh", listOf("-c", "echo \$ACP_X; exit 3"), dir, mapOf("ACP_X" to "hello")), null)
        val exit = tm.waitForExit(id)
        assertEquals(3, exit["exitCode"])
        assertEquals("hello\n", tm.output(id)["output"])
        assertFalse(tm.output(id)["truncated"] as Boolean)
    }

    @Test
    fun `kill ends a running command`() {
        val tm = TerminalManager(LocalProcessStarter()) {}
        val id = tm.create("s", null, CommandSpec("/bin/sh", listOf("-c", "sleep 30"), dir, emptyMap()), null)
        Thread.sleep(200)
        tm.kill(id)
        val f = java.util.concurrent.CompletableFuture.supplyAsync { tm.waitForExit(id) }
        assertEquals("SIGKILL", f.get(5, TimeUnit.SECONDS)["signal"])
    }
}
