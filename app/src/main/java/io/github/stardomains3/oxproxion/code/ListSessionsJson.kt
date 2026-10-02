package io.github.stardomains3.oxproxion.code

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Parses `bridge/listSessions` results. Pure and unit-tested so [BridgeBackend] stays thin.
 *
 * Null / non-object [result] yields an empty list (same as a missing `sessions` array).
 * Whole-number doubles (`42.0`) and digit strings still become longs so resume and sort stay honest.
 * A `sessionId` written the same way still matches live events as `"5"`, not `"5.0"`.
 */
object ListSessionsJson {

    fun parse(result: JsonElement?, hostId: String): List<CodeSessionSummary> {
        val root = result as? JsonObject ?: return emptyList()
        val arr = root["sessions"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun idString(k: String): String? {
                val p = o[k] as? JsonPrimitive ?: return null
                wholeNumberLong(p)?.let { return it.toString() }
                return p.contentOrNull?.takeIf { it.isNotEmpty() }
            }
            CodeSessionSummary(
                // Whole-number doubles (5.0 / "5.0") still match live events as "5".
                id = idString("sessionId") ?: return@mapNotNull null,
                hostId = hostId,
                harness = HarnessKind.fromId(s("harness")),
                workspace = s("cwd") ?: "",
                title = s("title") ?: "Session",
                createdAt = longField(o, "createdAt") ?: 0L,
                updatedAt = longField(o, "updatedAt") ?: 0L,
                permissionMode = PermissionMode.fromId(s("permissionMode") ?: s("mode")),
                model = s("model"),
                preview = s("preview") ?: "",
                branch = s("branch"),
                lastSeq = longField(o, "lastSeq"),
            )
        }
    }

    private fun longField(o: JsonObject, key: String): Long? {
        val p = o[key] as? JsonPrimitive ?: return null
        return wholeNumberLong(p)
    }

    /** Integers stay long; a double like `42.0` and a string `"42.0"` still match. */
    private fun wholeNumberLong(p: JsonPrimitive): Long? {
        p.longOrNull?.let { return it }
        p.doubleOrNull?.let { d ->
            if (d.isFinite() && d == kotlin.math.floor(d) &&
                d in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()
            ) return d.toLong()
        }
        val c = p.contentOrNull ?: return null
        c.toLongOrNull()?.let { return it }
        return c.toDoubleOrNull()?.takeIf {
            it.isFinite() && it == kotlin.math.floor(it) &&
                it in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()
        }?.toLong()
    }
}
