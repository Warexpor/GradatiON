package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Test

class RpApiMemoryTest {

    private data class Msg(val role: String, val id: Int)

    private fun trim(msgs: List<Msg>, budget: Int, pin: Boolean = true): List<Int> =
        RpApiMemory.trimNonSystem(
            nonSystem = msgs,
            budget = budget,
            pinCharacterGreeting = pin,
            isAssistant = { it.role == "assistant" }
        ).map { it.id }

    @Test
    fun tinyBudgetKeepsLatestUserNotGreetingOnly() {
        val msgs = listOf(
            Msg("assistant", 1), // greeting
            Msg("user", 2),
            Msg("assistant", 3),
            Msg("user", 4)
        )
        assertEquals(listOf(4), trim(msgs, budget = 1))
    }

    @Test
    fun budgetTwoKeepsGreetingAndLatest() {
        val msgs = listOf(
            Msg("assistant", 1),
            Msg("user", 2),
            Msg("assistant", 3),
            Msg("user", 4)
        )
        assertEquals(listOf(1, 4), trim(msgs, budget = 2))
    }

    @Test
    fun llmStyleDoesNotPinGreeting() {
        val msgs = listOf(
            Msg("assistant", 1),
            Msg("user", 2),
            Msg("assistant", 3),
            Msg("user", 4)
        )
        assertEquals(listOf(3, 4), trim(msgs, budget = 2, pin = false))
    }

    @Test
    fun withinBudgetUnchanged() {
        val msgs = listOf(Msg("assistant", 1), Msg("user", 2))
        assertEquals(listOf(1, 2), trim(msgs, budget = 5))
    }

    @Test
    fun pinTailPutsTheReplyAndThePhotoBack() {
        val greeting = Msg("assistant", 1)
        val photo = Msg("user", 2)
        val reply = Msg("assistant", 3)
        val kept = listOf(greeting, reply) // a budget of 2 pinned the greeting and dropped the photo
        val pinned = RpApiMemory.pinTail(kept, listOf(photo, reply)) { a, b -> a == b }
        assertEquals(listOf(1, 2, 3), pinned.map { it.id })
    }

    @Test
    fun definitionStaysWholeUntilHistoryIsCut() {
        assertEquals(null, RpApiMemory.definitionCap(messageCount = 4, historyBudget = 10))
        assertEquals(RpApiMemory.DEFINITION_HEAD, RpApiMemory.definitionCap(messageCount = 12, historyBudget = 10))
        assertEquals(null, RpApiMemory.definitionCap(messageCount = 80, historyBudget = 10_000))
    }

    @Test
    fun longRepliesFillTheWindowBeforeTheCount() {
        // Thirty 3,000-character scenes do not fit 40k characters; the newest thirteen do.
        assertEquals(13, RpApiMemory.fitCount(List(30) { 3_000 }, 40_000))
        assertEquals(30, RpApiMemory.fitCount(List(30) { 100 }, 40_000))
        // The newest turn is always sent, even when it alone is over.
        assertEquals(1, RpApiMemory.fitCount(listOf(10, 90_000), 40_000))
        assertEquals(0, RpApiMemory.fitCount(emptyList(), 40_000))
    }

    @Test
    fun windowComesFromTheModelsContext() {
        val reply = RpApiMemory.REPLY_RESERVE_TOKENS
        val perToken = RpApiMemory.CHARS_PER_TOKEN
        assertEquals((200_000 - reply) * perToken - 10_000, RpApiMemory.historyChars(200_000, 10_000))
        // Unknown size falls back to the default window.
        assertEquals(
            RpApiMemory.historyChars(RpApiMemory.DEFAULT_CONTEXT_TOKENS, 10_000),
            RpApiMemory.historyChars(null, 10_000)
        )
        assertEquals(RpApiMemory.historyChars(null, 10_000), RpApiMemory.historyChars(0, 10_000))
        // A tiny window still keeps a few turns.
        assertEquals(8_000, RpApiMemory.historyChars(4_096, 50_000))
    }

    @Test
    fun styleReminderSitsBeforeTheNewestUserTurnOfALongChat() {
        val short = listOf("system", "assistant", "user", "assistant", "user")
        assertEquals(-1, RpApiMemory.reminderIndex(short))
        val long = listOf("system", "assistant", "user", "assistant", "user", "assistant", "user")
        assertEquals(6, RpApiMemory.reminderIndex(long))
        // A rewrite ends on its own note after the old reply; the reminder goes before that note.
        val rewrite = long + listOf("assistant", "user")
        assertEquals(8, RpApiMemory.reminderIndex(rewrite))
        assertEquals(-1, RpApiMemory.reminderIndex(List(8) { "assistant" }))
    }
}
