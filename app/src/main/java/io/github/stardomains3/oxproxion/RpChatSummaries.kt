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
        return named.replace(Regex("[*_#>`~]"), "").take(140)
    }

    /** A stored message as one line of plain text: markdown marks and line breaks folded away. */
    fun previewOf(storedContent: String): String {
        val text = try {
            MessageContent.text(json.parseToJsonElement(storedContent))
        } catch (_: Exception) {
            storedContent
        }
        return text.replace(Regex("[*_#>`~]"), "").replace(Regex("\\s+"), " ").trim()
    }

    private const val LLM_KEY = -1L
    private const val ORPHAN_KEY = -1000L
}
