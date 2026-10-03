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
 * A `sessionId`, `model`, `cwd`, `harness`, or `branch` written the same way still matches as `"5"`, not `"5.0"`.
 * `permissionMode` / `mode` uses the same aliases as a live `current_mode_update`
 * (`acceptEdits`, `bypassPermissions`, `agent`, `full_auto`, Codex `auto` /
 * `full-access` / `read-only`, OpenCode `build`), not only the exact pill ids.
 * A blank `permissionMode` falls through to `mode`. A mode this phone understands,
 * including Ask, sets [CodeSessionSummary.permissionModeSpecified] so merge does not
 * treat it as omitted. An unknown or missing mode stays Ask and unspecified.
 */
object ListSessionsJson {

    fun parse(result: JsonElement?, hostId: String): List<CodeSessionSummary> {
        val root = result as? JsonObject ?: return emptyList()
        val arr = root["sessions"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun modeText(k: String) = s(k)?.trim()?.ifEmpty { null }
            fun idString(k: String): String? {
                val p = o[k] as? JsonPrimitive ?: return null
                wholeNumberLong(p)?.let { return it.toString() }
                return p.contentOrNull?.takeIf { it.isNotEmpty() }
            }
            // Same aliases as current_mode_update. A blank permissionMode is not a mode,
            // so `mode` can still say plan. Unknown / omitted stays Ask and unspecified,
            // so merge can keep a local non-Ask pill. An explicit Ask (including
            // read-only and default) is specified, or a refresh puts Full auto back.
            val mappedMode = PermissionMode.fromAcpModeId(modeText("permissionMode") ?: modeText("mode"))
            CodeSessionSummary(
                // Whole-number doubles (5.0 / "5.0") still match live events as "5".
                id = idString("sessionId") ?: return@mapNotNull null,
                hostId = hostId,
                // Same whole-number coercion as listHarnesses ids.
                harness = HarnessKind.fromId(idString("harness")),
                // Same whole-number coercion as listWorkspaces / listHarnesses models.
                workspace = idString("cwd") ?: "",
                title = s("title") ?: "Session",
                createdAt = longField(o, "createdAt") ?: 0L,
                updatedAt = longField(o, "updatedAt") ?: 0L,
                permissionMode = mappedMode ?: PermissionMode.ASK,
                permissionModeSpecified = mappedMode != null,
                // Same whole-number coercion as listHarnesses models.
                model = idString("model"),
                preview = s("preview") ?: "",
                // Same whole-number coercion as gitStatus branch / browse names.
                branch = idString("branch"),
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
