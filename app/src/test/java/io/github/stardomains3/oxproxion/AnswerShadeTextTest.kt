package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
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
}
