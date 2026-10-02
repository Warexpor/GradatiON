package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.TurnEndFormat
import io.github.stardomains3.oxproxion.code.TurnUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnEndFormatTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun reasonsWorthALine() {
        assertEquals(TurnEndFormat.Reason.STOPPED, TurnEndFormat.reason("cancelled"))
        assertEquals(TurnEndFormat.Reason.MAX_TOKENS, TurnEndFormat.reason("max_tokens"))
        assertEquals(TurnEndFormat.Reason.MAX_REQUESTS, TurnEndFormat.reason("max_turn_requests"))
        assertEquals(TurnEndFormat.Reason.REFUSAL, TurnEndFormat.reason("refusal"))
        assertEquals(TurnEndFormat.Reason.ERROR, TurnEndFormat.reason("error"))
        assertNull(TurnEndFormat.reason("end_turn"))
    }

    @Test fun compactCountsAndCost() {
        assertEquals("340", TurnEndFormat.compactCount(340))
        assertEquals("1.2k", TurnEndFormat.compactCount(1200))
        assertEquals("12k", TurnEndFormat.compactCount(12_000))
        assertEquals("1.5m", TurnEndFormat.compactCount(1_500_000))
        assertEquals("<$0.01", TurnEndFormat.costText(0.004))
        assertEquals("$0.02", TurnEndFormat.costText(0.02))
        assertEquals("$1.20", TurnEndFormat.costText(1.2))
    }

    @Test fun linePrefersSummaryThenUsage() {
        val pieces = TurnEndFormat.usagePieces(TurnUsage(1200, 340, 0.02))!!
        val usage = listOf("${pieces.input} in", "${pieces.output} out", pieces.cost!!)
        assertEquals(
            "2 files changed · 1.2k in · 340 out · $0.02",
            TurnEndFormat.line("2 files changed", "Stopped", usage),
        )
        assertEquals("Stopped", TurnEndFormat.line(null, "Stopped", emptyList()))
        assertEquals("", TurnEndFormat.line(null, null, emptyList()))
    }

    @Test fun parseUsageFromPromptResultOrMeta() {
        val top = json.parseToJsonElement(
            """{"stopReason":"end_turn","usage":{"input_tokens":10,"outputTokens":4,"costUsd":0.5}}"""
        ).jsonObject
        val parsed = TurnEndFormat.parseUsage(top)!!
        assertEquals(10L, parsed.inputTokens)
        assertEquals(4L, parsed.outputTokens)
        assertEquals(0.5, parsed.costUsd!!, 1e-9)

        val meta = json.parseToJsonElement(
            """{"stopReason":"end_turn","_meta":{"usage":{"promptTokens":8,"completionTokens":2},"totalCost":1.25}}"""
        ).jsonObject
        val fromMeta = TurnEndFormat.parseUsage(meta)!!
        assertEquals(8L, fromMeta.inputTokens)
        assertEquals(2L, fromMeta.outputTokens)
        assertEquals(1.25, fromMeta.costUsd!!, 1e-9)

        assertNull(TurnEndFormat.parseUsage(json.parseToJsonElement("""{"stopReason":"end_turn"}""").jsonObject))
    }

    @Test fun parseUsageAcceptsWholeNumberDoublesAsStrings() {
        val top = json.parseToJsonElement(
            """{"stopReason":"end_turn","usage":{"inputTokens":"1200.0","output_tokens":"340.0","costUsd":"0.02"}}"""
        ).jsonObject
        val parsed = TurnEndFormat.parseUsage(top)!!
        assertEquals(1200L, parsed.inputTokens)
        assertEquals(340L, parsed.outputTokens)
        assertEquals(0.02, parsed.costUsd!!, 1e-9)

        val numeric = json.parseToJsonElement(
            """{"stopReason":"end_turn","usage":{"inputTokens":1200.0,"outputTokens":340.0}}"""
        ).jsonObject
        val fromNum = TurnEndFormat.parseUsage(numeric)!!
        assertEquals(1200L, fromNum.inputTokens)
        assertEquals(340L, fromNum.outputTokens)
    }
}
