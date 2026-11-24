package io.github.vasyaod.acp.core

import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.nio.channels.UnresolvedAddressException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/** A bidirectional text-message channel. JSON-RPC rides on top of it. */
interface Transport {
    fun send(text: String)
    fun close()
}

interface TransportListener {
    fun onText(text: String)

    /** The channel is gone; [error] says why (null only for a close we asked for). */
    fun onClosed(error: AcpConnectionException?)
}

fun interface TransportFactory {
    /** Opens the channel or throws [AcpConnectionException]. */
    fun connect(url: String, headers: Map<String, String>, listener: TransportListener): Transport
}

/** WebSocket transport on the JDK's java.net.http client. */
class WebSocketTransportFactory(
    private val connectTimeout: Duration = Duration.ofSeconds(15),
) : TransportFactory {
    override fun connect(url: String, headers: Map<String, String>, listener: TransportListener): Transport {
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            throw AcpConnectionException(FailureKind.UNREACHABLE, "The endpoint URL is not valid: $url", cause = e)
        }
        if (uri.scheme !in setOf("ws", "wss") || uri.host.isNullOrBlank()) {
            throw AcpConnectionException(
                FailureKind.UNREACHABLE,
                "The endpoint URL must start with ws:// or wss:// and name a host (got \"$url\").",
            )
        }
        val client = HttpClient.newBuilder().connectTimeout(connectTimeout).build()
        val ws = WsListener(listener)
        val builder = client.newWebSocketBuilder().connectTimeout(connectTimeout)
        for ((k, v) in headers) builder.header(k, v)
        val socket = try {
            builder.buildAsync(uri, ws).get(connectTimeout.toMillis() + 5_000, TimeUnit.MILLISECONDS)
        } catch (e: Throwable) {
            throw classify(uri, e)
        }
        return object : Transport {
            private val lock = Any()
            private var tail: CompletableFuture<*> = CompletableFuture.completedFuture(null)

            override fun send(text: String) {
                // java.net.http forbids overlapping sends: chain them.
                synchronized(lock) {
                    tail = tail.handle { _, _ -> null }.thenCompose { socket.sendText(text, true) }
                }
            }

            override fun close() {
                ws.closedByUs = true
                synchronized(lock) {
                    tail = tail.handle { _, _ -> null }.thenCompose { socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye") }
                }
                tail.orTimeout(2, TimeUnit.SECONDS).whenComplete { _, _ -> socket.abort() }
                ws.fire(null)
            }
        }
    }

    private class WsListener(val listener: TransportListener) : WebSocket.Listener {
        private val buf = StringBuilder()
        @Volatile var closedByUs = false
        @Volatile private var fired = false

        fun fire(error: AcpConnectionException?) {
            synchronized(this) {
                if (fired) return
                fired = true
            }
            listener.onClosed(if (closedByUs) null else error)
        }

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buf.append(data)
            if (last) {
                val text = buf.toString()
                buf.setLength(0)
                try {
                    listener.onText(text)
                } catch (_: Throwable) {
                    // A listener bug must not kill the socket.
                }
            }
            webSocket.request(1)
            return null
        }

        override fun onBinary(webSocket: WebSocket, data: java.nio.ByteBuffer, last: Boolean): CompletionStage<*>? {
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String?): CompletionStage<*>? {
            fire(AcpConnectionException.fromClose(statusCode, reason))
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            fire(
                AcpConnectionException(
                    FailureKind.DROPPED,
                    "The connection to the agent dropped (${error.javaClass.simpleName}${error.message?.let { ": $it" } ?: ""}).",
                    cause = error,
                ),
            )
        }
    }

    companion object {
        fun classify(uri: URI, e: Throwable): AcpConnectionException {
            var cause: Throwable? = e
            while (cause is ExecutionException || cause is CompletionException) cause = cause.cause ?: break
            val where = "${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
            return when (cause) {
                is AcpConnectionException -> cause
                is WebSocketHandshakeException -> AcpConnectionException.fromHttpStatus(cause.response.statusCode()).let {
                    AcpConnectionException(it.kind, it.message ?: "", httpStatus = it.httpStatus, cause = cause)
                }
                is HttpConnectTimeoutException, is HttpTimeoutException, is TimeoutException ->
                    AcpConnectionException(FailureKind.UNREACHABLE, "Timed out connecting to $where.", cause = cause)
                is UnresolvedAddressException, is UnknownHostException ->
                    AcpConnectionException(FailureKind.UNREACHABLE, "Cannot resolve host ${uri.host}.", cause = cause)
                is ConnectException ->
                    if (cause.cause is UnresolvedAddressException) {
                        AcpConnectionException(FailureKind.UNREACHABLE, "Cannot resolve host ${uri.host}.", cause = cause)
                    } else {
                        AcpConnectionException(FailureKind.UNREACHABLE, "Cannot reach $where (connection refused or no route).", cause = cause)
                    }
                is SSLException ->
                    AcpConnectionException(FailureKind.UNREACHABLE, "TLS failure talking to $where: ${cause.message}", cause = cause)
                else -> AcpConnectionException(
                    FailureKind.UNREACHABLE,
                    "Cannot connect to $where (${cause?.javaClass?.simpleName}${cause?.message?.let { ": $it" } ?: ""}).",
                    cause = cause,
                )
            }
        }
    }
}
