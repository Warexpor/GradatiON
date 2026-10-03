package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Speak/Copy line on the answer shade. */
class AnswerShadeTextTest {

    @Test
    fun toolHandoffDoesNotReplaceFinishedAnswer() {
        assertNull(AnswerShadeText.lineForShade("I will check", handedToTools = true, isError = false))
    }

    @Test
    fun errorDoesNotReplacePreviousAnswer() {
        assertNull(AnswerShadeText.lineForShade("Error!", handedToTools = false, isError = true))
        assertNull(AnswerShadeText.lineForShade("**Error:** boom", handedToTools = false, isError = true))
    }

    @Test
    fun finishedAnswerIsTheSpeakLine() {
        assertEquals(
            "The file is updated.",
            AnswerShadeText.lineForShade("The file is updated.", handedToTools = false, isError = false),
        )
    }

    @Test
    fun longSpeakLineDoesNotSplitAnEmoji() {
        val emoji = "\uD83D\uDE00"
        val head = "a".repeat(AnswerShadeText.SPEAK_LIMIT - 1)
        val line = AnswerShadeText.lineForShade(head + emoji + "tail", handedToTools = false, isError = false)
        assertEquals("$head...", line)
        assertFalse(line!!.removeSuffix("...").last().isHighSurrogate())
    }

    @Test
    fun emojiThatFitsBeforeTheLimitStaysWhole() {
        val emoji = "\uD83D\uDE00"
        val head = "a".repeat(AnswerShadeText.SPEAK_LIMIT - 2)
        val text = head + emoji + "tail"
        val line = AnswerShadeText.lineForShade(text, handedToTools = false, isError = false)
        assertEquals(head + emoji + "...", line)
    }

    @Test
    fun clipForSpeakLeavesAShortReplyAlone() {
        assertEquals("hello", AnswerShadeText.clipForSpeak("hello"))
    }
}
