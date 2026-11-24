package io.github.vasyaod.acp.core

/** Why the connection to the agent failed or ended; each kind has its own user-facing advice. */
enum class FailureKind {
    /** 4401 close code or HTTP 401 at the handshake: no token, wrong token, expired sign-in. */
    UNAUTHORIZED,

    /** 4403 close code or HTTP 403 at the handshake: the server refused this credential. */
    FORBIDDEN,

    /** DNS failure, refused connection, TLS failure or timeout before the socket opened. */
    UNREACHABLE,

    /** The socket was open and then went away (network drop, server restart, other close codes). */
    DROPPED,

    /** The server answered the upgrade with something else (404, 500, …). */
    HANDSHAKE_FAILED,
}

/** A connection-level failure, with a message that is safe to show to the user (never contains the token). */
class AcpConnectionException(
    val kind: FailureKind,
    message: String,
    val closeCode: Int? = null,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    companion object {
        fun fromClose(code: Int, reason: String?): AcpConnectionException {
            val why = reason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
            return when (code) {
                4401 -> AcpConnectionException(
                    FailureKind.UNAUTHORIZED,
                    "The agent rejected the credentials (4401$why). Sign in again or update the token in Settings.",
                    closeCode = code,
                )
                4403 -> AcpConnectionException(
                    FailureKind.FORBIDDEN,
                    "The agent refused this credential (4403$why). It is valid but not allowed to open an ACP session.",
                    closeCode = code,
                )
                else -> AcpConnectionException(
                    FailureKind.DROPPED,
                    "The connection to the agent closed (code $code$why).",
                    closeCode = code,
                )
            }
        }

        fun fromHttpStatus(status: Int): AcpConnectionException = when (status) {
            401 -> AcpConnectionException(
                FailureKind.UNAUTHORIZED,
                "The agent rejected the credentials (HTTP 401). Sign in again or update the token in Settings.",
                httpStatus = status,
            )
            403 -> AcpConnectionException(
                FailureKind.FORBIDDEN,
                "The agent refused the connection (HTTP 403): the token is missing, invalid, or not allowed to open an ACP session.",
                httpStatus = status,
            )
            else -> AcpConnectionException(
                FailureKind.HANDSHAKE_FAILED,
                "The server did not accept a WebSocket at this URL (HTTP $status). Check the endpoint URL.",
                httpStatus = status,
            )
        }
    }
}

/** A JSON-RPC error answered by the agent (e.g. -32000 engine failure, -32001 turn already running). */
class AcpRpcException(val code: Int, message: String, val data: Any? = null) : RuntimeException(message)
