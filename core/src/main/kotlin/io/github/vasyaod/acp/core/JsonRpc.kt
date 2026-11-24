package io.github.vasyaod.acp.core

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicLong

/** Handles requests and notifications that the *other* side sends us. */
interface RpcHandler {
    /** Answer a request; throw [RpcError] for a JSON-RPC error, anything else becomes -32603. */
    fun onRequest(method: String, params: Map<String, Any?>): Any?

    fun onNotification(method: String, params: Map<String, Any?>)
}

class RpcError(val code: Int, message: String) : RuntimeException(message) {
    companion object {
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
    }
}

/**
 * A symmetric JSON-RPC 2.0 peer over a [Transport]: it sends requests and waits on
 * their answers, and it serves the other side's requests on [executor] (they may block:
 * a permission prompt, a command waiting to exit).
 */
class JsonRpcPeer(
    private val handler: RpcHandler,
    private val executor: ExecutorService,
) : TransportListener {
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<Any?>>()
    @Volatile private var transport: Transport? = null
    @Volatile var closedWith: AcpConnectionException? = null
        private set
    @Volatile var closed = false
        private set
    var onClosed: ((AcpConnectionException?) -> Unit)? = null

    fun attach(t: Transport) {
        transport = t
    }

    fun request(method: String, params: Map<String, Any?>): CompletableFuture<Any?> {
        val fut = CompletableFuture<Any?>()
        if (closed) {
            fut.completeExceptionally(closedWith ?: AcpConnectionException(FailureKind.DROPPED, "Not connected to the agent."))
            return fut
        }
        val id = ids.incrementAndGet()
        pending[id] = fut
        fut.whenComplete { _, _ -> pending.remove(id) }
        send(mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params))
        return fut
    }

    fun notify(method: String, params: Map<String, Any?>) {
        if (!closed) send(mapOf("jsonrpc" to "2.0", "method" to method, "params" to params))
    }

    private fun send(msg: Map<String, Any?>) {
        val t = transport ?: throw IllegalStateException("transport not attached")
        t.send(Json.stringify(msg))
    }

    fun close() {
        transport?.close()
    }

    override fun onText(text: String) {
        val msg = try {
            Json.parse(text).asObj()
        } catch (_: Exception) {
            return
        }
        val method = msg["method"] as? String
        val id = msg["id"]
        if (method != null) {
            val params = msg.obj("params")
            if (id != null) {
                executor.execute { serve(id, method, params) }
            } else {
                try {
                    handler.onNotification(method, params)
                } catch (_: Throwable) {
                }
            }
            return
        }
        val key = (id as? Number)?.toLong() ?: (id as? String)?.toLongOrNull() ?: return
        val fut = pending.remove(key) ?: return
        val err = msg["error"]
        if (err != null) {
            val e = err.asObj()
            fut.completeExceptionally(AcpRpcException(e.int("code") ?: RpcError.INTERNAL_ERROR, e.str("message") ?: "error", e["data"]))
        } else {
            fut.complete(msg["result"])
        }
    }

    private fun serve(id: Any, method: String, params: Map<String, Any?>) {
        val reply: Map<String, Any?> = try {
            val result = handler.onRequest(method, params)
            mapOf("jsonrpc" to "2.0", "id" to id, "result" to result)
        } catch (e: RpcError) {
            mapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to e.code, "message" to (e.message ?: "")))
        } catch (e: Throwable) {
            mapOf(
                "jsonrpc" to "2.0", "id" to id,
                "error" to mapOf("code" to RpcError.INTERNAL_ERROR, "message" to (e.message ?: e.javaClass.simpleName)),
            )
        }
        if (!closed) {
            try {
                send(reply)
            } catch (_: Throwable) {
            }
        }
    }

    override fun onClosed(error: AcpConnectionException?) {
        if (closed) return
        closed = true
        closedWith = error
        val failure = error ?: AcpConnectionException(FailureKind.DROPPED, "The connection was closed.")
        for (f in pending.values.toList()) f.completeExceptionally(failure)
        pending.clear()
        onClosed?.invoke(error)
    }
}
