package io.github.vasyaod.acp.core

import java.util.concurrent.ConcurrentHashMap

enum class PermissionChoice { ALLOW_ONCE, ALLOW_ALWAYS, REJECT }

data class PermissionOption(val optionId: String, val name: String, val kind: String)

data class PermissionRequest(
    val sessionId: String,
    val toolCallId: String?,
    val title: String,
    val kind: String,
    val rawInput: Any?,
    val options: List<PermissionOption>,
)

/** Asks the human. Called on a background thread; it may block until they answer. */
fun interface PermissionPrompter {
    fun ask(request: PermissionRequest): PermissionChoice
}

/**
 * Decides `session/request_permission`: the auto-allow setting, then "always allow"
 * remembered per tool kind for the chat (session), then the human.
 */
class PermissionPolicy(
    private val prompter: PermissionPrompter,
    private val autoAllow: () -> Boolean = { false },
) {
    private val always = ConcurrentHashMap<String, MutableSet<String>>()

    fun isAlwaysAllowed(sessionId: String, kind: String): Boolean = always[sessionId]?.contains(kind) == true

    fun forget(sessionId: String) {
        always.remove(sessionId)
    }

    /** Returns the chosen ACP option id, or null for "no suitable option" (answered as cancelled). */
    fun decide(request: PermissionRequest): String? {
        val choice = when {
            autoAllow() -> PermissionChoice.ALLOW_ONCE
            isAlwaysAllowed(request.sessionId, request.kind) -> PermissionChoice.ALLOW_ALWAYS
            else -> prompter.ask(request)
        }
        if (choice == PermissionChoice.ALLOW_ALWAYS) {
            always.computeIfAbsent(request.sessionId) { ConcurrentHashMap.newKeySet() }.add(request.kind)
        }
        return optionFor(choice, request.options)
    }

    companion object {
        fun optionFor(choice: PermissionChoice, options: List<PermissionOption>): String? {
            fun byKind(vararg kinds: String) = kinds.firstNotNullOfOrNull { k -> options.firstOrNull { it.kind == k }?.optionId }
            fun byId(vararg ids: String) = ids.firstNotNullOfOrNull { k -> options.firstOrNull { it.optionId == k }?.optionId }
            return when (choice) {
                PermissionChoice.ALLOW_ALWAYS -> byKind("allow_always") ?: byId("allow_always") ?: byKind("allow_once") ?: byId("allow")
                PermissionChoice.ALLOW_ONCE -> byKind("allow_once") ?: byId("allow")
                PermissionChoice.REJECT -> byKind("reject_once") ?: byId("reject") ?: byKind("reject_always")
            }
        }
    }
}
