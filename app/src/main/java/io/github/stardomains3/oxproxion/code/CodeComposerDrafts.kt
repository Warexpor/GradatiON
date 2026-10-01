package io.github.stardomains3.oxproxion.code

/**
 * Unsent Code session composer text (and pictures) while the screen is gone.
 * Memory only, like the transcript: a process death drops it. A blank line with no
 * pictures is not a draft.
 */
object CodeComposerDrafts {

    data class Draft(val text: String, val attachments: List<PromptAttachment>)

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
