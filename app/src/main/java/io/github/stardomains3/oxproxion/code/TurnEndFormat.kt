package io.github.stardomains3.oxproxion.code

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * How a finished turn is labelled. Pure: the transcript row supplies localized reason text
 * and joins [usagePieces] itself.
 */
object TurnEndFormat {

    enum class Reason { STOPPED, MAX_TOKENS, MAX_REQUESTS, REFUSAL, ERROR }

    /** ACP `stopReason` values worth a line of their own. `end_turn` and anything else stay quiet. */
    fun reason(stopReason: String): Reason? = when (stopReason) {
        "cancelled" -> Reason.STOPPED
        "max_tokens" -> Reason.MAX_TOKENS
        "max_turn_requests", "max_requests" -> Reason.MAX_REQUESTS
        "refusal" -> Reason.REFUSAL
        "error" -> Reason.ERROR
        else -> null
    }

    /** Compact counts for the turn line. Null when the bridge reported nothing usable. */
    fun usagePieces(usage: TurnUsage?): UsagePieces? {
        if (usage == null) return null
        val input = usage.inputTokens?.let(::compactCount)
        val output = usage.outputTokens?.let(::compactCount)
        val cost = usage.costUsd?.let(::costText)
        if (input == null && output == null && cost == null) return null
        return UsagePieces(input, output, cost)
    }

    /**
     * One turn-end line. [summary] (demo / bridge prose) wins over [reason]; usage fragments
     * follow either. Empty when there is nothing to say.
     */
    fun line(summary: String?, reason: String?, usage: List<String>): String =
        buildList {
            val head = summary?.takeIf { it.isNotEmpty() } ?: reason?.takeIf { it.isNotEmpty() }
            if (head != null) add(head)
            addAll(usage.filter { it.isNotEmpty() })
        }.joinToString(" · ")

    /**
     * Usage from a `session/prompt` result. Accepts a top-level `usage` object or `_meta.usage`,
     * and a cost on either that object or `_meta` itself.
     */
    fun parseUsage(result: JsonObject?): TurnUsage? {
        if (result == null) return null
        val meta = result["_meta"] as? JsonObject
        val usage = result["usage"] as? JsonObject ?: meta?.get("usage") as? JsonObject
        val input = longField(usage, "inputTokens", "input_tokens", "promptTokens", "prompt_tokens")
        val output = longField(usage, "outputTokens", "output_tokens", "completionTokens", "completion_tokens")
        val cost = doubleField(usage, "costUsd", "cost", "totalCost", "total_cost")
            ?: doubleField(meta, "costUsd", "cost", "totalCost", "total_cost")
        if (input == null && output == null && cost == null) return null
        return TurnUsage(input, output, cost)
    }

    /** 1200 → "1.2k", 12000 → "12k", 1_500_000 → "1.5m". */
    fun compactCount(n: Long): String {
        val v = if (n < 0) 0L else n
        return when {
            v < 1_000L -> v.toString()
            v < 10_000L -> tenths(v, 100L, "k")
            v < 1_000_000L -> "${v / 1_000L}k"
            v < 10_000_000L -> tenths(v, 100_000L, "m")
            else -> "${v / 1_000_000L}m"
        }
    }

    /** Dollars, two places. Tiny positive costs stay visible as "<$0.01". */
    fun costText(usd: Double): String {
        if (usd.isNaN() || usd <= 0.0) return "$0"
        if (usd < 0.01) return "<$0.01"
        val cents = kotlin.math.round(usd * 100.0).toLong().coerceAtMost(99_999_999L)
        val whole = cents / 100
        val frac = (cents % 100).toString().padStart(2, '0')
        return "$$whole.$frac"
    }

    private fun tenths(n: Long, divisor: Long, suffix: String): String {
        val tenths = n / divisor
        val whole = tenths / 10
        val frac = tenths % 10
        return if (frac == 0L) "$whole$suffix" else "$whole.$frac$suffix"
    }

    private fun longField(obj: JsonObject?, vararg keys: String): Long? {
        if (obj == null) return null
        for (k in keys) {
            val p = obj[k] as? JsonPrimitive ?: continue
            p.longOrNull?.let { return it }
            p.contentOrNull?.toLongOrNull()?.let { return it }
            p.doubleOrNull?.let { return it.toLong() }
        }
        return null
    }

    private fun doubleField(obj: JsonObject?, vararg keys: String): Double? {
        if (obj == null) return null
        for (k in keys) {
            val p = obj[k] as? JsonPrimitive ?: continue
            p.doubleOrNull?.let { return it }
            p.contentOrNull?.toDoubleOrNull()?.let { return it }
        }
        return null
    }

    data class UsagePieces(val input: String?, val output: String?, val cost: String?)
}
