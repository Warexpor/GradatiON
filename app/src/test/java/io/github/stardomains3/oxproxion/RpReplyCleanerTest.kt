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
    fun keepsProseThatOnlyStartsLikeALeak() {
        val story = "Instructions were clear, she said.\nNo limits, he whispered.\nFormat your thoughts first."
        assertEquals(story, RpReplyCleaner.clean(story))
    }

    @Test
    fun stripsWholeLineLeaks() {
        val cleaned = RpReplyCleaner.clean("No limits.\nIMPORTANT: stay in character\nShe nods.")
        assertEquals("She nods.", cleaned)
    }

    @Test
    fun collapsesTheGapAStrippedLineLeaves() {
        val cleaned = RpReplyCleaner.clean("She smiles.\n\nFormat: *action*\n\n\nHe waves.")
        assertEquals("She smiles.\n\nHe waves.", cleaned)
    }

    @Test
    fun stripsAnUnclosedThinkBlock() {
        assertEquals("Hello", RpReplyCleaner.clean("Hello<think>still reasoning about what to"))
    }

    @Test
    fun stripsReasoningThatOnlyHasAClosingTag() {
        assertEquals("Hello there", RpReplyCleaner.clean("let me think…</think>Hello there"))
    }

    @Test
    fun stripsARewritePreamble() {
        assertEquals("*She waits.*", RpReplyCleaner.clean("Here's the rewritten reply:\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("(OOC: Rewrite your last reply above.)\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("（OOC：Rewrite your last reply above.）\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("[OOC: Rewrite your last reply above.]\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("【OOC：Rewrite your last reply above.】\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("{OOC: Rewrite your last reply above.}\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("｛OOC：Rewrite your last reply above.｝\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("〔OOC：Rewrite your last reply above.〕\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("〖OOC：Rewrite your last reply above.〗\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("〈OOC：Rewrite your last reply above.〉\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("《OOC：Rewrite your last reply above.》\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("｟OOC：Rewrite your last reply above.｠\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("〘OOC：Rewrite your last reply above.〙\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("⟨OOC：Rewrite your last reply above.⟩\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("❰OOC：Rewrite your last reply above.❱\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("〚OOC：Rewrite your last reply above.〛\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("⟪OOC：Rewrite your last reply above.⟫\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("⟬OOC：Rewrite your last reply above.⟭\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("⟦OOC：Rewrite your last reply above.⟧\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("⦃OOC：Rewrite your last reply above.⦄\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("❨OOC：Rewrite your last reply above.❩\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("❪OOC：Rewrite your last reply above.❫\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("❬OOC：Rewrite your last reply above.❭\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("❲OOC：Rewrite your last reply above.❳\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("❴OOC：Rewrite your last reply above.❵\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("⦅OOC：Rewrite your last reply above.⦆\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("⦗OOC：Rewrite your last reply above.⦘\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("⦇OOC：Rewrite your last reply above.⦈\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("⦉OOC：Rewrite your last reply above.⦊\n*She waits.*"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("⧼OOC：Rewrite your last reply above.⧽\n*She waits.*"))
        assertEquals(
            "*She waits.*",
            RpReplyCleaner.clean(RpPromptEngine.rewriteDirective("make it (shorter)") + "\n*She waits.*")
        )
        assertEquals(
            "*She waits.*",
            RpReplyCleaner.clean(
                RpPromptEngine.rewriteDirective("make it shorter") + "\nHere's the rewritten reply:\n*She waits.*"
            )
        )
    }

    @Test
    fun keepsAnOutOfCharacterLineThatIsNotTheRewriteNote() {
        val story = "(OOC: she waves.)\n*She waits.*"
        assertEquals(story, RpReplyCleaner.clean(story))
    }

    @Test
    fun keepsALineThatOnlySoundsLikeARewriteLabel() {
        val story = "Here's the new version of me, she said.\n*She waits.*"
        assertEquals(story, RpReplyCleaner.clean(story))
    }

    @Test
    fun unwrapsAProseFenceAroundTheReply() {
        assertEquals("*She waits.*", RpReplyCleaner.clean("```\n*She waits.*\n```"))
        assertEquals("*She waits.*", RpReplyCleaner.clean("```markdown\n*She waits.*\n```"))
    }

    @Test
    fun keepsAFencedProgram() {
        val code = "```python\nprint(1)\n```"
        assertEquals(code, RpReplyCleaner.clean(code))
    }

    @Test
    fun dropsASceneNoteEchoedAtTheStart() {
        val note = RpPromptEngine.sceneNote("stay tense")
        assertEquals("*She waits.*", RpReplyCleaner.clean("$note\n*She waits.*"))
        assertEquals(note, RpReplyCleaner.clean(note))
        val wide = "（Scene note, not spoken aloud:\nstay tense\n）"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$wide\n*She waits.*"))
        val square = "[Scene note, not spoken aloud:\nstay tense\n]"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$square\n*She waits.*"))
        val lenticular = "【Scene note, not spoken aloud:\nstay tense\n】"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$lenticular\n*She waits.*"))
        val brace = "{Scene note, not spoken aloud:\nstay tense\n}"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$brace\n*She waits.*"))
        val braceWide = "｛Scene note, not spoken aloud:\nstay tense\n｝"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$braceWide\n*She waits.*"))
        val tortoise = "〔Scene note, not spoken aloud:\nstay tense\n〕"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$tortoise\n*She waits.*"))
        val whiteLent = "〖Scene note, not spoken aloud:\nstay tense\n〗"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$whiteLent\n*She waits.*"))
        val angle = "〈Scene note, not spoken aloud:\nstay tense\n〉"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$angle\n*She waits.*"))
        val doubleAngle = "《Scene note, not spoken aloud:\nstay tense\n》"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$doubleAngle\n*She waits.*"))
        val whiteParen = "｟Scene note, not spoken aloud:\nstay tense\n｠"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$whiteParen\n*She waits.*"))
        val whiteTortoise = "〘Scene note, not spoken aloud:\nstay tense\n〙"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$whiteTortoise\n*She waits.*"))
        val mathAngle = "⟨Scene note, not spoken aloud:\nstay tense\n⟩"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$mathAngle\n*She waits.*"))
        val heavyOrnament = "❰Scene note, not spoken aloud:\nstay tense\n❱"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$heavyOrnament\n*She waits.*"))
        val whiteSquare = "〚Scene note, not spoken aloud:\nstay tense\n〛"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$whiteSquare\n*She waits.*"))
        val mathDouble = "⟪Scene note, not spoken aloud:\nstay tense\n⟫"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$mathDouble\n*She waits.*"))
        val mathTortoise = "⟬Scene note, not spoken aloud:\nstay tense\n⟭"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$mathTortoise\n*She waits.*"))
        val mathWhiteSquare = "⟦Scene note, not spoken aloud:\nstay tense\n⟧"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$mathWhiteSquare\n*She waits.*"))
        val whiteCurly = "⦃Scene note, not spoken aloud:\nstay tense\n⦄"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$whiteCurly\n*She waits.*"))
        val flattenedParen = "❨Scene note, not spoken aloud:\nstay tense\n❩"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$flattenedParen\n*She waits.*"))
        val mediumFlattened = "❪Scene note, not spoken aloud:\nstay tense\n❫"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$mediumFlattened\n*She waits.*"))
        val mediumAngle = "❬Scene note, not spoken aloud:\nstay tense\n❭"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$mediumAngle\n*She waits.*"))
        val lightTortoise = "❲Scene note, not spoken aloud:\nstay tense\n❳"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$lightTortoise\n*She waits.*"))
        val mediumCurly = "❴Scene note, not spoken aloud:\nstay tense\n❵"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$mediumCurly\n*She waits.*"))
        val whiteParenMath = "⦅Scene note, not spoken aloud:\nstay tense\n⦆"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$whiteParenMath\n*She waits.*"))
        val blackTortoise = "⦗Scene note, not spoken aloud:\nstay tense\n⦘"
        assertEquals("*She waits.*", RpReplyCleaner.clean("$blackTortoise\n*She waits.*"))
    }

    @Test
    fun keepsEmoji() {
        val cleaned = RpReplyCleaner.clean("Hi 😊 friend")
        assertTrue(cleaned.contains("😊"))
        assertFalse(cleaned.contains("<think>"))
    }
}
