package io.github.stardomains3.oxproxion

/**
 * Staged composer attachments for Chat, one entry per thread. Text drafts already park in
 * [ComposerDrafts]; photos and files used to be cleared (and deleted) when leaving a chat, so
 * coming back kept the words but lost the picture. The file on disk stays until the entry is
 * dropped for good (send, discard, or the user clears it).
 */
object ComposerStaged {

    data class FilePart(val fileName: String, val content: String, val size: Long)

    data class Entry(
        val imageBytes: ByteArray? = null,
        val imageMime: String? = null,
        val imageUri: String? = null,
        val audioBytes: ByteArray? = null,
        val audioFormat: String? = null,
        val files: List<FilePart> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = imageBytes == null && imageUri.isNullOrBlank() &&
                audioBytes == null && files.isEmpty()
    }

    fun key(sessionId: Long?): String = ComposerDrafts.key(sessionId)

    fun get(store: Map<String, Entry>, sessionId: Long?): Entry =
        store[key(sessionId)] ?: Entry()

    /** Park [entry] for [sessionId]. An empty entry drops the slot. Newest key stays; oldest fall off. */
    fun remember(store: Map<String, Entry>, sessionId: Long?, entry: Entry): Map<String, Entry> {
        val id = key(sessionId)
        val kept = LinkedHashMap<String, Entry>(store.size + 1)
        store.forEach { (k, v) -> if (k != id) kept[k] = v }
        if (!entry.isEmpty) kept[id] = entry
        while (kept.size > ComposerDrafts.MAX_KEPT) {
            val drop = kept.keys.firstOrNull { it != id } ?: break
            kept.remove(drop)
        }
        return kept
    }

    fun rekey(store: Map<String, Entry>, from: Long?, to: Long?, entry: Entry): Map<String, Entry> =
        remember(remember(store, from, Entry()), to, entry)

    fun drop(store: Map<String, Entry>, sessionId: Long): Map<String, Entry> {
        val id = key(sessionId)
        if (id == ComposerDrafts.NEW || id !in store) return store
        val kept = LinkedHashMap<String, Entry>(store.size)
        store.forEach { (k, v) -> if (k != id) kept[k] = v }
        return kept
    }

    /** Entries [remember] dropped when the store grew past [ComposerDrafts.MAX_KEPT]. */
    fun evicted(before: Map<String, Entry>, after: Map<String, Entry>): List<Entry> =
        before.mapNotNull { (k, v) -> if (k in after) null else v }
}
