package io.github.vasyaod.acp.ide

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.vasyaod.acp.core.AcpClient
import io.github.vasyaod.acp.core.AcpClientOptions
import io.github.vasyaod.acp.core.AcpConnectionException
import io.github.vasyaod.acp.core.AcpListener
import io.github.vasyaod.acp.core.AcpRpcException
import io.github.vasyaod.acp.core.FailureKind
import io.github.vasyaod.acp.core.Headers
import io.github.vasyaod.acp.core.LocalEvent
import io.github.vasyaod.acp.core.PermissionPolicy
import io.github.vasyaod.acp.core.SessionUpdate
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/** One chat in one project: owns the connection, the ACP session and the running turn. */
class ChatController(private val project: Project, private val view: ChatView) : Disposable {
    private val worker = AppExecutorUtil.createBoundedApplicationPoolExecutor("ACP Agent connect", 1)
    private val policy = PermissionPolicy(IdePermissionPrompter(project)) { AcpSettings.get().state.autoAllow }

    @Volatile private var client: AcpClient? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var turn: CompletableFuture<String>? = null
    @Volatile private var lostSession = false
    /** Bumped by New chat, so a late answer from the old chat does not print into the new one. */
    @Volatile private var generation = 0
    /** The same failure reaches us twice (the turn's future and onDisconnected); show it once. */
    private var lastShownError: Throwable? = null

    /** True from Send until the turn ends (including while connecting). */
    @Volatile private var sending = false

    val isBusy: Boolean get() = sending || turn?.isDone == false

    fun send(text: String) {
        if (text.isBlank() || isBusy) return
        sending = true
        view.addUserMessage(text)
        view.setBusy(true)
        val gen = generation
        worker.execute {
            try {
                val (c, sid) = ensureSession()
                val f = c.prompt(sid, text)
                turn = f
                f.whenComplete { reason, err -> edt { finishTurn(gen, reason, err) } }
            } catch (e: Throwable) {
                edt { finishTurn(gen, null, e) }
            }
        }
    }

    private fun finishTurn(gen: Int, reason: String?, err: Throwable?) {
        if (gen != generation) return
        sending = false
        view.setBusy(false)
        view.endAgentMessage()
        if (err == null) {
            when (reason) {
                "end_turn", null -> {}
                "cancelled" -> view.addInfo("Stopped.")
                else -> view.addInfo("Turn ended: $reason")
            }
            return
        }
        var e: Throwable = err
        while ((e is CompletionException || e is ExecutionException) && e.cause != null) e = e.cause!!
        when (e) {
            is AcpConnectionException -> showConnectionError(e)
            is AcpRpcException -> view.addError(
                when (e.code) {
                    -32001 -> "A turn is already running in this session."
                    else -> "The agent reported an error (${e.code}): ${e.message}"
                },
            )
            is UserFacingException -> view.addError(e.message ?: "")
            else -> view.addError("Unexpected error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun ensureSession(): Pair<AcpClient, String> {
        val settings = AcpSettings.get().state
        val url = settings.endpointUrl.trim()
        if (url.isEmpty()) throw UserFacingException("Set the agent's WebSocket URL in Settings | Tools | ACP Agent.")
        var c = client
        if (c == null || !c.isConnected) {
            val mode = AcpSettings.get().authMode
            val token = TokenStore.get(mode, url)
            if (mode == AuthMode.TOKEN && token == null) {
                throw UserFacingException("No token is stored for $url. Add one in Settings | Tools | ACP Agent.")
            }
            if (mode == AuthMode.BROWSER && token == null) {
                edt { offerSignIn("You are not signed in to $url.") }
                throw UserFacingException("Not signed in. Use \"Sign in with browser\" in Settings | Tools | ACP Agent.")
            }
            val extra = try {
                Headers.parse(settings.extraHeaders)
            } catch (e: IllegalArgumentException) {
                throw UserFacingException(e.message ?: "bad extra headers")
            }
            edt { view.setStatus("Connecting to $url…") }
            val created = AcpClient(
                AcpClientOptions(url, Headers.forConnect(token, extra), clientVersion = PLUGIN_VERSION),
                IdeFileSystem(project), IdeProcessStarter(project), policy, Listener(),
            )
            try {
                val info = created.connect()
                edt {
                    view.setStatus("Connected: ${info.name ?: "agent"}${info.version?.let { " $it" } ?: ""}")
                }
            } catch (e: Throwable) {
                created.close()
                edt { view.setStatus("Not connected") }
                throw e
            }
            client?.close()
            client = created
            c = created
            sessionId = null
        }
        var sid = sessionId
        if (sid == null) {
            val base = project.basePath ?: throw UserFacingException("This project has no base directory to work in.")
            sid = c.newSession(Paths.get(base))
            sessionId = sid
            val note = if (lostSession) " The previous session ended with the connection; this is a new one." else ""
            lostSession = false
            edt { view.addInfo("New session. Commands and file edits run on this machine in $base.$note") }
        }
        return c to sid
    }

    fun stop() {
        val c = client ?: return
        val sid = sessionId ?: return
        if (!isBusy) return
        view.addInfo("Stopping…")
        worker.execute { runCatching { c.cancel(sid) } }
    }

    fun newChat() {
        val c = client
        val sid = sessionId
        if (c != null && sid != null) {
            if (isBusy) runCatching { c.cancel(sid) }
            c.forgetSession(sid)
        }
        sessionId = null
        turn = null
        lostSession = false
        generation++
        sending = false
        view.clear()
        view.setBusy(false)
    }

    private inner class Listener : AcpListener {
        override fun onSessionUpdate(sessionId: String, update: SessionUpdate) {
            if (sessionId != this@ChatController.sessionId) return
            edt { view.applyUpdate(update) }
        }

        override fun onLocalEvent(event: LocalEvent) {
            if (event.sessionId != sessionId) return
            edt { view.applyLocal(event) }
        }

        override fun onDisconnected(error: AcpConnectionException) {
            client = null
            if (sessionId != null) lostSession = true
            sessionId = null
            edt {
                view.setStatus("Disconnected")
                showConnectionError(error)
            }
        }
    }

    private fun showConnectionError(e: AcpConnectionException) {
        if (e === lastShownError) return
        lastShownError = e
        view.addError(e.message ?: "Connection error")
        when (e.kind) {
            FailureKind.UNAUTHORIZED -> offerSignIn(e.message ?: "")
            FailureKind.FORBIDDEN -> if (e.httpStatus == 403) offerSignIn(e.message ?: "")
            FailureKind.DROPPED -> view.addInfo("Send a message to reconnect; it starts a new session (the agent cannot resume the old one).")
            else -> {}
        }
    }

    private fun offerSignIn(message: String) {
        val mode = AcpSettings.get().authMode
        val n = NotificationGroupManager.getInstance().getNotificationGroup("ACP Agent")
            .createNotification("ACP Agent", message, NotificationType.WARNING)
        if (mode == AuthMode.BROWSER) {
            n.addAction(NotificationAction.createSimpleExpiring("Sign in again") { AcpSignIn.start(project) })
        }
        n.addAction(NotificationAction.createSimpleExpiring("Open settings") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, AcpConfigurable::class.java)
        })
        n.notify(project)
    }

    private fun edt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) block() }, ModalityState.any())
    }

    override fun dispose() {
        client?.close()
        client = null
        worker.shutdownNow()
    }

    class UserFacingException(message: String) : RuntimeException(message)

    companion object {
        const val PLUGIN_VERSION = "0.1.0"
    }
}
