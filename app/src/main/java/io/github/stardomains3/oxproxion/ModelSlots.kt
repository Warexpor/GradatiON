package io.github.stardomains3.oxproxion

/**
 * Chat and Roleplay remember their own model, so "local for RP, cloud for Chat" survives a tab
 * switch. Chat keeps the historical pref key, which is also the migration: the Roleplay slot
 * starts out as whatever Chat had.
 */
object ModelSlots {
    const val KEY_CHAT = "modelvalenewchat"
    const val KEY_RP = "modelvalenewchat_rp"

    fun key(mode: ChatMode): String = if (mode == ChatMode.RP) KEY_RP else KEY_CHAT

    /** @return the stored model for [mode], or the Chat one when Roleplay has never picked. */
    fun resolve(mode: ChatMode, stored: (String) -> String?, default: String): String {
        val own = stored(key(mode))?.takeIf { it.isNotBlank() }
        if (own != null) return own
        return stored(KEY_CHAT)?.takeIf { it.isNotBlank() } ?: default
    }
}
