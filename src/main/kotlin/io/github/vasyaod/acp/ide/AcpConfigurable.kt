package io.github.vasyaod.acp.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.vasyaod.acp.core.Headers
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** Settings | Tools | ACP Agent. The token goes to PasswordSafe, everything else to acp-agent.xml. */
class AcpConfigurable : Configurable {
    private val url = JBTextField().apply { emptyText.text = "wss://agent.example.com/acp" }
    private val mode = ComboBox(AuthMode.entries.toTypedArray())
    private val token = JBPasswordField().apply { emptyText.text = "paste a new token to replace the stored one" }
    private val tokenState = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val clearToken = JButton("Forget Stored Token")
    private val signIn = JButton("Sign In with Browser…")
    private val signOut = JButton("Sign Out")
    private val signInState = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val issuer = JBTextField().apply { emptyText.text = "optional, e.g. https://auth.example.com" }
    private val clientId = JBTextField()
    private val scope = JBTextField().apply { emptyText.text = "optional" }
    private val headers = JBTextArea(3, 40).apply { emptyText.text = "X-Example: value   (one per line)" }
    private val autoAllow = JBCheckBox("Allow every command and edit without asking")
    private val showThoughts = JBCheckBox("Show the agent's thinking")
    private var panel: JComponent? = null

    override fun getDisplayName() = "ACP Agent"

    override fun createComponent(): JComponent {
        clearToken.addActionListener {
            val u = url.text.trim()
            ApplicationManager.getApplication().executeOnPooledThread {
                TokenStore.set(AuthMode.TOKEN, u, null)
                refreshSecrets(u)
            }
        }
        signIn.addActionListener {
            val u = url.text.trim()
            signInState.text = "Waiting for the browser…"
            AcpSignIn.start(null, u, issuer.text.trim(), clientId.text.trim(), scope.text.trim()) { _, msg ->
                signInState.text = msg
                refreshSecrets(u)
            }
        }
        signOut.addActionListener {
            val u = url.text.trim()
            ApplicationManager.getApplication().executeOnPooledThread {
                TokenStore.set(AuthMode.BROWSER, u, null)
                refreshSecrets(u)
            }
        }
        mode.addActionListener { updateEnabled() }

        val tokenRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { add(tokenState); add(clearToken) }
        val browserRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { add(signIn); add(signOut); add(signInState) }
        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Agent WebSocket URL:", url)
            .addComponentToRightColumn(hint("Any ACP agent reachable over ws:// or wss://, e.g. wss://agent-swarm.f-proj.com/acp"))
            .addLabeledComponent("Authentication:", mode)
            .addSeparator()
            .addLabeledComponent("Bearer token:", token)
            .addComponentToRightColumn(tokenRow)
            .addSeparator()
            .addComponent(browserRow)
            .addLabeledComponent("Authorization server (issuer):", issuer)
            .addComponentToRightColumn(hint("Used only if the agent publishes no /.well-known/oauth-protected-resource"))
            .addLabeledComponent("Client ID:", clientId)
            .addLabeledComponent("Scope:", scope)
            .addSeparator()
            .addLabeledComponent("Extra headers:", JBScrollPane(headers))
            .addComponent(autoAllow)
            .addComponent(showThoughts)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        reset()
        return panel!!
    }

    private fun hint(text: String) = JBLabel(text).apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBUI.Fonts.smallFont()
    }

    private fun updateEnabled() {
        val m = mode.selectedItem as AuthMode
        token.isEnabled = m == AuthMode.TOKEN
        clearToken.isEnabled = m == AuthMode.TOKEN
        listOf(signIn, signOut, issuer, clientId, scope).forEach { it.isEnabled = m == AuthMode.BROWSER }
    }

    /** PasswordSafe can be slow: read it off the EDT and show only whether a secret is stored. */
    private fun refreshSecrets(u: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val hasToken = u.isNotEmpty() && TokenStore.get(AuthMode.TOKEN, u) != null
            val hasOauth = u.isNotEmpty() && TokenStore.get(AuthMode.BROWSER, u) != null
            ApplicationManager.getApplication().invokeLater({
                tokenState.text = if (hasToken) "A token is stored in the IDE password safe." else "No token stored for this URL."
                if (signInState.text.isNullOrEmpty() || signInState.text.startsWith("Signed") || signInState.text.startsWith("Not signed")) {
                    signInState.text = if (hasOauth) "Signed in" else "Not signed in"
                }
            }, ModalityState.any())
        }
    }

    override fun isModified(): Boolean {
        val s = AcpSettings.get().state
        return url.text.trim() != s.endpointUrl ||
            (mode.selectedItem as AuthMode).name != s.authMode ||
            token.password.isNotEmpty() ||
            issuer.text.trim() != s.issuerUrl ||
            clientId.text.trim() != s.clientId ||
            scope.text.trim() != s.scope ||
            headers.text != s.extraHeaders ||
            autoAllow.isSelected != s.autoAllow ||
            showThoughts.isSelected != s.showThoughts
    }

    override fun apply() {
        val u = url.text.trim()
        if (u.isNotEmpty() && !(u.startsWith("ws://") || u.startsWith("wss://"))) {
            throw ConfigurationException("The agent URL must start with ws:// or wss://")
        }
        try {
            Headers.parse(headers.text)
        } catch (e: IllegalArgumentException) {
            throw ConfigurationException(e.message)
        }
        val s = AcpSettings.get().state
        s.endpointUrl = u
        s.authMode = (mode.selectedItem as AuthMode).name
        s.issuerUrl = issuer.text.trim()
        s.clientId = clientId.text.trim().ifEmpty { AcpSettings.DEFAULT_CLIENT_ID }
        s.scope = scope.text.trim()
        s.extraHeaders = headers.text
        s.autoAllow = autoAllow.isSelected
        s.showThoughts = showThoughts.isSelected
        val newToken = String(token.password).trim()
        token.text = ""
        if (newToken.isNotEmpty()) {
            if (u.isEmpty()) throw ConfigurationException("Set the URL before storing a token (tokens are stored per URL).")
            ApplicationManager.getApplication().executeOnPooledThread {
                TokenStore.set(AuthMode.TOKEN, u, newToken)
                refreshSecrets(u)
            }
        }
    }

    override fun reset() {
        val s = AcpSettings.get().state
        url.text = s.endpointUrl
        mode.selectedItem = AcpSettings.get().authMode
        token.text = ""
        issuer.text = s.issuerUrl
        clientId.text = s.clientId
        scope.text = s.scope
        headers.text = s.extraHeaders
        autoAllow.isSelected = s.autoAllow
        showThoughts.isSelected = s.showThoughts
        updateEnabled()
        refreshSecrets(s.endpointUrl)
    }

    override fun disposeUIResources() {
        panel = null
    }
}
