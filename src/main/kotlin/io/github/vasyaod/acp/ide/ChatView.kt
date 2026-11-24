package io.github.vasyaod.acp.ide

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.vasyaod.acp.core.LocalEvent
import io.github.vasyaod.acp.core.SessionUpdate
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Rectangle
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/** The chat UI: messages, thoughts, tool-call cards, an input box, Send / Stop / New chat. EDT only. */
class ChatView(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val controller = ChatController(project, this).also { Disposer.register(parent, it) }

    private val messages = ScrollablePanel().apply {
        layout = VerticalLayout(JBUI.scale(8))
        border = JBUI.Borders.empty(8)
    }
    private val scroll = JBScrollPane(messages).apply {
        verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        border = JBUI.Borders.empty()
    }
    private val input = JBTextArea(3, 20).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Ask the agent… (Enter to send, Shift+Enter for a new line)"
    }
    private val sendButton = JButton("Send", AllIcons.Actions.Execute)
    private val stopButton = JButton("Stop", AllIcons.Actions.Suspend).apply { isEnabled = false }
    private val newChatButton = JButton("New chat", AllIcons.General.Add)
    private val settingsButton = JButton(AllIcons.General.Settings).apply { toolTipText = "Settings" }
    private val status = JBLabel("Not connected").apply { foreground = UIUtil.getContextHelpForeground() }

    private var agentText: JBTextArea? = null
    private var thoughtText: JBTextArea? = null
    private val cards = HashMap<String, ToolCallCard>()

    init {
        val top = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply {
            add(newChatButton); add(settingsButton); add(status)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply { add(stopButton); add(sendButton) }
        val bottom = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(4, 8, 8, 8)
            add(JBScrollPane(input).apply { preferredSize = Dimension(100, JBUI.scale(72)) }, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
        add(top, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)

        sendButton.addActionListener { submit() }
        stopButton.addActionListener { controller.stop() }
        newChatButton.addActionListener { controller.newChat() }
        settingsButton.addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, AcpConfigurable::class.java) }
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown && !e.isControlDown && !e.isAltDown) {
                    e.consume()
                    submit()
                }
            }
        })
        addInfo(
            "Chat with a remote ACP agent. Its commands and file edits run here, in ${project.basePath}. " +
                "Configure the endpoint in Settings | Tools | ACP Agent.",
        )
    }

    private fun submit() {
        if (controller.isBusy) return
        val text = input.text.trim()
        if (text.isEmpty()) return
        input.text = ""
        controller.send(text)
    }

    fun setBusy(busy: Boolean) {
        sendButton.isEnabled = !busy
        stopButton.isEnabled = busy
    }

    fun setStatus(text: String) {
        status.text = text
    }

    fun clear() {
        messages.removeAll()
        cards.clear()
        agentText = null
        thoughtText = null
        addInfo("New chat. The next message starts a new session.")
        refresh()
    }

    fun addUserMessage(text: String) {
        endAgentMessage()
        add(bubble("You", text, JBColor(0xE8F0FE, 0x2B3A55)))
    }

    fun endAgentMessage() {
        agentText = null
        thoughtText = null
    }

    fun addInfo(text: String) = add(line(text, UIUtil.getContextHelpForeground()))

    fun addError(text: String) {
        endAgentMessage()
        add(line(text, JBColor.RED))
    }

    fun applyUpdate(update: SessionUpdate) {
        when (update) {
            is SessionUpdate.AgentMessageChunk -> {
                thoughtText = null
                val area = agentText ?: textArea("").also {
                    agentText = it
                    add(labelled("Agent", it, null))
                }
                area.append(update.text)
                refresh()
            }
            is SessionUpdate.AgentThoughtChunk -> {
                if (!AcpSettings.get().state.showThoughts) return
                val area = thoughtText ?: textArea("").also {
                    it.foreground = UIUtil.getContextHelpForeground()
                    it.font = it.font.deriveFont(Font.ITALIC)
                    thoughtText = it
                    add(labelled("Thinking", it, null))
                }
                area.append(update.text)
                refresh()
            }
            is SessionUpdate.UserMessageChunk -> {}
            is SessionUpdate.ToolCall -> {
                endAgentMessage()
                val card = cards[update.toolCallId] ?: ToolCallCard().also {
                    cards[update.toolCallId] = it
                    add(it)
                }
                card.update(update.title, update.kind, update.status, update.content)
                refresh()
            }
            is SessionUpdate.ToolCallUpdate -> {
                val card = cards[update.toolCallId] ?: ToolCallCard().also {
                    endAgentMessage()
                    cards[update.toolCallId] = it
                    add(it)
                }
                card.update(update.title, update.kind, update.status, update.content)
                refresh()
            }
            is SessionUpdate.Plan -> {
                endAgentMessage()
                val text = update.entries.joinToString("\n") {
                    val mark = when (it.status) { "completed" -> "[x]"; "in_progress" -> "[~]"; else -> "[ ]" }
                    "$mark ${it.content}"
                }
                add(labelled("Plan", textArea(text), null))
            }
            is SessionUpdate.Other -> {}
        }
    }

    fun applyLocal(event: LocalEvent) {
        val card = event.toolCallId?.let { cards[it] } ?: ToolCallCard().also {
            endAgentMessage()
            val key = "local-" + System.nanoTime()
            cards[key] = it
            it.update("local", "other", "in_progress", null)
            add(it)
        }
        when (event) {
            is LocalEvent.CommandStarted -> card.appendLocal("$ ${event.commandLine}\n  (running locally in ${event.cwd})\n")
            is LocalEvent.CommandOutput -> card.appendLocal(event.text)
            is LocalEvent.CommandExited -> card.appendLocal(
                if (event.signal != null) "\n[killed: ${event.signal}]\n" else "\n[exit ${event.exitCode}]\n",
            )
            is LocalEvent.FileRead -> card.appendLocal("read ${event.path} (local)\n")
            is LocalEvent.FileWritten -> card.appendLocal("wrote ${event.path} (${event.chars} chars, undoable)\n")
        }
        refresh()
    }

    private fun add(c: JComponent) {
        messages.add(c)
        refresh()
    }

    private fun refresh() {
        val bar = scroll.verticalScrollBar
        val atBottom = bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(40)
        messages.revalidate()
        messages.repaint()
        if (atBottom) SwingUtilities.invokeLater { bar.value = bar.maximum }
    }

    private fun line(text: String, color: java.awt.Color) = textArea(text).apply {
        foreground = color
        font = font.deriveFont(font.size2D - 1f)
    }

    private fun bubble(title: String, text: String, bg: JBColor) = labelled(title, textArea(text), bg)

    private fun labelled(title: String, body: JComponent, bg: java.awt.Color?): JComponent = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
        isOpaque = bg != null
        if (bg != null) background = bg
        border = JBUI.Borders.empty(4, 6)
        add(JBLabel(title).apply { font = font.deriveFont(Font.BOLD); foreground = UIUtil.getContextHelpForeground() }, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
    }

    companion object {
        fun textArea(text: String) = JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            border = JBUI.Borders.empty()
        }
    }
}

/** A tool call: kind, title, status, and what happened locally (command output, files). */
class ToolCallCard : JPanel(BorderLayout(0, JBUI.scale(4))) {
    private val header = JBLabel("", SwingConstants.LEFT)
    private val statusLabel = JBLabel("")
    private val body = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size - 1)
        isOpaque = false
    }
    private var title = ""
    private var kind = "other"
    private var hasLocal = false

    init {
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(4, 6),
        )
        val top = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(header, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.EAST)
        }
        add(top, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
        body.isVisible = false
    }

    fun update(title: String?, kind: String?, status: String?, content: String?) {
        if (!title.isNullOrBlank()) this.title = title
        if (!kind.isNullOrBlank()) this.kind = kind
        header.icon = when (this.kind) {
            "execute" -> AllIcons.Debugger.Console
            "read" -> AllIcons.Actions.Preview
            "edit", "delete", "move" -> AllIcons.Actions.Edit
            "search" -> AllIcons.Actions.Find
            "fetch" -> AllIcons.General.Web
            "think" -> AllIcons.Actions.IntentionBulb
            else -> AllIcons.Nodes.Plugin
        }
        header.text = "${this.kind}: ${this.title}"
        if (status != null) {
            statusLabel.text = status
            statusLabel.foreground = when (status) {
                "completed" -> JBColor(0x2E7D32, 0x81C784)
                "failed" -> JBColor.RED
                else -> UIUtil.getContextHelpForeground()
            }
        }
        // The agent's result text, unless we already show the live local output.
        if (!content.isNullOrEmpty() && !hasLocal) setBody(content)
    }

    fun appendLocal(text: String) {
        if (!hasLocal) {
            hasLocal = true
            body.text = ""
        }
        setBody(body.text + text)
    }

    private fun setBody(text: String) {
        body.text = if (text.length > MAX) "…" + text.takeLast(MAX) else text
        body.isVisible = true
    }

    private companion object {
        const val MAX = 20_000
    }
}

/** Tracks the viewport width so wrapped text areas get the right height. */
class ScrollablePanel : JPanel(), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = visibleRect.height
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
}
