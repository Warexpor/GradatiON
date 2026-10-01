package io.github.stardomains3.oxproxion

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class RpContinuationTest {

    @Test fun nothingAddedLeavesTheReplyAlone() {
        assertEquals("She smiles.", RpContinuation.join("She smiles.", ""))
    }

    @Test fun anEmptyReplyIsJustTheAddition() {
        assertEquals("Hello", RpContinuation.join("", "Hello"))
    }

    @Test fun aFinishedSentenceStartsANewParagraph() {
        assertEquals("She smiles.\n\nThen she turns.", RpContinuation.join("She smiles.", "Then she turns."))
    }

    @Test fun aClosedActionStartsANewParagraphBeforeDialogue() {
        assertEquals("*She smiles.*\n\n\"Come in.\"", RpContinuation.join("*She smiles.*", "\"Come in.\""))
        assertEquals("\"Come in.\"\n\nShe waits.", RpContinuation.join("\"Come in.\"", "She waits."))
    }

    @Test fun unfinishedTextGetsASpaceBeforeTheNextWord() {
        assertEquals("She waits and", RpContinuation.join("She waits", "and"))
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

    @Test fun aFinishedJapaneseSentenceStartsANewParagraph() {
        assertEquals("彼女は微笑む。\n\nそして振り向く。", RpContinuation.join("彼女は微笑む。", "そして振り向く。"))
        assertEquals("「来て。」\n\n彼は待つ。", RpContinuation.join("「来て。」", "彼は待つ。"))
    }

    @Test fun unfinishedJapaneseAndKoreanDoNotGainASpace() {
        assertEquals("彼女は待つそして", RpContinuation.join("彼女は待つ", "そして"))
        assertEquals("안녕잘 가", RpContinuation.join("안녕", "잘 가"))
    }

    @Test fun japanesePunctuationHugsTheWordBeforeIt() {
        assertEquals("彼女は待つ。", RpContinuation.join("彼女は待つ", "。"))
    }

    @Test fun continueKeepsThePictureAlreadyOnTheReply() {
        val piece = FlexibleMessage(role = "assistant", content = JsonPrimitive("She turns."))
        val kept = RpContinuation.keepPicture("content://scene/1", piece)
        assertEquals("content://scene/1", kept.imageUri)
        assertEquals("She turns.", (kept.content as JsonPrimitive).content)
        val fresh = piece.copy(imageUri = "content://scene/2")
        assertEquals("content://scene/2", RpContinuation.keepPicture("content://scene/1", fresh).imageUri)
        assertEquals(null, RpContinuation.keepPicture("data:image/jpeg;base64,qq", piece).imageUri)
    }

    @Test fun theContinueDirectionAsksForAnExactSeam() {
        val d = RpPromptEngine.CONTINUE_DIRECTION
        assert("exactly where it ends" in d)
        assert("mid-sentence" in d)
    }
}
