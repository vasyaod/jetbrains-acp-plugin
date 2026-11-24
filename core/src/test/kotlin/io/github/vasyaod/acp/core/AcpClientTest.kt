package io.github.vasyaod.acp.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AcpClientTest {
    @TempDir
    lateinit var project: Path

    private val token = "test-token-123"
    private val agents = mutableListOf<MockAcpAgent>()
    private val clients = mutableListOf<AcpClient>()

    private val updates = CopyOnWriteArrayList<SessionUpdate>()
    private val events = CopyOnWriteArrayList<LocalEvent>()
    private val disconnects = CopyOnWriteArrayList<AcpConnectionException>()
    private val prompts = CopyOnWriteArrayList<PermissionRequest>()
    private val commandStarted = CountDownLatch(1)

    @AfterEach
    fun tearDown() {
        clients.forEach { it.close() }
        agents.forEach { it.shutdown() }
    }

    private fun agent(mode: String = "ok") = MockAcpAgent(token, mode).startAndWait().also { agents += it }

    private fun client(url: String, token: String? = this.token, choice: PermissionChoice = PermissionChoice.ALLOW_ONCE): AcpClient {
        val policy = PermissionPolicy({ req -> prompts += req; choice })
        val listener = object : AcpListener {
            override fun onSessionUpdate(sessionId: String, update: SessionUpdate) { updates += update }
            override fun onLocalEvent(event: LocalEvent) {
                events += event
                if (event is LocalEvent.CommandStarted) commandStarted.countDown()
            }
            override fun onDisconnected(error: AcpConnectionException) { disconnects += error }
        }
        return AcpClient(
            AcpClientOptions(url, Headers.forConnect(token, emptyMap()), requestTimeoutMs = 10_000),
            LocalFileSystemHandler(), LocalProcessStarter(), policy, listener,
        ).also { clients += it }
    }

    private fun hostname(): String =
        ProcessBuilder("hostname").start().inputStream.bufferedReader().readText().trim()

    private fun writeReadme() = Files.writeString(project.resolve("README.md"), "line one\nline two\nline three\n")

    @Test
    fun `scripted turn runs the command and file operations locally in the project dir`() {
        writeReadme()
        val mock = agent()
        val c = client(mock.url)
        val info = c.connect()
        assertEquals("mock-agent", info.name)
        assertEquals("Bearer $token", mock.authHeaders.single())

        val sid = c.newSession(project)
        assertEquals("end_turn", c.prompt(sid, "run hostname and pwd, read README.md").get(30, TimeUnit.SECONDS))

        // The command really ran here: same hostname as this machine, cwd = the project dir.
        val out = mock.terminalOutputs.single().trim().lines()
        assertEquals(hostname(), out[0])
        assertEquals(project.toRealPath().toString(), out[1])
        assertEquals(0, (mock.exitStatuses.single()["exitCode"] as Number).toInt())

        val started = events.filterIsInstance<LocalEvent.CommandStarted>().single()
        assertEquals("hostname && pwd", started.commandLine)
        assertEquals(project.toAbsolutePath().normalize().toString(), started.cwd)
        assertEquals("call_1", started.toolCallId)
        assertTrue(events.any { it is LocalEvent.CommandOutput && it.toolCallId == "call_1" })

        // line/limit respected; write landed on this disk.
        assertEquals("line two\n", mock.fileReads.single())
        assertEquals("written by the mock agent\n", Files.readString(project.resolve("notes/agent.txt")))
        assertTrue(events.any { it is LocalEvent.FileWritten && it.path.endsWith("notes/agent.txt") })

        // Permission was asked for the command and the write; both allowed once.
        assertEquals(listOf("execute", "edit"), prompts.map { it.kind })
        assertEquals(listOf("allow", "allow"), mock.permissionAnswers)

        // The UI got streamed text and tool-call cards.
        val text = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>().joinToString("") { it.text }
        assertEquals("Hello from the mock agent. Running a command on your machine. Done.", text)
        assertEquals(listOf("call_1", "call_2", "call_3"), updates.filterIsInstance<SessionUpdate.ToolCall>().map { it.toolCallId })
        val done = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().filter { it.status == "completed" }
        assertTrue(done.first { it.toolCallId == "call_1" }.content!!.contains(hostname()))
    }

    @Test
    fun `second prompt reuses the session and always-allow is remembered per kind`() {
        writeReadme()
        val mock = agent()
        val c = client(mock.url, choice = PermissionChoice.ALLOW_ALWAYS)
        c.connect()
        val sid = c.newSession(project)
        assertEquals("end_turn", c.prompt(sid, "turn one").get(30, TimeUnit.SECONDS))
        assertEquals("end_turn", c.prompt(sid, "turn two").get(30, TimeUnit.SECONDS))

        assertEquals(listOf(sid, sid), mock.promptSessionIds.toList())
        assertEquals(1, mock.methods.count { it == "session/new" })
        assertEquals(2, mock.terminalOutputs.size)
        // Asked once per kind (execute, edit); turn two was answered from memory.
        assertEquals(listOf("execute", "edit"), prompts.map { it.kind })
        assertEquals(List(4) { "allow_always" }, mock.permissionAnswers.toList())

        // A new chat forgets "always".
        c.forgetSession(sid)
        val sid2 = c.newSession(project)
        assertTrue(sid2 != sid)
        c.prompt(sid2, "turn three").get(30, TimeUnit.SECONDS)
        assertEquals(4, prompts.size)
    }

    @Test
    fun `rejecting the permission means nothing runs`() {
        writeReadme()
        val mock = agent()
        val c = client(mock.url, choice = PermissionChoice.REJECT)
        c.connect()
        val sid = c.newSession(project)
        assertEquals("end_turn", c.prompt(sid, "go").get(30, TimeUnit.SECONDS))
        assertEquals(listOf("reject"), mock.permissionAnswers.toList())
        assertFalse(mock.methods.contains("terminal/create"))
        assertTrue(events.none { it is LocalEvent.CommandStarted })
    }

    @Test
    fun `stop sends session cancel, kills the local command and the turn ends cancelled`() {
        val mock = agent()
        val c = client(mock.url)
        c.connect()
        val sid = c.newSession(project)
        val turn = c.prompt(sid, "slow")
        assertTrue(commandStarted.await(10, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        c.cancel(sid)
        assertEquals("cancelled", turn.get(15, TimeUnit.SECONDS))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 10_000, "cancel should not wait for sleep 30")
        assertTrue(mock.methods.contains("session/cancel"))
        assertEquals("SIGKILL", mock.exitStatuses.single()["signal"])
        assertTrue(events.any { it is LocalEvent.CommandExited && it.signal == "SIGKILL" })
    }

    @Test
    fun `wrong token surfaces 4401 as UNAUTHORIZED`() {
        val mock = agent()
        val e = assertThrows<AcpConnectionException> { client(mock.url, token = "wrong").connect() }
        assertEquals(FailureKind.UNAUTHORIZED, e.kind)
        assertEquals(4401, e.closeCode)
        assertTrue(e.message!!.contains("Sign in again"))
        assertFalse(e.message!!.contains("wrong"), "the token must never be echoed")
    }

    @Test
    fun `missing token surfaces 4401 as UNAUTHORIZED`() {
        val mock = agent()
        val e = assertThrows<AcpConnectionException> { client(mock.url, token = null).connect() }
        assertEquals(FailureKind.UNAUTHORIZED, e.kind)
        assertEquals(null, mock.authHeaders.single())
    }

    @Test
    fun `valid but refused credential surfaces 4403 as FORBIDDEN`() {
        val mock = agent(mode = "forbid")
        val e = assertThrows<AcpConnectionException> { client(mock.url).connect() }
        assertEquals(FailureKind.FORBIDDEN, e.kind)
        assertEquals(4403, e.closeCode)
    }

    @Test
    fun `HTTP 401 and 403 at the handshake are classified`() {
        HttpStatusServer(401).use { s ->
            val e = assertThrows<AcpConnectionException> { client("ws://127.0.0.1:${s.port}/acp").connect() }
            assertEquals(FailureKind.UNAUTHORIZED, e.kind)
            assertEquals(401, e.httpStatus)
        }
        HttpStatusServer(403).use { s ->
            val e = assertThrows<AcpConnectionException> { client("ws://127.0.0.1:${s.port}/acp").connect() }
            assertEquals(FailureKind.FORBIDDEN, e.kind)
        }
        HttpStatusServer(404).use { s ->
            val e = assertThrows<AcpConnectionException> { client("ws://127.0.0.1:${s.port}/acp").connect() }
            assertEquals(FailureKind.HANDSHAKE_FAILED, e.kind)
        }
    }

    @Test
    fun `unreachable host and bad URL are reported cleanly`() {
        val port = java.net.ServerSocket(0).use { it.localPort } // free, nobody listening
        val e = assertThrows<AcpConnectionException> { client("ws://127.0.0.1:$port/acp").connect() }
        assertEquals(FailureKind.UNREACHABLE, e.kind)
        assertTrue(e.message!!.contains("127.0.0.1:$port"))

        val dns = assertThrows<AcpConnectionException> { client("wss://no-such-host.invalid/acp").connect() }
        assertEquals(FailureKind.UNREACHABLE, dns.kind)

        val bad = assertThrows<AcpConnectionException> { client("https://example.com/acp").connect() }
        assertTrue(bad.message!!.contains("ws://"))
    }

    @Test
    fun `a dropped connection mid-turn fails the prompt and notifies, and a reconnect starts fresh`() {
        writeReadme()
        val mock = agent()
        val c = client(mock.url)
        c.connect()
        val sid = c.newSession(project)
        val ex = assertThrows<ExecutionException> { c.prompt(sid, "drop").get(15, TimeUnit.SECONDS) }
        val cause = ex.cause as AcpConnectionException
        assertEquals(FailureKind.DROPPED, cause.kind)
        val deadline = System.currentTimeMillis() + 5000
        while (disconnects.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(FailureKind.DROPPED, disconnects.single().kind)
        assertFalse(c.isConnected)

        val again = client(mock.url)
        again.connect()
        val sid2 = again.newSession(project)
        assertEquals("end_turn", again.prompt(sid2, "go").get(30, TimeUnit.SECONDS))
    }

    @Test
    fun `an agent error answers the prompt with its JSON-RPC code`() {
        val mock = agent()
        val c = client(mock.url)
        c.connect()
        val sid = c.newSession(project)
        val ex = assertThrows<ExecutionException> { c.prompt(sid, "fail").get(15, TimeUnit.SECONDS) }
        val cause = ex.cause as AcpRpcException
        assertEquals(-32000, cause.code)
        assertEquals("the engine failed", cause.message)
        assertTrue(c.isConnected)
    }

    @Test
    fun `extra headers are sent on the upgrade`() {
        val h = Headers.forConnect("tok", Headers.parse("X-Team: blue\n# comment\nAuthorization: ignored-when-token"))
        assertEquals(mapOf("X-Team" to "blue", "Authorization" to "Bearer tok"), h)
        assertNotNull(Headers.parse(""))
        val count = AtomicInteger()
        assertThrows<IllegalArgumentException> { count.incrementAndGet(); Headers.parse("no colon here") }
    }
}
