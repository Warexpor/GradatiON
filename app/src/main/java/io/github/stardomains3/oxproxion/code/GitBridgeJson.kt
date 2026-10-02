package io.github.stardomains3.oxproxion.code

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Parses `bridge/gitStatus` / `bridge/diff` JSON results. Pure and unit-tested so the
 * [BridgeBackend] wire path stays thin.
 *
 * Null or non-object [result] throws so Hub `runCatching` surfaces a failed UI.
 * `{}` and missing optional fields stay empty success.
 */
object GitBridgeJson {

    fun parseStatus(result: JsonElement?): GitStatusResult {
        val o = result as? JsonObject
            ?: throw IllegalStateException("bridge/gitStatus result is not an object")
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
        val o = result as? JsonObject
            ?: throw IllegalStateException("bridge/diff result is not an object")
        return GitDiffResult((o["unified"] as? JsonPrimitive)?.contentOrNull.orEmpty())
    }

    /**
     * Compact status letter for list rows. Porcelain is two columns (` M`, `R `);
     * a longer token such as `R100` is a similarity score, so the first letter is the status.
     */
    fun statusLetter(status: String): String {
        val trimmed = status.trim()
        if (trimmed == "??" || trimmed == "?") return "?"
        if (trimmed == "!!" || trimmed == "!") return "!"
        if (trimmed.length > 2 && trimmed[0].isLetter()) return trimmed[0].toString()
        val y = status.getOrNull(1)?.takeIf { it != ' ' }
        val x = status.getOrNull(0)?.takeIf { it != ' ' }
        return (y ?: x ?: '?').toString()
    }

    private fun intField(o: JsonObject, key: String): Int {
        val p = o[key] as? JsonPrimitive ?: return 0
        wholeNumberLong(p)?.let { n ->
            return n.toInt().takeIf { it.toLong() == n } ?: 0
        }
        return p.intOrNull ?: p.contentOrNull?.toIntOrNull() ?: 0
    }

    /** Integers stay long; a double like `2.0` and a string `"2.0"` still count. */
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
