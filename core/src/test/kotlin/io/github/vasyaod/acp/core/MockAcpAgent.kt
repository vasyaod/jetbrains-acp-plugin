package io.github.vasyaod.acp.core

import org.java_websocket.WebSocket
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A scripted ACP agent over WebSocket, for tests. It checks the Authorization header,
 * then answers initialize / session/new, and runs a scripted turn per prompt text:
 *  - anything else: text chunk, tool_call, permission, terminal/create `hostname && pwd`,
 *    wait_for_exit, output, release, fs/read_text_file, permission, fs/write_text_file, end_turn
 *  - "slow": a `sleep 30` terminal that only ends on session/cancel (-> stopReason cancelled)
 *  - "drop": a text chunk, then the socket is torn down mid-turn
 *  - "fail": a JSON-RPC -32000 error
 */
class MockAcpAgent(
    private val token: String,
    /** "ok", or "forbid" to close every connection with 4403 after the upgrade. */
    private val mode: String = "ok",
) : WebSocketServer(InetSocketAddress("127.0.0.1", 0)) {
    private val started = CountDownLatch(1)
    private val peers = ConcurrentHashMap<WebSocket, JsonRpcPeer>()
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "mock-agent").apply { isDaemon = true } }

    val authHeaders = CopyOnWriteArrayList<String?>()
    val methods = CopyOnWriteArrayList<String>()
    val promptSessionIds = CopyOnWriteArrayList<String>()
    val permissionAnswers = CopyOnWriteArrayList<String>()
    val terminalOutputs = CopyOnWriteArrayList<String>()
    val exitStatuses = CopyOnWriteArrayList<Map<String, Any?>>()
    val fileReads = CopyOnWriteArrayList<String>()
    val cancelled = CountDownLatch(1)
    private val sessionCwds = ConcurrentHashMap<String, String>()

    init {
        isReuseAddr = true
    }

    val url: String get() = "ws://127.0.0.1:$port/acp"

    fun startAndWait(): MockAcpAgent {
        start()
        check(started.await(10, TimeUnit.SECONDS)) { "mock agent did not start" }
        return this
    }

    override fun onStart() {
        started.countDown()
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val auth = if (handshake.hasFieldValue("Authorization")) handshake.getFieldValue("Authorization") else null
        authHeaders.add(auth)
        if (auth != "Bearer $token") {
            conn.close(4401, "missing or invalid credentials")
            return
        }
        if (mode == "forbid") {
            conn.close(4403, "this credential may not drive an ACP session")
            return
        }
        lateinit var peer: JsonRpcPeer
        peer = JsonRpcPeer(Agent(conn) { peer }, executor)
        peer.attach(object : Transport {
            override fun send(text: String) {
                if (conn.isOpen) conn.send(text)
            }

            override fun close() {
                conn.close()
            }
        })
        peers[conn] = peer
    }

    override fun onMessage(conn: WebSocket, message: String) {
        peers[conn]?.onText(message)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        peers.remove(conn)?.onClosed(AcpConnectionException(FailureKind.DROPPED, "closed"))
    }

    override fun onError(conn: WebSocket?, ex: Exception) {}

    fun shutdown() {
        stop(1000)
        executor.shutdownNow()
    }

    private inner class Agent(val conn: WebSocket, val peerRef: () -> JsonRpcPeer) : RpcHandler {
        private val peer get() = peerRef()

        override fun onNotification(method: String, params: Map<String, Any?>) {
            methods.add(method)
            if (method == "session/cancel") cancelled.countDown()
        }

        override fun onRequest(method: String, params: Map<String, Any?>): Any? {
            methods.add(method)
            return when (method) {
                "initialize" -> {
                    val caps = params.obj("clientCapabilities")
                    check(caps.obj("fs")["readTextFile"] == true && caps["terminal"] == true) { "client must offer fs + terminal" }
                    mapOf(
                        "protocolVersion" to 1,
                        "agentCapabilities" to mapOf("loadSession" to false),
                        "agentInfo" to mapOf("name" to "mock-agent", "version" to "1"),
                        "authMethods" to emptyList<Any>(),
                    )
                }
                "session/new" -> {
                    val cwd = params.str("cwd")!!
                    check(cwd.startsWith("/")) { "cwd must be absolute" }
                    val id = "sess-" + (sessionCwds.size + 1)
                    sessionCwds[id] = cwd
                    mapOf("sessionId" to id)
                }
                "session/prompt" -> {
                    val sid = params.str("sessionId")!!
                    promptSessionIds.add(sid)
                    val text = params["prompt"].asList().joinToString("") { it.asObj().str("text") ?: "" }
                    val cwd = sessionCwds[sid] ?: throw RpcError(RpcError.INVALID_PARAMS, "unknown session")
                    mapOf("stopReason" to turn(sid, cwd, text))
                }
                "session/cancel" -> { cancelled.countDown(); null }
                else -> throw RpcError(RpcError.METHOD_NOT_FOUND, method)
            }
        }

        private fun call(method: String, params: Map<String, Any?>): Map<String, Any?> =
            peer.request(method, params).get(30, TimeUnit.SECONDS).asObj()

        private fun update(sid: String, update: Map<String, Any?>) =
            peer.notify("session/update", mapOf("sessionId" to sid, "update" to update))

        private fun chunk(sid: String, text: String) =
            update(sid, mapOf("sessionUpdate" to "agent_message_chunk", "content" to mapOf("type" to "text", "text" to text)))

        private fun toolCall(sid: String, id: String, kind: String, title: String) =
            update(sid, mapOf("sessionUpdate" to "tool_call", "toolCallId" to id, "title" to title, "kind" to kind, "status" to "pending"))

        private fun toolStatus(sid: String, id: String, status: String, text: String? = null) =
            update(
                sid,
                buildMap {
                    put("sessionUpdate", "tool_call_update"); put("toolCallId", id); put("status", status)
                    if (text != null) put("content", listOf(mapOf("type" to "content", "content" to mapOf("type" to "text", "text" to text))))
                },
            )

        private fun permission(sid: String, id: String, kind: String, title: String): Boolean {
            val res = call(
                "session/request_permission",
                mapOf(
                    "sessionId" to sid,
                    "toolCall" to mapOf("toolCallId" to id, "title" to title, "kind" to kind, "status" to "pending"),
                    "options" to listOf(
                        mapOf("optionId" to "allow", "name" to "Allow", "kind" to "allow_once"),
                        mapOf("optionId" to "allow_always", "name" to "Always allow", "kind" to "allow_always"),
                        mapOf("optionId" to "reject", "name" to "Reject", "kind" to "reject_once"),
                    ),
                ),
            )
            val outcome = res.obj("outcome")
            val answer = if (outcome.str("outcome") == "selected") outcome.str("optionId") ?: "?" else outcome.str("outcome") ?: "?"
            permissionAnswers.add(answer)
            return answer == "allow" || answer == "allow_always"
        }

        private fun turn(sid: String, cwd: String, text: String): String {
            when (text) {
                "fail" -> throw RpcError(-32000, "the engine failed")
                "drop" -> {
                    chunk(sid, "about to drop")
                    Thread.sleep(100)
                    conn.closeConnection(CloseFrame.ABNORMAL_CLOSE, "dropped")
                    Thread.sleep(500)
                    return "end_turn"
                }
                "slow" -> {
                    toolCall(sid, "call_slow", "execute", "sleep 30")
                    if (!permission(sid, "call_slow", "execute", "sleep 30")) return "end_turn"
                    val term = call(
                        "terminal/create",
                        mapOf("sessionId" to sid, "command" to "/bin/sh", "args" to listOf("-c", "sleep 30"), "cwd" to cwd),
                    ).str("terminalId")!!
                    toolStatus(sid, "call_slow", "in_progress")
                    if (!cancelled.await(20, TimeUnit.SECONDS)) return "end_turn"
                    call("terminal/kill", mapOf("sessionId" to sid, "terminalId" to term))
                    exitStatuses.add(call("terminal/wait_for_exit", mapOf("sessionId" to sid, "terminalId" to term)))
                    call("terminal/release", mapOf("sessionId" to sid, "terminalId" to term))
                    toolStatus(sid, "call_slow", "failed", "cancelled")
                    return "cancelled"
                }
            }
            chunk(sid, "Hello from the mock agent. ")
            chunk(sid, "Running a command on your machine.")
            toolCall(sid, "call_1", "execute", "hostname && pwd")
            if (!permission(sid, "call_1", "execute", "hostname && pwd")) {
                toolStatus(sid, "call_1", "failed", "the user did not allow this")
                return "end_turn"
            }
            val term = call(
                "terminal/create",
                mapOf(
                    "sessionId" to sid, "command" to "/bin/sh", "args" to listOf("-c", "hostname && pwd"),
                    "cwd" to cwd, "outputByteLimit" to 200_000,
                ),
            ).str("terminalId")!!
            toolStatus(sid, "call_1", "in_progress")
            exitStatuses.add(call("terminal/wait_for_exit", mapOf("sessionId" to sid, "terminalId" to term)))
            val out = call("terminal/output", mapOf("sessionId" to sid, "terminalId" to term))
            terminalOutputs.add(out.str("output") ?: "")
            call("terminal/release", mapOf("sessionId" to sid, "terminalId" to term))
            toolStatus(sid, "call_1", "completed", out.str("output"))

            toolCall(sid, "call_2", "read", "Read README.md")
            val read = call("fs/read_text_file", mapOf("sessionId" to sid, "path" to "$cwd/README.md", "line" to 2, "limit" to 1))
            fileReads.add(read.str("content") ?: "")
            toolStatus(sid, "call_2", "completed", read.str("content"))

            toolCall(sid, "call_3", "edit", "Write notes/agent.txt")
            if (permission(sid, "call_3", "edit", "Write notes/agent.txt")) {
                peer.request(
                    "fs/write_text_file",
                    mapOf("sessionId" to sid, "path" to "$cwd/notes/agent.txt", "content" to "written by the mock agent\n"),
                ).get(30, TimeUnit.SECONDS)
                toolStatus(sid, "call_3", "completed", "wrote notes/agent.txt")
            } else {
                toolStatus(sid, "call_3", "failed", "rejected")
            }
            chunk(sid, " Done.")
            return "end_turn"
        }
    }
}

/** Answers every request with a bare HTTP status, like a server that refuses before the WebSocket upgrade. */
class HttpStatusServer(private val status: Int) : AutoCloseable {
    private val socket = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort

    init {
        Thread({
            while (!socket.isClosed) {
                val s = try { socket.accept() } catch (_: Exception) { break }
                s.use {
                    val r = it.getInputStream().bufferedReader()
                    while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
                    it.getOutputStream().write("HTTP/1.1 $status Refused\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    it.getOutputStream().flush()
                }
            }
        }, "http-status-server").apply { isDaemon = true }.start()
    }

    override fun close() = socket.close()
}
