package io.github.stardomains3.oxproxion

interface HistoryPanelHost {
    fun closeHistoryPanel(animated: Boolean = true)
    fun startNewChatFromHistory()
    /** Push Settings without flashing Ask under the history panel. */
    fun openSettingsFromHistory()
    /** Grouped drawer destinations (Code, Roleplay home, Models, Prompt library, Presets). */
    fun openFromHistory(destination: Destination)

    enum class Destination { CODE, ROLEPLAY, MODELS, PROMPTS, PRESETS }
}
