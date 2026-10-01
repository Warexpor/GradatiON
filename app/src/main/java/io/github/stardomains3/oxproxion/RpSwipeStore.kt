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
