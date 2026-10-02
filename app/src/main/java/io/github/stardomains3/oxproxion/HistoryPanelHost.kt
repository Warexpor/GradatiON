package io.github.stardomains3.oxproxion

interface HistoryPanelHost {
    fun closeHistoryPanel(animated: Boolean = true)
    fun startNewChatFromHistory()
    /** Drop the unsent line for this chat, including when it is the one on screen. */
    fun forgetUnsentDraft(sessionId: Long)
    /** True when this chat still has unsent text or a parked photo/file. */
    fun hasUnsentDraft(sessionId: Long): Boolean
    /** Push Settings without flashing Ask under the history panel. */
    fun openSettingsFromHistory()
    /** Grouped drawer destinations (Code, Roleplay home, Models, Prompt library, Presets). */
    fun openFromHistory(destination: Destination)

    enum class Destination { CODE, ROLEPLAY, MODELS, PROMPTS, PRESETS }
}
