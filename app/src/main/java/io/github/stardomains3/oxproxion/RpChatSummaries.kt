package io.github.stardomains3.oxproxion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** One row of the Roleplay chats list: a character and the chat you would pick up with them. */
data class RpChatSummary(
    /** The chat the row opens: the newest one with this character. */
    val sessionId: Long,
    val character: RpCharacter?,
    val name: String,
    val preview: String,
    val timestamp: Long,
    /** How many chats there are with this character. */
    val chats: Int,
    val isLlm: Boolean
)

object RpChatSummaries {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * One row per character, newest first. A character with several chats shows the newest and
     * counts the rest; plain-LLM chats share one row, and chats whose character was deleted keep
     * a row under the chat's own title so they aren't lost.
     */
    fun build(
        sessions: List<ChatSession>,
        characters: List<RpCharacter>,
        previews: Map<Long, String>,
        llmName: String,
        noPreview: String
    ): List<RpChatSummary> {
        val byId = characters.associateBy { it.id }
        return sessions
            .filter { it.chatMode() == ChatMode.RP }
            .groupBy { if (it.isLlm) LLM_KEY else it.characterId ?: ORPHAN_KEY - it.id }
            .values
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
    }

    /** A preview split into plain text and the spans that were *actions* in the reply. */
    data class StyledPreview(val text: String, val actions: List<IntRange>)

    private val actionSpan = Regex("""\*([^*]+)\*""")

    /**
     * [previewOf] keeps single asterisks around actions. Without them the stripped action runs
     * straight into the dialogue ("sighs and grabs a wrench Fine."), so the row italicises them.
     */
    fun styledPreview(preview: String): StyledPreview {
        val out = StringBuilder()
        val actions = ArrayList<IntRange>()
        var last = 0
        for (m in actionSpan.findAll(preview)) {
            out.append(preview.substring(last, m.range.first).replace("*", ""))
            val start = out.length
            out.append(m.groupValues[1].trim())
            if (out.length > start) actions += start until out.length
            last = m.range.last + 1
        }
        out.append(preview.substring(last).replace("*", ""))
        return StyledPreview(out.toString(), actions)
    }

    /**
     * A stored message as one line of text: markdown marks and line breaks folded away. An
     * *action* keeps its single asterisks so [styledPreview] can set it apart from the dialogue.
     */
    fun previewOf(storedContent: String): String {
        val element = try { json.parseToJsonElement(storedContent) } catch (_: Exception) { JsonPrimitive(storedContent) }
        val text = when (element) {
            is JsonPrimitive -> element.contentOrNull.orEmpty()
            is JsonArray -> element.firstNotNullOfOrNull { item ->
                (item as? JsonObject)?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                    ?.get("text")?.jsonPrimitive?.contentOrNull
            }.orEmpty()
            else -> ""
        }
        return text.replace("**", "").replace(Regex("[_#>`~]"), "").replace(Regex("\\s+"), " ").trim()
    }

    private const val LLM_KEY = -1L
    private const val ORPHAN_KEY = -1000L
}
