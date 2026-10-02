package io.github.stardomains3.oxproxion

interface HistoryPanelHost {
    fun closeHistoryPanel(animated: Boolean = true)
    fun startNewChatFromHistory()
    /** Drop the unsent line for this chat, including when it is the one on screen. */
    fun forgetUnsentDraft(sessionId: Long)
    /** True when this chat still has unsent text or a parked photo/file. */
    fun hasUnsentDraft(sessionId: Long): Boolean
    /**
     * The unsent line History should show under the title. Blank when there is nothing
     * waiting. A staged photo or file with no caption still returns a short label so the
     * row reads as a draft the same way Discard draft does.
     */
    fun unsentDraftPreview(sessionId: Long): String
    /**
     * Photo / Audio / files label for this chat's staged attachment, even when a caption
     * is also waiting. Search uses this so "Photo" still finds a draft that has both.
     */
    fun unsentAttachmentLabel(sessionId: Long): String
    /** Push Settings without flashing Ask under the history panel. */
    fun openSettingsFromHistory()
    /** Grouped drawer destinations (Code, Roleplay home, Models, Prompt library, Presets). */
    fun openFromHistory(destination: Destination)

    enum class Destination { CODE, ROLEPLAY, MODELS, PROMPTS, PRESETS }
}
