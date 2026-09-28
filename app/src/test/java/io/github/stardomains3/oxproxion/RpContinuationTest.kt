package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Test

class RpContinuationTest {

    @Test fun nothingAddedLeavesTheReplyAlone() {
        assertEquals("She smiles.", RpContinuation.join("She smiles.", ""))
    }

    @Test fun anEmptyReplyIsJustTheAddition() {
        assertEquals("Hello", RpContinuation.join("", "Hello"))
    }

    @Test fun aFinishedSentenceGetsASpaceBeforeTheNextWord() {
        assertEquals("She smiles. Then she turns.", RpContinuation.join("She smiles.", "Then she turns."))
    }

    @Test fun aClosedActionGetsASpaceBeforeDialogue() {
        assertEquals("*She smiles.* \"Come in.\"", RpContinuation.join("*She smiles.*", "\"Come in.\""))
    }

    @Test fun whitespaceTheModelSentIsKept() {
        assertEquals("She smiles.\n\nThen she turns.", RpContinuation.join("She smiles.", "\n\nThen she turns."))
        assertEquals("She smiles. Then", RpContinuation.join("She smiles. ", "Then"))
    }

    @Test fun punctuationHugsTheWordBeforeIt() {
        assertEquals("She waits, and", RpContinuation.join("She waits", ", and"))
        assertEquals("Really?!", RpContinuation.join("Really", "?!"))
    }

    @Test fun anOpenDashOrBracketTakesTheNextWordDirectly() {
        assertEquals("Wait—what", RpContinuation.join("Wait—", "what"))
        assertEquals("(quietly)", RpContinuation.join("(", "quietly)"))
    }

    @Test fun theContinueDirectionAsksForAnExactSeam() {
        val d = RpPromptEngine.CONTINUE_DIRECTION
        assert("exactly where it ends" in d)
        assert("mid-sentence" in d)
    }
}
