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

    /**
     * Live composer wins when it has anything staged. When the open thread's live stage
     * is empty (parked for Code), History still needs the parked Photo / Audio / files.
     */
    data class Presence(val hasPhoto: Boolean, val hasAudio: Boolean, val fileCount: Int) {
        val isEmpty: Boolean get() = !hasPhoto && !hasAudio && fileCount <= 0
    }

    fun presence(
        livePhoto: Boolean,
        liveAudio: Boolean,
        liveFileCount: Int,
        parked: Entry,
    ): Presence {
        if (livePhoto || liveAudio || liveFileCount > 0) {
            return Presence(livePhoto, liveAudio, liveFileCount)
        }
        return Presence(
            hasPhoto = parked.imageBytes != null || !parked.imageUri.isNullOrBlank(),
            hasAudio = parked.audioBytes != null,
            fileCount = parked.files.size,
        )
    }

    /**
     * What to write into the park map when entering Code (or soft-parking). Null means keep
     * the map as-is: remembering an empty live stage would drop a chip already parked.
     */
    fun liveToPark(live: Entry): Entry? = if (live.isEmpty) null else live

    /** Merge a late photo onto a parked entry without dropping audio/files already there. */
    fun withPhoto(base: Entry, bytes: ByteArray?, mime: String?, uri: String?): Entry =
        base.copy(imageBytes = bytes, imageMime = mime, imageUri = uri)

    /**
     * Merge a late audio clip onto a parked entry. Clears the picture fields the same way
     * the live composer does when audio replaces a staged photo.
     */
    fun withAudio(base: Entry, bytes: ByteArray?, format: String?): Entry =
        base.copy(
            audioBytes = bytes,
            audioFormat = format,
            imageBytes = null,
            imageMime = null,
            imageUri = null,
        )

    /** Append a late file onto a parked entry. */
    fun withFile(base: Entry, file: FilePart): Entry =
        base.copy(files = base.files + file)

}
