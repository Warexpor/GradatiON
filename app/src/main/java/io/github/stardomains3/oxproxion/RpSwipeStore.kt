package io.github.stardomains3.oxproxion

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RpSwipeState(
    val alts: List<String> = emptyList(),
    val index: Int = 0,
    /**
     * The file behind each version, once this chat has started remembering them.
     * Empty means a save from before that, and swiping still keeps the picture already
     * on the reply. A blank entry is a version that has no picture of its own.
     * The JPEG itself stays in the file: this list is only the link.
     */
    val pictureUris: List<String> = emptyList(),
    /**
     * Versions of earlier replies, by transcript position. The story moved on past them, so
     * swiping one swaps that bubble in place and leaves what came after it as it was.
     */
    val earlier: Map<Int, RpVersions> = emptyMap(),
) {
    /** Anything worth saving: the newest reply's versions, or an earlier reply's. */
    val isEmpty: Boolean get() = alts.isEmpty() && earlier.isEmpty()
}

/** The versions kept for one earlier reply. [pictureUris] follows the rules of [RpSwipeState]. */
@Serializable
data class RpVersions(
    val alts: List<String>,
    val index: Int,
    val pictureUris: List<String> = emptyList(),
)

class RpSwipeStore(private val prefs: SharedPreferencesHelper) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(sessionId: Long): RpSwipeState? {
        val raw = prefs.getRpSwipeJson(sessionId) ?: return null
        return try {
            json.decodeFromString<RpSwipeState>(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun save(sessionId: Long, state: RpSwipeState) {
        prefs.saveRpSwipeJson(sessionId, json.encodeToString(state))
    }

    fun clear(sessionId: Long) {
        prefs.clearRpSwipeJson(sessionId)
    }
}
