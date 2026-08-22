package io.github.stardomains3.oxproxion

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RpSwipeState(
    val alts: List<String> = emptyList(),
    val index: Int = 0
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
