package io.github.stardomains3.oxproxion.code

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Parses `bridge/gitStatus` / `bridge/diff` JSON results. Pure and unit-tested so the
 * [BridgeBackend] wire path stays thin.
 */
object GitBridgeJson {

    fun parseStatus(result: JsonElement?): GitStatusResult {
        val o = result as? JsonObject ?: return emptyStatus()
        val files = (o["files"] as? JsonArray)?.mapNotNull { e ->
            val f = e as? JsonObject ?: return@mapNotNull null
            val path = (f["path"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val status = (f["status"] as? JsonPrimitive)?.contentOrNull ?: "  "
            GitFileStatus(path = path, status = status)
        }.orEmpty()
        return GitStatusResult(
            branch = (o["branch"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            ahead = intField(o, "ahead"),
            behind = intField(o, "behind"),
            files = files
        )
    }

    fun parseDiff(result: JsonElement?): GitDiffResult {
        val o = result as? JsonObject ?: return GitDiffResult("")
        return GitDiffResult((o["unified"] as? JsonPrimitive)?.contentOrNull.orEmpty())
    }

    /** Compact status letter for list rows (porcelain XY → one glyph). */
    fun statusLetter(status: String): String {
        val trimmed = status.trim()
        if (trimmed == "??" || trimmed == "?") return "?"
        val y = status.getOrNull(1)?.takeIf { it != ' ' }
        val x = status.getOrNull(0)?.takeIf { it != ' ' }
        return (y ?: x ?: '?').toString()
    }

    private fun intField(o: JsonObject, key: String): Int {
        val p = o[key] as? JsonPrimitive ?: return 0
        return p.intOrNull ?: p.longOrNull?.toInt() ?: p.contentOrNull?.toIntOrNull() ?: 0
    }

    private fun emptyStatus() = GitStatusResult("", 0, 0, emptyList())
}
