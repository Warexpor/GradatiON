package io.github.stardomains3.oxproxion

import kotlinx.serialization.json.Json

/** One row of the Roleplay characters list: a character and the chat you would pick up with them. */
data class RpChatSummary(
    /** The chat the row opens: the newest one with this character. Null until you have talked. */
    val sessionId: Long?,
    val character: RpCharacter?,
    val name: String,
    val preview: String,
    /** When the newest chat was last touched; 0 for a character you have not talked to yet. */
    val timestamp: Long,
    /** How many chats there are with this character. */
    val chats: Int,
    val isLlm: Boolean
)

object RpChatSummaries {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Every character, one row each. Ones you are mid-story with come first, newest chat on top;
     * the rest follow by name, so the list is also where a new story starts. A character with
     * several chats shows the newest and counts the rest; plain-LLM chats share one row, and
     * chats whose character was deleted keep a row under the chat's own title so they aren't lost.
     */
    fun build(
        sessions: List<ChatSession>,
        characters: List<RpCharacter>,
        previews: Map<Long, String>,
        llmName: String,
        noPreview: String,
        startPrompt: String = noPreview,
        userName: String = ""
    ): List<RpChatSummary> {
        val byId = characters.associateBy { it.id }
        val groups = sessions
            .filter { it.chatMode() == ChatMode.RP }
            .groupBy { if (it.isLlm) LLM_KEY else it.characterId ?: ORPHAN_KEY - it.id }
        val talked = groups.values
            .map { group ->
                val newest = group.maxBy { it.timestamp }
                val character = if (newest.isLlm) null else newest.characterId?.let { byId[it] }
                RpChatSummary(
                    sessionId = newest.id,
                    character = character,
                    name = when {
                        newest.isLlm -> llmName
                        character != null -> character.name
                        else -> newest.title
                    },
                    preview = previews[newest.id].orEmpty().ifBlank { noPreview },
                    timestamp = newest.timestamp,
                    chats = group.size,
                    isLlm = newest.isLlm
                )
            }
            .sortedByDescending { it.timestamp }
        val met = talked.mapNotNull { it.character?.id }.toSet()
        val fresh = characters
            .filter { it.id !in met }
            .sortedBy { it.name.lowercase() }
            .map { c ->
                RpChatSummary(
                    sessionId = null,
                    character = c,
                    name = c.name,
                    preview = tagline(c, userName).ifBlank { startPrompt },
                    timestamp = 0L,
                    chats = 0,
                    isLlm = false
                )
            }
        return talked + fresh
    }

    /**
     * The first line of what the card says about itself, for a character you have not talked to.
     * {{char}} and {{user}} are the names, so the list does not show the placeholders.
     */
    fun tagline(c: RpCharacter, userName: String = ""): String {
        val raw = listOf(c.personality, c.scenario, c.greeting)
            .firstNotNullOfOrNull { text -> text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } }
            .orEmpty()
        val who = c.name.ifBlank { "GradatiON" }
        val named = RpPromptEngine.expandMacros(raw, who, userName.ifBlank { "you" })
        return foldMarkdown(named).take(140)
    }

    /**
     * The Lore tile lights only when a book would actually be used. A pin that was deleted
     * still counts when the active book is the fallback.
     */
    fun loreTileOn(loreEnabled: Boolean, pinnedBookExists: Boolean, activeBookExists: Boolean): Boolean =
        loreEnabled && (pinnedBookExists || activeBookExists)

    /**
     * A stored message as one line of plain text: markdown marks and line breaks folded away.
     * An underscore inside a word stays, so `snake_case` is still that word.
     */
    fun previewOf(storedContent: String): String {
        val element = parsed(storedContent)
        val text = if (element != null) MessageContent.text(element) else storedContent
        return foldMarkdown(text)
    }

    /**
     * The line under a character-list or character-History row.
     * A photo with no caption is [photoLabel], not an empty line that reads as no messages.
     * "You:" applies to words the user wrote, not to that photo label.
     */
    fun rowLine(role: String?, storedContent: String, youLabel: (String) -> String, photoLabel: String): String {
        val text = previewOf(storedContent)
        if (text.isNotBlank()) return if (role == "user") youLabel(text) else text
        val element = parsed(storedContent) ?: return ""
        return if (photoLabel.isNotBlank() && MessageContent.hasImage(element)) photoLabel else ""
    }

    private fun parsed(storedContent: String) =
        try {
            json.parseToJsonElement(storedContent)
        } catch (_: Exception) {
            null
        }

    /**
     * Markdown marks come off. An underscore between letters or digits stays: stripping every
     * `_` turned `snake_case` into `snakecase` on the character list.
     */
    private fun foldMarkdown(text: String): String =
        WHITESPACE.replace(
            MD_EDGE_UNDERSCORE.replace(MD_STARS.replace(text, ""), "").replace(MD_MARKS, ""),
            " ",
        ).trim()

    private val MD_STARS = Regex("\\*+")
    private val MD_MARKS = Regex("[#>`~]")
    private val MD_EDGE_UNDERSCORE = Regex("(?<![A-Za-z0-9])_|_(?![A-Za-z0-9])")
    private val WHITESPACE = Regex("\\s+")

    private const val LLM_KEY = -1L
    private const val ORPHAN_KEY = -1000L
}
