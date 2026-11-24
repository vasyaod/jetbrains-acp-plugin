package io.github.vasyaod.acp.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Paths

/**
 * Talks to the live agent-swarm endpoint. Runs only when $AGENT_SWARM_RUN_TOKEN is set
 * (it is in agent-swarm runs, not in CI). A run token is refused for ACP by design, so
 * the client must surface a clean FORBIDDEN / UNAUTHORIZED error. The token is never printed.
 */
class RealServerSmokeTest {
    private val url = System.getenv("ACP_SMOKE_URL") ?: "wss://agent-swarm.f-proj.com/acp"

    private fun connect(token: String): AcpConnectionException = assertThrows {
        AcpClient(
            AcpClientOptions(url, Headers.forConnect(token, emptyMap())),
            LocalFileSystemHandler(), LocalProcessStarter(), PermissionPolicy({ PermissionChoice.REJECT }), object : AcpListener {},
        ).use { it.connect(); it.newSession(Paths.get(".")) }
    }

    private inline fun <T> AcpClient.use(block: (AcpClient) -> T): T = try { block(this) } finally { close() }

    @Test
    fun `a run token is refused and surfaced as an access error`() {
        val token = System.getenv("AGENT_SWARM_RUN_TOKEN")
        assumeTrue(!token.isNullOrBlank(), "AGENT_SWARM_RUN_TOKEN not set")
        val e = connect(token!!)
        println("real server, run token -> ${e.kind} (close=${e.closeCode}, http=${e.httpStatus}): ${e.message}")
        assertTrue(e.kind == FailureKind.FORBIDDEN || e.kind == FailureKind.UNAUTHORIZED, "got ${e.kind}: ${e.message}")
        assertFalse(e.message!!.contains(token))
    }

    @Test
    fun `a bogus token is refused`() {
        assumeTrue(!System.getenv("AGENT_SWARM_RUN_TOKEN").isNullOrBlank(), "live smoke tests are opt-in via AGENT_SWARM_RUN_TOKEN")
        val e = connect("not-a-real-token")
        println("real server, bogus token -> ${e.kind} (close=${e.closeCode}, http=${e.httpStatus}): ${e.message}")
        assertTrue(e.kind == FailureKind.FORBIDDEN || e.kind == FailureKind.UNAUTHORIZED, "got ${e.kind}: ${e.message}")
    }
}
