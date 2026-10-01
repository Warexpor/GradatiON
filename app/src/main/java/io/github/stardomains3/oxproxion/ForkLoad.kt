package io.github.stardomains3.oxproxion

import android.util.Log
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Decodes a stored chat fork. A blob this version cannot read is left where it is:
 * deleting it used to throw away the only copy.
 */
internal object ForkLoad {
    private val json = Json { ignoreUnknownKeys = true }

    fun messages(raw: String?): List<FlexibleMessage>? {
        if (raw.isNullOrBlank()) return null
        return try {
            json.decodeFromString(ListSerializer(FlexibleMessage.serializer()), raw)
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable chat fork; leaving it in place (${e.javaClass.simpleName})")
            null
        }
    }

    private const val TAG = "ForkLoad"
}
