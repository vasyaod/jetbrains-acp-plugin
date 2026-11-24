package io.github.vasyaod.acp.ide

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import io.github.vasyaod.acp.core.oauth.BrowserLauncher
import io.github.vasyaod.acp.core.oauth.OAuthClient

/** "Sign in with browser": OAuth 2.0 code + PKCE with a loopback redirect, token into PasswordSafe. */
object AcpSignIn {
    fun start(
        project: Project?,
        url: String = AcpSettings.get().state.endpointUrl,
        issuer: String = AcpSettings.get().state.issuerUrl,
        clientId: String = AcpSettings.get().state.clientId,
        scope: String = AcpSettings.get().state.scope,
        onDone: (Boolean, String) -> Unit = { _, _ -> },
    ) {
        object : Task.Backgroundable(project, "Signing in to the ACP agent", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Discovering the authorization server…"
                val oauth = OAuthClient()
                val discovery = oauth.discover(url, issuer)
                indicator.text = "Waiting for the browser sign-in (${discovery.metadata.issuer})…"
                val token = oauth.signIn(
                    discovery,
                    clientId.ifBlank { AcpSettings.DEFAULT_CLIENT_ID },
                    scope,
                    BrowserLauncher { BrowserUtil.browse(it) },
                    cancelled = { indicator.isCanceled },
                )
                TokenStore.set(AuthMode.BROWSER, url, token.accessToken)
            }

            override fun onSuccess() {
                notify(project, "Signed in to $url.", NotificationType.INFORMATION)
                onDone(true, "Signed in")
            }

            override fun onThrowable(error: Throwable) {
                notify(project, "Sign-in failed: ${error.message}", NotificationType.ERROR)
                onDone(false, error.message ?: "failed")
            }
        }.queue()
    }

    private fun notify(project: Project?, text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("ACP Agent")
            .createNotification("ACP Agent", text, type).notify(project)
    }
}
