package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpRewriteTest {
    @Test
    fun greetingOnlyIsRewrittenInPlace() {
        assertFalse(RpRewrite.streamsAsNewSwipe(position = 0, lastAssistantIndex = 0, lastUserIndex = -1))
    }

    @Test
    fun lastReplyAfterAUserTurnStreamsAsASwipe() {
        assertTrue(RpRewrite.streamsAsNewSwipe(position = 2, lastAssistantIndex = 2, lastUserIndex = 1))
    }

    @Test
    fun earlierReplyStaysInPlace() {
        assertFalse(RpRewrite.streamsAsNewSwipe(position = 0, lastAssistantIndex = 2, lastUserIndex = 1))
    }

    @Test
    fun loreFocusKeepsTheReplyAndDropsABlankTurn() {
        assertEquals(listOf("She waits.", "He answers."), RpRewrite.loreFocus(" He answers. ", " She waits. "))
        assertEquals(listOf("He answers."), RpRewrite.loreFocus("He answers.", "  "))
    }

    @Test
    fun snippetIsTheFirstLineWithoutMarks() {
        val reply = "\n*She waits.*\n\n\"You're late.\""
        assertEquals("She waits.", RpRewrite.snippet(reply))
        assertEquals("abcd…", RpRewrite.snippet("abcdef", limit = 5))
    }
}
