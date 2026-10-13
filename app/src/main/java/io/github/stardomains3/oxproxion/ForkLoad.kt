package io.github.stardomains3.oxproxion

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Decodes a stored chat fork. A blob this version cannot read is left where it is:
 * deleting it used to throw away the only copy.
 *
 * Two shapes are stored under the same key. The first versions kept one other branch as a
 * plain message list. A chat with more branches keeps all of them, oldest first, with the
 * one on screen as an empty list ([Branches]).
 */
internal object ForkLoad {
    private val json = Json { ignoreUnknownKeys = true }

    /** Every branch from the cut, oldest first. [active] is the one on screen; its slot is empty. */
    data class Branches(val tails: List<List<FlexibleMessage>>, val active: Int)

    @Serializable
    private data class Stored(val active: Int, val branches: List<List<FlexibleMessage>>)

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

    /** Both shapes. The old single branch comes back as the older of two, the newer on screen. */
    fun branches(raw: String?): Branches? {
        if (raw.isNullOrBlank()) return null
        if (raw.trimStart().startsWith("[")) {
            val single = messages(raw) ?: return null
            return Branches(listOf(single, emptyList()), active = 1)
        }
        return try {
            val stored = json.decodeFromString(Stored.serializer(), raw)
            stored.takeIf { s ->
                s.branches.size >= 2 && s.active in s.branches.indices &&
                    s.branches.withIndex().all { (i, t) -> i == s.active || t.isNotEmpty() }
            }?.let { Branches(it.branches, it.active) }
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable chat fork; leaving it in place (${e.javaClass.simpleName})")
            null
        }
    }

    /** Two branches with the newer on screen keep the old shape, so a backup stays readable everywhere. */
    fun encode(tails: List<List<FlexibleMessage>>, active: Int): String {
        if (tails.size == 2 && active == 1) {
            return json.encodeToString(ListSerializer(FlexibleMessage.serializer()), tails[0])
        }
        val slots = tails.mapIndexed { i, t -> if (i == active) emptyList() else t }
        return json.encodeToString(Stored.serializer(), Stored(active, slots))
    }

    private const val TAG = "ForkLoad"
}
