package io.github.stardomains3.oxproxion.code

/**
 * Unsent Code composer text (and pictures) while the screen is gone.
 * Memory only, like the transcript: a process death drops it. A blank line with no
 * pictures is not a draft.
 *
 * Session drafts key on the session id. Home drafts key on [homeKey] so each machine
 * keeps its own unsent line when you switch hosts or the Code home view is rebuilt.
 */
object CodeComposerDrafts {

    data class Draft(val text: String, val attachments: List<PromptAttachment>)

    private const val HOME_PREFIX = "home\u0000"

    /** Stable map key for the Code home composer on [hostId], or null when blank. */
    fun homeKey(hostId: String): String? {
        val id = hostId.trim()
        if (id.isEmpty()) return null
        return HOME_PREFIX + id
    }

    fun park(
        drafts: MutableMap<String, Draft>,
        sessionId: String,
        text: String,
        attachments: List<PromptAttachment> = emptyList(),
    ) {
        if (sessionId.isBlank() || (text.isBlank() && attachments.isEmpty())) {
            drafts.remove(sessionId)
        } else {
            drafts[sessionId] = Draft(text, attachments.toList())
        }
    }
}
