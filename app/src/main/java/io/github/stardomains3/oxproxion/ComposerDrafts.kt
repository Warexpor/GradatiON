package io.github.stardomains3.oxproxion

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Unsent composer text for Chat, one entry per thread. A chat that has not been saved
 * yet uses [NEW]. Blank text drops the entry. The oldest entries fall off past [MAX_KEPT]
 * so a long history cannot grow the preference without limit.
 */
object ComposerDrafts {
    const val NEW = "new"
    const val MAX_KEPT = 40
    const val MAX_CHARS = 16_000

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Entry(val k: String, val t: String)

    fun key(sessionId: Long?): String = if (sessionId == null || sessionId <= 0L) NEW else sessionId.toString()

    fun text(store: Map<String, String>, sessionId: Long?): String = store[key(sessionId)].orEmpty()

    /** [text] for [sessionId], dropping a blank. The written key moves to the end (newest). */
    fun remember(store: Map<String, String>, sessionId: Long?, text: String): Map<String, String> {
        val id = key(sessionId)
        val kept = LinkedHashMap<String, String>(store.size + 1)
        store.forEach { (k, v) -> if (k != id) kept[k] = v }
        val body = text.take(MAX_CHARS)
        if (body.isNotBlank()) kept[id] = body
        while (kept.size > MAX_KEPT) {
            val drop = kept.keys.firstOrNull { it != id } ?: break
            kept.remove(drop)
        }
        return kept
    }

    /**
     * The unsaved chat just received [to]. [text] is whatever is in the field now
     * (empty after a send, or the next line if they already started typing).
     */
    fun rekey(store: Map<String, String>, from: Long?, to: Long?, text: String): Map<String, String> =
        remember(remember(store, from, ""), to, text)

    fun encode(store: Map<String, String>): String =
        json.encodeToString(ListSerializer(Entry.serializer()), store.map { (k, t) -> Entry(k, t) })

    fun decode(raw: String): LinkedHashMap<String, String> {
        if (raw.isBlank()) return linkedMapOf()
        return runCatching {
            json.decodeFromString(ListSerializer(Entry.serializer()), raw)
                .associateTo(linkedMapOf()) { it.k to it.t }
        }.getOrDefault(linkedMapOf())
    }
}
