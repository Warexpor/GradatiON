package io.github.stardomains3.oxproxion

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanContextBudgetTest {

    private data class Msg(val role: String, val id: Int, val tokens: Int, val pinned: Boolean = false)

    private fun trim(msgs: List<Msg>, budget: Int, pin: Boolean = true): List<Int> =
        RpApiMemory.trimToTokenBudget(
            nonSystem = msgs,
            tokenBudget = budget,
            tokensOf = { it.tokens },
            pinCharacterGreeting = pin,
            isAssistant = { it.role == "assistant" },
            isPinned = { it.pinned },
        ).map { it.id }

    @Test fun estimateIsCharsOverThreePointFive() {
        assertEquals(0, LanContextBudget.estimateTokens(""))
        assertEquals(1, LanContextBudget.estimateTokens("abc"))
        assertEquals(2, LanContextBudget.estimateTokens("abcd"))
        assertEquals(100, LanContextBudget.estimateTokens("x".repeat(350)))
    }

    @Test fun contextPrefersUserThenDetectedThenDefault() {
        assertEquals(4096, LanContextBudget.effectiveContext(4096, 32768))
        assertEquals(32768, LanContextBudget.effectiveContext(null, 32768))
        assertEquals(32768, LanContextBudget.effectiveContext(0, 32768))
        assertEquals(8192, LanContextBudget.effectiveContext(null, null))
        // "Asked, the server wouldn't say" is stored as 0.
        assertEquals(8192, LanContextBudget.effectiveContext(null, 0))
    }

    @Test fun replyIsCappedToAThirdOfTheWindow() {
        assertEquals(2730, LanContextBudget.cappedMaxTokens(12_000, 8192))
        assertEquals(1000, LanContextBudget.cappedMaxTokens(1000, 8192))
        assertEquals(64, LanContextBudget.cappedMaxTokens(12_000, 90))
    }

    @Test fun historyBudgetLeavesRoomForSystemAndReply() {
        // 8192 - 2730 reply - 1000 system - 256 slack
        assertEquals(4206, LanContextBudget.historyBudget(8192, 2730, 1000))
        assertEquals(0, LanContextBudget.historyBudget(2048, 1000, 3000))
    }

    @Test fun messageTokensCountTextAndPictures() {
        val text = FlexibleMessage(role = "user", content = JsonPrimitive("x".repeat(350)))
        assertEquals(104, LanContextBudget.tokensOf(text))
        val withImage = FlexibleMessage(
            role = "user",
            content = kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("type", JsonPrimitive("text")); put("text", JsonPrimitive("x".repeat(35)))
                })
                add(kotlinx.serialization.json.buildJsonObject {
                    put("type", JsonPrimitive("image_url"))
                    put("image_url", JsonPrimitive("data:image/png;base64," + "A".repeat(200_000)))
                })
            }
        )
        assertEquals(10 + LanContextBudget.IMAGE_TOKENS + 4, LanContextBudget.tokensOf(withImage))
    }

    @Test fun detectedWindowsAreRememberedPerServerAndModel() {
        LanContextBudget.forgetDetected()
        assertNull(LanContextBudget.detectedFor("http://a:1", "m"))
        LanContextBudget.rememberDetected("http://a:1", "m", 16384)
        assertEquals(16384, LanContextBudget.detectedFor("http://a:1", "m"))
        assertNull(LanContextBudget.detectedFor("http://b:1", "m"))
        LanContextBudget.forgetDetected()
        assertNull(LanContextBudget.detectedFor("http://a:1", "m"))
    }

    // --- token-budget trimming ---

    private val chat = listOf(
        Msg("assistant", 1, 100), // greeting
        Msg("user", 2, 100),
        Msg("assistant", 3, 100),
        Msg("user", 4, 100),
        Msg("assistant", 5, 100),
        Msg("user", 6, 100),
    )

    @Test fun everythingFittingIsKept() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6), trim(chat, 600))
    }

    @Test fun oldestHistoryGoesFirstAndGreetingStays() {
        assertEquals(listOf(1, 5, 6), trim(chat, 300))
        assertEquals(listOf(1, 4, 5, 6), trim(chat, 400))
    }

    @Test fun withoutGreetingPinTheNewestRunIsKept() {
        assertEquals(listOf(4, 5, 6), trim(chat, 300, pin = false))
    }

    @Test fun latestTurnStaysEvenWhenItAloneIsTooBig() {
        val big = listOf(Msg("assistant", 1, 50), Msg("user", 2, 5000))
        assertEquals(listOf(2), trim(big, 1000))
    }

    @Test fun greetingIsDroppedWhenItWouldNotFit() {
        assertEquals(listOf(6), trim(chat, 150))
    }

    @Test fun pinnedLinesStayAndCountAgainstTheBudget() {
        val msgs = listOf(
            Msg("assistant", 1, 100),
            Msg("user", 2, 100, pinned = true),
            Msg("assistant", 3, 100),
            Msg("user", 4, 100),
        )
        assertEquals(listOf(1, 2, 4), trim(msgs, 300))
        assertEquals(listOf(2, 4), trim(msgs, 250, pin = false))
    }

    @Test fun keptHistoryStaysContiguousAtTheNewEnd() {
        val msgs = listOf(
            Msg("user", 1, 100), Msg("assistant", 2, 900), Msg("user", 3, 100), Msg("assistant", 4, 100),
        )
        // The big reply doesn't fit, so the older short line must not sneak past it.
        assertEquals(listOf(3, 4), trim(msgs, 400, pin = false))
    }

    @Test fun emptyInputIsEmpty() {
        assertTrue(trim(emptyList(), 100).isEmpty())
    }
}
