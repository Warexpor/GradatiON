package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpReplyCleanerTest {

    @Test
    fun stripsThinkBlocks() {
        val cleaned = RpReplyCleaner.clean("<think>secret</think>Hello there")
        assertEquals("Hello there", cleaned)
    }

    @Test
    fun stripsInstructionLeaks() {
        val cleaned = RpReplyCleaner.clean("INSTRUCTIONS: never break\nShe smiles.")
        assertEquals("She smiles.", cleaned)
    }

    @Test
    fun keepsEmoji() {
        val cleaned = RpReplyCleaner.clean("Hi 😊 friend")
        assertTrue(cleaned.contains("😊"))
        assertFalse(cleaned.contains("<think>"))
    }
}
