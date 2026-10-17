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
/** Roleplay: the Facts a parked branch had, and how many messages they covered. */
@Serializable
data class BranchFacts(val text: String, val run: Int = 0)

internal object ForkLoad {
    private val json = Json { ignoreUnknownKeys = true }

    /** Every branch from the cut, oldest first. [active] is the one on screen; its slot is empty. */
    data class Branches(
        val tails: List<List<FlexibleMessage>>,
        val active: Int,
        /** Roleplay: each branch's reply versions, by position. Empty maps for a plain chat. */
        val versions: List<Map<Int, RpVersions>> = List(tails.size) { emptyMap() },
        /** Roleplay: each parked branch's Facts. Null for the one on screen, and in a plain chat. */
        val facts: List<BranchFacts?> = List(tails.size) { null },
    )

    @Serializable
    private data class Stored(
        val active: Int,
        val branches: List<List<FlexibleMessage>>,
        val versions: List<Map<Int, RpVersions>> = emptyList(),
        val facts: List<BranchFacts?> = emptyList(),
    )

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
            }?.let { s ->
                val versions = s.versions.takeIf { it.size == s.branches.size }
                    ?: List(s.branches.size) { emptyMap() }
                val facts = s.facts.takeIf { it.size == s.branches.size } ?: List(s.branches.size) { null }
                Branches(s.branches, s.active, versions, facts)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable chat fork; leaving it in place (${e.javaClass.simpleName})")
            null
        }
    }

    /** Two branches with the newer on screen keep the old shape, so a backup stays readable everywhere. */
    fun encode(
        tails: List<List<FlexibleMessage>>,
        active: Int,
        versions: List<Map<Int, RpVersions>> = emptyList(),
        facts: List<BranchFacts?> = emptyList(),
    ): String {
        val kept = versions.takeIf { v -> v.size == tails.size && v.any { it.isNotEmpty() } }
            ?.mapIndexed { i, v -> if (i == active) emptyMap() else v }
            .orEmpty()
        val notes = facts.takeIf { f -> f.size == tails.size && f.any { it != null } }
            ?.mapIndexed { i, f -> if (i == active) null else f }
            .orEmpty()
        if (tails.size == 2 && active == 1 && kept.none { it.isNotEmpty() } && notes.none { it != null }) {
            return json.encodeToString(ListSerializer(FlexibleMessage.serializer()), tails[0])
        }
        val slots = tails.mapIndexed { i, t -> if (i == active) emptyList() else t }
        val stored = Stored(
            active,
            slots,
            kept.takeIf { v -> v.any { it.isNotEmpty() } }.orEmpty(),
            notes.takeIf { f -> f.any { it != null } }.orEmpty(),
        )
        return json.encodeToString(Stored.serializer(), stored)
    }

    private const val TAG = "ForkLoad"
}
