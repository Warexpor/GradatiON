package io.github.stardomains3.oxproxion

import kotlinx.serialization.json.Json

/**
 * Prompt and system-message backups. A file from a newer version may carry fields this one
 * does not know; chat import already ignores those, and these two used to reject the whole file.
 */
internal object LibraryBackup {
    private val json = Json { ignoreUnknownKeys = true }

    fun prompts(text: String): List<Prompt> = json.decodeFromString(text)

    fun systemMessages(text: String): List<SystemMessage> = json.decodeFromString(text)
}
