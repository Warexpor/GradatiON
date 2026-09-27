package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RpAutoMemoryTest {

    @Test fun waitsUntilTheWindowIsNearlyFull() {
        assertFalse(RpAutoMemory.shouldUpdate(messageCount = 10, budget = 30, lastRunAt = 0))
        assertTrue(RpAutoMemory.shouldUpdate(messageCount = 24, budget = 30, lastRunAt = 0))
    }

    @Test fun allMessagesActsLikeASixtyMessageWindow() {
        assertFalse(RpAutoMemory.shouldUpdate(messageCount = 40, budget = Int.MAX_VALUE, lastRunAt = 0))
        assertTrue(RpAutoMemory.shouldUpdate(messageCount = 54, budget = Int.MAX_VALUE, lastRunAt = 0))
    }

    @Test fun thenRefreshesEverySixMessages() {
        assertFalse(RpAutoMemory.shouldUpdate(messageCount = 28, budget = 30, lastRunAt = 24))
        assertTrue(RpAutoMemory.shouldUpdate(messageCount = 30, budget = 30, lastRunAt = 24))
        assertTrue(RpAutoMemory.shouldUpdate(messageCount = 80, budget = 30, lastRunAt = 60))
    }

    @Test fun transcriptKeepsNewestWithinLimit() {
        val turns = (1..400).map { (if (it % 2 == 0) "assistant" else "user") to "line $it ".repeat(20) }
        val t = RpAutoMemory.transcript(turns, "Mira", "Sam")
        assertTrue(t.length <= RpAutoMemory.TRANSCRIPT_CHARS + 200)
        assertTrue(t.endsWith("line 400 ".repeat(20).trim()))
        assertTrue(t.lines().last().startsWith("Mira: "))
    }

    @Test fun promptCarriesMemoryAndNames() {
        val p = RpAutoMemory.prompt("Mira", "Sam", "- Owes Sam a favor", "Sam: hi")
        assertTrue(p.contains("- Owes Sam a favor"))
        assertTrue(p.contains("between Mira and Sam"))
        assertTrue(p.contains("Sam: hi"))
    }

    @Test fun cleanNormalizesAndRejectsJunk() {
        assertNull(RpAutoMemory.clean(null))
        assertNull(RpAutoMemory.clean("  "))
        assertNull(RpAutoMemory.clean("Error: API request failed with status 401"))
        assertEquals(
            "- Mira owes Sam a favor\n- They leave at first light",
            RpAutoMemory.clean("```\n<think>hmm</think>\n* Mira owes Sam a favor\n\n• They leave at first light\n```")
        )
        val long = (1..200).joinToString("\n") { "- fact number $it" }
        assertTrue(RpAutoMemory.clean(long)!!.length <= RpAutoMemory.MEMORY_CHARS)
    }
}
