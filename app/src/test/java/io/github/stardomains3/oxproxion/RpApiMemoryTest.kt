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
}
