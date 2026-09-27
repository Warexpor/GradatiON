package io.github.stardomains3.oxproxion

/**
 * What one mode is allowed to show and change. Ask and Roleplay no longer flip each other's
 * tool and web flags; Roleplay simply does not offer them. Code mode has its own [code.CodeModeHost].
 */
internal interface ModeController {
    fun webSearchSelected(savedPreference: Boolean): Boolean
    fun toolsSelected(savedPreference: Boolean): Boolean
    fun allowsToolToggle(): Boolean
}

internal class AskModeController : ModeController {
    override fun webSearchSelected(savedPreference: Boolean) = savedPreference
    override fun toolsSelected(savedPreference: Boolean) = savedPreference
    override fun allowsToolToggle() = true
}

internal class RpModeController : ModeController {
    override fun webSearchSelected(savedPreference: Boolean) = false
    override fun toolsSelected(savedPreference: Boolean) = false
    override fun allowsToolToggle() = false
}
