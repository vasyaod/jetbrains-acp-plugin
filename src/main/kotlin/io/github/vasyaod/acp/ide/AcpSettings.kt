package io.github.vasyaod.acp.ide

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

enum class AuthMode(val label: String) {
    NONE("None"),
    TOKEN("Token"),
    BROWSER("Sign in with browser"),
    ;

    override fun toString() = label
}

/** Plain settings. The token is NOT here: it lives in the IDE password safe ([TokenStore]). */
@Service(Service.Level.APP)
@State(name = "AcpAgentSettings", storages = [Storage("acp-agent.xml")])
class AcpSettings : PersistentStateComponent<AcpSettings.State> {
    class State {
        var endpointUrl: String = ""
        var authMode: String = AuthMode.TOKEN.name
        /** "Name: value" per line, sent on the WebSocket upgrade. */
        var extraHeaders: String = ""
        /** Fallback authorization server when the agent publishes no RFC 9728 metadata. */
        var issuerUrl: String = ""
        var clientId: String = DEFAULT_CLIENT_ID
        var scope: String = ""
        var autoAllow: Boolean = false
        var showThoughts: Boolean = true
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(s: State) {
        state = s
    }

    val authMode: AuthMode get() = runCatching { AuthMode.valueOf(state.authMode) }.getOrDefault(AuthMode.TOKEN)

    companion object {
        const val DEFAULT_CLIENT_ID = "jetbrains-acp-plugin"

        fun get(): AcpSettings = ApplicationManager.getApplication().getService(AcpSettings::class.java)
    }
}

/** Secrets in PasswordSafe (OS keychain / KeePass), keyed per endpoint so switching agents keeps both. */
object TokenStore {
    private fun attrs(kind: String, endpoint: String) =
        CredentialAttributes(generateServiceName("ACP Agent Client", "$kind@${endpoint.trim()}"))

    fun get(mode: AuthMode, endpoint: String): String? = when (mode) {
        AuthMode.NONE -> null
        AuthMode.TOKEN -> PasswordSafe.instance.getPassword(attrs("token", endpoint))
        AuthMode.BROWSER -> PasswordSafe.instance.getPassword(attrs("oauth", endpoint))
    }?.takeIf { it.isNotBlank() }

    fun set(mode: AuthMode, endpoint: String, secret: String?) {
        val kind = when (mode) {
            AuthMode.NONE -> return
            AuthMode.TOKEN -> "token"
            AuthMode.BROWSER -> "oauth"
        }
        PasswordSafe.instance.set(attrs(kind, endpoint), secret?.takeIf { it.isNotBlank() }?.let { Credentials("acp", it) })
    }
}
