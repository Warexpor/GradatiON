package io.github.stardomains3.oxproxion

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ChatBackup(
    val sessions: List<ExportedChatSession>
)

@Serializable
data class ExportedChatSession(
    val title: String,
    val modelUsed: String,
    val messages: List<ExportedChatMessage>,
    val mode: String = ChatMode.ASK.storageValue,
    val characterId: Long? = null,
    val characterExportKey: String? = null,
    val isLlm: Boolean = false,
    /** When the chat was last saved. Null in a backup from before this field existed. */
    val timestamp: Long? = null,
    /** Fact notes for this chat. Null means the backup does not carry them. */
    val facts: String? = null,
    val pinned: Boolean = false,
    /**
     * The other branch of an edited chat, and the other versions of a roleplay reply.
     * Null means a backup from before these fields. The messages are the JSON already stored
     * for that chat, so a blob this version cannot read is still carried.
     */
    val forkIndex: Int? = null,
    val forkAnchor: Int? = null,
    val forkMessages: String? = null,
    val swipe: String? = null
)

@Serializable
data class ExportedChatMessage(
    val role: String,
    val content: String // The raw JSON content from the database
)

/**
 * Writes one chat, then the next, and one message at a time inside a chat.
 * Encoding the whole history (or one long chat) as a single string used to sit on top of the
 * messages already loaded, which was enough to kill the process.
 */
internal object ChatBackupWriter {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun writeSession(
        out: Appendable,
        session: ChatSession,
        characterExportKey: String?,
        facts: String?,
        pinned: Boolean,
        forkIndex: Int?,
        forkAnchor: Int?,
        forkMessages: String?,
        swipeJson: String?,
        emitMessages: suspend (emit: suspend (ChatMessage) -> Unit) -> Unit,
    ) {
        out.append("{\"title\":")
        out.append(json.encodeToString(session.title))
        out.append(",\"modelUsed\":")
        out.append(json.encodeToString(session.modelUsed))
        out.append(",\"messages\":[")
        var first = true
        emitMessages { message ->
            if (!first) out.append(',')
            first = false
            out.append(json.encodeToString(ExportedChatMessage(message.role, message.content)))
        }
        out.append("],\"mode\":")
        out.append(json.encodeToString(session.mode))
        session.characterId?.let {
            out.append(",\"characterId\":")
            out.append(it.toString())
        }
        if (!characterExportKey.isNullOrBlank()) {
            out.append(",\"characterExportKey\":")
            out.append(json.encodeToString(characterExportKey))
        }
        if (session.isLlm) out.append(",\"isLlm\":true")
        out.append(",\"timestamp\":")
        out.append(session.timestamp.toString())
        if (!facts.isNullOrBlank()) {
            out.append(",\"facts\":")
            out.append(json.encodeToString(facts))
        }
        if (pinned) out.append(",\"pinned\":true")
        if (forkIndex != null && forkIndex >= 0 && !forkMessages.isNullOrBlank()) {
            out.append(",\"forkIndex\":")
            out.append(forkIndex.toString())
            out.append(",\"forkAnchor\":")
            out.append((forkAnchor ?: -1).toString())
            out.append(",\"forkMessages\":")
            out.append(json.encodeToString(forkMessages))
        }
        if (!swipeJson.isNullOrBlank()) {
            out.append(",\"swipe\":")
            out.append(json.encodeToString(swipeJson))
        }
        out.append('}')
    }
}
