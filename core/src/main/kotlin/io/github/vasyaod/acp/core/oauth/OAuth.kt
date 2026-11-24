package io.github.vasyaod.acp.core.oauth

import com.sun.net.httpserver.HttpServer
import io.github.vasyaod.acp.core.Json
import io.github.vasyaod.acp.core.asList
import io.github.vasyaod.acp.core.asObj
import io.github.vasyaod.acp.core.int
import io.github.vasyaod.acp.core.str
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class OAuthException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class AuthServerMetadata(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val scopesSupported: List<String> = emptyList(),
)

data class Discovery(
    val metadata: AuthServerMetadata,
    /** RFC 9728 `resource` of the protected resource, sent as the RFC 8707 `resource` parameter when known. */
    val resource: String?,
    /** Which document led to the authorization server, for the settings page. */
    val via: String,
)

data class OAuthToken(val accessToken: String, val tokenType: String?, val expiresInSeconds: Long?, val refreshToken: String?, val scope: String?)

/** Opens the authorization URL in the user's browser (the IDE uses BrowserUtil). */
fun interface BrowserLauncher {
    fun open(url: String)
}

/**
 * OAuth 2.0 authorization code + PKCE (S256) for a public client with a loopback
 * redirect `http://127.0.0.1:<random port>/callback`. The authorization server is
 * discovered generically: RFC 9728 protected-resource metadata on the endpoint's
 * origin, else the configured issuer; then its RFC 8414 metadata.
 */
class OAuthClient(
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) {
    /** The http(s) origin of a ws(s) endpoint: wss://h:p/acp -> https://h:p */
    fun originOf(endpointUrl: String): URI {
        val u = URI(endpointUrl.trim())
        val scheme = when (u.scheme?.lowercase()) {
            "wss", "https" -> "https"
            "ws", "http" -> "http"
            else -> throw OAuthException("The endpoint URL must be ws://, wss://, http:// or https:// (got $endpointUrl).")
        }
        return URI(scheme, null, u.host, u.port, null, null, null)
    }

    fun discover(endpointUrl: String, fallbackIssuer: String?): Discovery {
        val origin = originOf(endpointUrl)
        val path = URI(endpointUrl.trim()).rawPath?.trimEnd('/').orEmpty()
        // RFC 9728 §3.1: the path-specific document first, then the origin's.
        val candidates = buildList {
            if (path.isNotEmpty()) add("$origin/.well-known/oauth-protected-resource$path")
            add("$origin/.well-known/oauth-protected-resource")
        }
        for (url in candidates) {
            val doc = getJson(url) ?: continue
            val servers = doc["authorization_servers"].asList().mapNotNull { it as? String }
            if (servers.isEmpty()) continue
            val md = authServerMetadata(servers.first())
                ?: throw OAuthException("The authorization server ${servers.first()} (from $url) publishes no RFC 8414 metadata.")
            return Discovery(md, doc.str("resource"), "protected-resource metadata at $url")
        }
        val issuer = fallbackIssuer?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?: throw OAuthException(
                "The agent publishes no OAuth protected-resource metadata at $origin. " +
                    "Set the authorization server (issuer) URL in Settings.",
            )
        val md = authServerMetadata(issuer)
            ?: throw OAuthException("No authorization server metadata found for issuer $issuer (tried RFC 8414 and OpenID discovery).")
        return Discovery(md, null, "configured issuer $issuer")
    }

    /** RFC 8414 metadata for [issuer], falling back to OpenID Connect discovery. */
    fun authServerMetadata(issuer: String): AuthServerMetadata? {
        val iu = URI(issuer.trimEnd('/'))
        val base = URI(iu.scheme, null, iu.host, iu.port, null, null, null).toString()
        val ipath = iu.rawPath?.trimEnd('/').orEmpty()
        val urls = listOf(
            "$base/.well-known/oauth-authorization-server$ipath",
            "${issuer.trimEnd('/')}/.well-known/openid-configuration",
        )
        for (u in urls) {
            val doc = getJson(u) ?: continue
            val auth = doc.str("authorization_endpoint") ?: continue
            val token = doc.str("token_endpoint") ?: continue
            return AuthServerMetadata(
                issuer = doc.str("issuer") ?: issuer,
                authorizationEndpoint = auth,
                tokenEndpoint = token,
                scopesSupported = doc["scopes_supported"].asList().mapNotNull { it as? String },
            )
        }
        return null
    }

    private fun getJson(url: String): Map<String, Any?>? = try {
        val res = http.send(
            HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (res.statusCode() == 200) Json.parse(res.body()).asObj().takeIf { it.isNotEmpty() } else null
    } catch (_: Exception) {
        null
    }

    /**
     * Runs the whole browser flow: listen on loopback, open the browser, wait for the
     * callback (up to [timeout]), check `state`, then exchange the code with the verifier.
     * [cancelled] is polled so the IDE's progress indicator can abort the wait.
     */
    fun signIn(
        discovery: Discovery,
        clientId: String,
        scope: String?,
        browser: BrowserLauncher,
        timeout: Duration = Duration.ofMinutes(5),
        cancelled: () -> Boolean = { false },
    ): OAuthToken {
        val verifier = Pkce.newVerifier()
        val challenge = Pkce.challenge(verifier)
        val state = Pkce.randomToken(16)
        LoopbackReceiver().use { receiver ->
            val redirectUri = receiver.redirectUri
            val params = linkedMapOf(
                "response_type" to "code",
                "client_id" to clientId,
                "redirect_uri" to redirectUri,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
                "state" to state,
            )
            scope?.trim()?.takeIf { it.isNotEmpty() }?.let { params["scope"] = it }
            discovery.resource?.let { params["resource"] = it }
            val sep = if (discovery.metadata.authorizationEndpoint.contains('?')) "&" else "?"
            browser.open(discovery.metadata.authorizationEndpoint + sep + form(params))

            val deadline = System.nanoTime() + timeout.toNanos()
            var callback: Map<String, String>? = null
            while (callback == null) {
                if (cancelled()) throw OAuthException("Sign-in was cancelled.")
                if (System.nanoTime() > deadline) throw OAuthException("Timed out waiting for the browser sign-in.")
                callback = try {
                    receiver.result.get(250, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    null
                }
            }
            callback["error"]?.let { err ->
                throw OAuthException("The authorization server refused: $err${callback["error_description"]?.let { " ($it)" } ?: ""}")
            }
            if (callback["state"] != state) throw OAuthException("The sign-in callback had a wrong state parameter; try again.")
            val code = callback["code"] ?: throw OAuthException("The sign-in callback carried no authorization code.")
            return exchange(discovery, clientId, code, verifier, redirectUri)
        }
    }

    fun exchange(discovery: Discovery, clientId: String, code: String, verifier: String, redirectUri: String): OAuthToken {
        val body = linkedMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "code_verifier" to verifier,
        )
        discovery.resource?.let { body["resource"] = it }
        val res = try {
            http.send(
                HttpRequest.newBuilder(URI(discovery.metadata.tokenEndpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(form(body)))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        } catch (e: Exception) {
            throw OAuthException("Cannot reach the token endpoint ${discovery.metadata.tokenEndpoint}: ${e.message}", e)
        }
        val doc = try {
            Json.parse(res.body()).asObj()
        } catch (_: Exception) {
            emptyMap()
        }
        if (res.statusCode() != 200) {
            throw OAuthException(
                "The token endpoint answered HTTP ${res.statusCode()}" +
                    (doc.str("error")?.let { ": $it${doc.str("error_description")?.let { d -> " ($d)" } ?: ""}" } ?: ""),
            )
        }
        val token = doc.str("access_token") ?: throw OAuthException("The token endpoint returned no access_token.")
        return OAuthToken(token, doc.str("token_type"), doc.int("expires_in")?.toLong(), doc.str("refresh_token"), doc.str("scope"))
    }

    companion object {
        fun form(params: Map<String, String>): String =
            params.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }

        private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

        fun parseQuery(raw: String?): Map<String, String> =
            raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
                val i = it.indexOf('=')
                val k = if (i < 0) it else it.substring(0, i)
                val v = if (i < 0) "" else it.substring(i + 1)
                URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
            }
    }
}

object Pkce {
    private val random = SecureRandom()

    fun randomToken(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    /** 43-character verifier (RFC 7636 allows 43..128). */
    fun newVerifier(): String = randomToken(32)

    fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
}

/** A one-shot HTTP listener on 127.0.0.1 (never "localhost") at a random port, path /callback. */
class LoopbackReceiver : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    val result = CompletableFuture<Map<String, String>>()
    val redirectUri: String get() = "http://127.0.0.1:${server.address.port}/callback"

    init {
        server.createContext("/callback") { ex ->
            val q = OAuthClient.parseQuery(ex.requestURI.rawQuery)
            val ok = q["error"] == null && q["code"] != null
            val html = """<!doctype html><html><head><meta charset="utf-8"><title>ACP sign-in</title></head>
                |<body style="font-family:sans-serif;margin:3em">
                |<h2>${if (ok) "Signed in" else "Sign-in failed"}</h2>
                |<p>${if (ok) "You can close this tab and return to the IDE." else "Return to the IDE for details."}</p>
                |</body></html>""".trimMargin().toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(200, html.size.toLong())
            ex.responseBody.use { it.write(html) }
            result.complete(q)
        }
        server.start()
    }

    override fun close() {
        server.stop(0)
    }
}
