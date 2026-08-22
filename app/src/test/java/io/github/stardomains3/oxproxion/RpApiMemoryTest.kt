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
}
