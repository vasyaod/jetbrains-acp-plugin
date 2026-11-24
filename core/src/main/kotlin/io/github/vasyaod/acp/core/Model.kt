package io.github.vasyaod.acp.core

/** The `session/update` notifications a chat UI renders. */
sealed class SessionUpdate {
    data class AgentMessageChunk(val text: String) : SessionUpdate()
    data class AgentThoughtChunk(val text: String) : SessionUpdate()
    data class UserMessageChunk(val text: String) : SessionUpdate()

    data class ToolCall(
        val toolCallId: String,
        val title: String,
        val kind: String,
        val status: String,
        val rawInput: Any?,
        val locations: List<String>,
        val content: String?,
    ) : SessionUpdate()

    /** Only the fields the agent sent are non-null. */
    data class ToolCallUpdate(
        val toolCallId: String,
        val title: String?,
        val kind: String?,
        val status: String?,
        val content: String?,
    ) : SessionUpdate()

    data class Plan(val entries: List<PlanEntry>) : SessionUpdate()

    /** Anything else (available_commands_update, current_mode_update, …), kept raw. */
    data class Other(val type: String, val raw: Map<String, Any?>) : SessionUpdate()

    data class PlanEntry(val content: String, val status: String, val priority: String)

    companion object {
        fun parse(update: Map<String, Any?>): SessionUpdate {
            val type = update.str("sessionUpdate") ?: ""
            return when (type) {
                "agent_message_chunk" -> AgentMessageChunk(contentBlockText(update["content"]))
                "agent_thought_chunk" -> AgentThoughtChunk(contentBlockText(update["content"]))
                "user_message_chunk" -> UserMessageChunk(contentBlockText(update["content"]))
                "tool_call" -> ToolCall(
                    toolCallId = update.str("toolCallId") ?: "",
                    title = update.str("title") ?: "",
                    kind = update.str("kind") ?: "other",
                    status = update.str("status") ?: "pending",
                    rawInput = update["rawInput"],
                    locations = update["locations"].asList().mapNotNull { it.asObj().str("path") },
                    content = toolContentText(update["content"]),
                )
                "tool_call_update" -> ToolCallUpdate(
                    toolCallId = update.str("toolCallId") ?: "",
                    title = update.str("title"),
                    kind = update.str("kind"),
                    status = update.str("status"),
                    content = toolContentText(update["content"]),
                )
                "plan" -> Plan(
                    update["entries"].asList().map {
                        val e = it.asObj()
                        PlanEntry(e.str("content") ?: "", e.str("status") ?: "pending", e.str("priority") ?: "medium")
                    },
                )
                else -> Other(type, update)
            }
        }

        /** Text of one ACP content block ({type:"text", text}); other block types are summarised. */
        fun contentBlockText(block: Any?): String {
            val b = block.asObj()
            return when (b.str("type")) {
                "text" -> b.str("text") ?: ""
                "resource_link" -> "[${b.str("name") ?: b.str("uri") ?: "resource"}]"
                "image" -> "[image]"
                "audio" -> "[audio]"
                "resource" -> b.obj("resource").str("text") ?: "[resource]"
                null -> ""
                else -> ""
            }
        }

        /** Text of a tool call's `content` array (content blocks, diffs, terminals), or null if absent. */
        fun toolContentText(content: Any?): String? {
            val list = content as? List<*> ?: return null
            val sb = StringBuilder()
            for (item in list) {
                val c = item.asObj()
                when (c.str("type")) {
                    "content" -> sb.append(contentBlockText(c["content"]))
                    "diff" -> sb.append("[diff ${c.str("path") ?: ""}]\n").append(c.str("newText") ?: "")
                    "terminal" -> sb.append("[terminal ${c.str("terminalId") ?: ""}]")
                }
            }
            return sb.toString()
        }
    }
}

/**
 * What the plugin did on this machine for the agent. The UI shows these on the
 * tool-call cards so the user can see the work happen locally.
 */
sealed class LocalEvent {
    abstract val sessionId: String
    abstract val toolCallId: String?

    data class CommandStarted(
        override val sessionId: String,
        override val toolCallId: String?,
        val terminalId: String,
        val commandLine: String,
        val cwd: String,
    ) : LocalEvent()

    data class CommandOutput(
        override val sessionId: String,
        override val toolCallId: String?,
        val terminalId: String,
        val text: String,
    ) : LocalEvent()

    data class CommandExited(
        override val sessionId: String,
        override val toolCallId: String?,
        val terminalId: String,
        val exitCode: Int?,
        val signal: String?,
    ) : LocalEvent()

    data class FileRead(override val sessionId: String, override val toolCallId: String?, val path: String) : LocalEvent()

    data class FileWritten(
        override val sessionId: String,
        override val toolCallId: String?,
        val path: String,
        val chars: Int,
    ) : LocalEvent()
}

data class AgentInfo(val name: String?, val version: String?, val protocolVersion: Int?, val raw: Map<String, Any?>)
