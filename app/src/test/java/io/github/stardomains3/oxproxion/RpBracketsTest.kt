package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RpBracketsTest {
    @Test
    fun everyPairIsOneOpenAndOneClose() {
        assertEquals(30, RpBrackets.pairs.size)
        RpBrackets.pairs.forEach { (open, close) -> assertEquals(close, RpBrackets.closeOf(open)) }
        assertNull(RpBrackets.closeOf('a'))
    }

    @Test
    fun closeMatchesNestingAndFullwidthTwins() {
        assertEquals(8, RpBrackets.matchingClose("(a (b) c)d"))
        assertEquals(4, RpBrackets.matchingClose("（OOC)rest"))
        assertEquals(2, RpBrackets.matchingClose("⟦x⟧"))
        assertEquals(-1, RpBrackets.matchingClose("(never"))
        assertEquals(-1, RpBrackets.matchingClose("plain"))
        assertEquals(-1, RpBrackets.matchingClose(""))
    }

    @Test
    fun sceneNoteEchoInANewBracketIsStripped() {
        val echo = "⧼Scene note, not spoken aloud:\nShe is tired.\n⧽\n\n*She yawns.*"
        assertEquals("*She yawns.*", RpPromptEngine.withoutLeadingSceneNote(echo))
    }
}
