package io.github.vasyaod.acp.core

import com.sun.net.httpserver.HttpServer
import io.github.vasyaod.acp.core.oauth.BrowserLauncher
import io.github.vasyaod.acp.core.oauth.OAuthClient
import io.github.vasyaod.acp.core.oauth.OAuthException
import io.github.vasyaod.acp.core.oauth.Pkce
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A mock authorization server: RFC 9728 + RFC 8414 metadata, /authorize, /token with PKCE S256 checks. */
class MockAuthServer(var publishProtectedResource: Boolean = true) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val base: String get() = "http://127.0.0.1:${server.address.port}"
    val authorizeRequests = CopyOnWriteArrayList<Map<String, String>>()
    val tokenRequests = CopyOnWriteArrayList<Map<String, String>>()
    private val codes = ConcurrentHashMap<String, Map<String, String>>()
    val issuedToken = "opaque-" + Pkce.randomToken(12)
    var denyAuthorization = false

    init {
        fun json(ex: com.sun.net.httpserver.HttpExchange, status: Int, body: Any?) {
            val b = Json.stringify(body).toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.createContext("/.well-known/oauth-protected-resource") { ex ->
            if (!publishProtectedResource) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
            json(ex, 200, mapOf("resource" to "$base/acp", "authorization_servers" to listOf("$base/issuer")))
        }
        server.createContext("/.well-known/oauth-authorization-server") { ex ->
            if (ex.requestURI.path != "/.well-known/oauth-authorization-server/issuer") {
                ex.sendResponseHeaders(404, -1); ex.close(); return@createContext
            }
            json(
                ex, 200,
                mapOf(
                    "issuer" to "$base/issuer",
                    "authorization_endpoint" to "$base/authorize",
                    "token_endpoint" to "$base/token",
                    "code_challenge_methods_supported" to listOf("S256"),
                ),
            )
        }
        server.createContext("/authorize") { ex ->
            val q = OAuthClient.parseQuery(ex.requestURI.rawQuery)
            authorizeRequests += q
            val redirect = q["redirect_uri"] ?: ""
            val location = if (denyAuthorization) {
                "$redirect?error=access_denied&state=${q["state"]}"
            } else {
                val code = Pkce.randomToken(8)
                codes[code] = q
                "$redirect?code=$code&state=${q["state"]}"
            }
            ex.responseHeaders.add("Location", location)
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/token") { ex ->
            val form = OAuthClient.parseQuery(String(ex.requestBody.readAllBytes(), StandardCharsets.UTF_8))
            tokenRequests += form
            val auth = codes.remove(form["code"] ?: "")
            val ok = auth != null &&
                form["grant_type"] == "authorization_code" &&
                form["client_id"] == auth["client_id"] &&
                form["redirect_uri"] == auth["redirect_uri"] &&
                Pkce.challenge(form["code_verifier"] ?: "") == auth["code_challenge"]
            if (ok) json(ex, 200, mapOf("access_token" to issuedToken, "token_type" to "Bearer", "expires_in" to 2592000))
            else json(ex, 400, mapOf("error" to "invalid_grant"))
        }
        server.start()
    }

    override fun close() = server.stop(0)
}

class OAuthTest {
    @TempDir
    lateinit var project: Path

    private val closers = mutableListOf<AutoCloseable>()

    @AfterEach
    fun tearDown() = closers.reversed().forEach { it.close() }

    /** Plays the user's browser: follows the authorization redirect to the loopback callback. */
    private val browser = BrowserLauncher { url ->
        Thread {
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
                .send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
        }.start()
    }

    @Test
    fun `discovers the authorization server via RFC 9728 then RFC 8414 and completes PKCE`() {
        val auth = MockAuthServer().also { closers += it }
        val oauth = OAuthClient()
        val endpoint = auth.base.replace("http://", "ws://") + "/acp"
        val d = oauth.discover(endpoint, fallbackIssuer = null)
        assertEquals("${auth.base}/authorize", d.metadata.authorizationEndpoint)
        assertEquals("${auth.base}/acp", d.resource)
        assertTrue(d.via.contains("protected-resource"))

        val token = oauth.signIn(d, "jetbrains-acp-plugin", null, browser)
        assertEquals(auth.issuedToken, token.accessToken)

        val q = auth.authorizeRequests.single()
        assertEquals("code", q["response_type"])
        assertEquals("jetbrains-acp-plugin", q["client_id"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals(43, q["code_challenge"]!!.length)
        assertNotNull(q["state"])
        assertTrue(Regex("""http://127\.0\.0\.1:\d+/callback""").matches(q["redirect_uri"]!!), q["redirect_uri"])
        val t = auth.tokenRequests.single()
        assertEquals(q["redirect_uri"], t["redirect_uri"])
        assertTrue(t["code_verifier"]!!.length >= 43)
    }

    @Test
    fun `falls back to the configured issuer when there is no protected-resource metadata`() {
        val auth = MockAuthServer(publishProtectedResource = false).also { closers += it }
        val oauth = OAuthClient()
        val endpoint = auth.base.replace("http://", "ws://") + "/acp"
        val e = assertThrows<OAuthException> { oauth.discover(endpoint, null) }
        assertTrue(e.message!!.contains("issuer"))
        val d = oauth.discover(endpoint, "${auth.base}/issuer")
        assertTrue(d.via.contains("configured issuer"))
        assertEquals(auth.issuedToken, oauth.signIn(d, "jetbrains-acp-plugin", "acp", browser).accessToken)
        assertEquals("acp", auth.authorizeRequests.single()["scope"])
    }

    @Test
    fun `a denied authorization is reported`() {
        val auth = MockAuthServer().also { closers += it }
        auth.denyAuthorization = true
        val oauth = OAuthClient()
        val d = oauth.discover(auth.base.replace("http://", "ws://") + "/acp", null)
        val e = assertThrows<OAuthException> { oauth.signIn(d, "jetbrains-acp-plugin", null, browser) }
        assertTrue(e.message!!.contains("access_denied"))
    }

    @Test
    fun `the signed-in token opens the ACP websocket`() {
        val auth = MockAuthServer().also { closers += it }
        val oauth = OAuthClient()
        val d = oauth.discover(auth.base.replace("http://", "ws://") + "/acp", null)
        val token = oauth.signIn(d, "jetbrains-acp-plugin", null, browser).accessToken

        val agent = MockAcpAgent(token).startAndWait()
        closers += AutoCloseable { agent.shutdown() }
        val client = AcpClient(
            AcpClientOptions(agent.url, Headers.forConnect(token, emptyMap())),
            LocalFileSystemHandler(), LocalProcessStarter(), PermissionPolicy({ PermissionChoice.ALLOW_ONCE }), object : AcpListener {},
        )
        closers += AutoCloseable { client.close() }
        assertEquals("mock-agent", client.connect().name)
        assertEquals("Bearer $token", agent.authHeaders.single())
    }

    @Test
    fun `PKCE S256 is base64url(sha256(verifier)) without padding`() {
        // Reference value from Python: base64.urlsafe_b64encode(hashlib.sha256(v).digest()).rstrip(b"=")
        assertEquals("kjqbaRuEQJNtb5Yw05NzYPDxkJIiJlUb2oJM6OArvG8", Pkce.challenge("dBjftJeZ4CVP-mJ0kzpiSn3wjvn6ZIbBO6Xq9Qbe2wQ"))
        val v = Pkce.newVerifier()
        assertEquals(43, v.length)
        assertTrue(v.matches(Regex("[A-Za-z0-9_-]+")))
    }
}
