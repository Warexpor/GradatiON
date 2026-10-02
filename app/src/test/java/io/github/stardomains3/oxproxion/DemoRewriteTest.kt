package io.github.stardomains3.oxproxion

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoRewriteTest {
    @Test
    fun shorterKeepsTheOpening() {
        val previous = "*She waits.*\n\n\"You're late.\"\n\n*Rain on the glass.*"
        val out = DemoModel.demoRewrite(previous, "Make it shorter. Keep the same events and voice.")
        assertTrue(out.contains("She waits."))
        org.junit.Assert.assertFalse(out.contains("Rain on the glass."))
    }

    @Test
    fun noteIsReadOffTheDirective() {
        val directive = RpPromptEngine.rewriteDirective("Make it shorter. Keep the same events and voice.")
        assertEquals("Make it shorter. Keep the same events and voice.", DemoModel.rewriteNote(directive))
        assertTrue(DemoModel.isRewriteRequest(directive))
    }

    @Test
    fun aNoteWithTwoLinesIsKeptWhole() {
        val directive = RpPromptEngine.rewriteDirective("Make it longer.\nKeep the rain.")
        assertEquals("Make it longer.\nKeep the rain.", DemoModel.rewriteNote(directive))
    }

    @Test
    fun theLaterAskWins() {
        val previous = "*She waits.*\n\n\"You're late.\"\n\n*Rain on the glass.*"
        val longer = DemoModel.demoRewrite(previous, "Don't make it shorter, make it longer")
        assertTrue(longer.contains("Rain on the glass."))
        assertTrue(longer.contains("doesn't look away"))
        val spoken = DemoModel.demoRewrite(previous, "More of your dialogue, and less narration.")
        assertTrue(spoken.contains("closer to what you wanted"))
    }

    @Test
    fun previousAssistantIsTheReplyAboveTheNote() {
        val body = buildJsonObject {
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", JsonPrimitive("Hello there."))
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", JsonPrimitive(RpPromptEngine.rewriteDirective("shorter")))
                })
            })
        }.toString()
        assertEquals("Hello there.", DemoModel.previousAssistant(body))
    }

    @Test
    fun aSceneNoteIsSpokenIntoTheDemoReply() {
        val script = DemoModel.Script(null, "*She waits.*")
        assertEquals("*She waits.*", DemoModel.applySceneNote(script, "No note here.").text)
        val noted = DemoModel.applySceneNote(script, RpPromptEngine.sceneNote("mention the locket."))
        assertTrue(noted.text.startsWith("*She waits.*"))
        assertTrue(noted.text.contains("Your note stays in the scene: mention the locket."))
    }

    @Test
    fun sceneNoteRoundTrip() {
        val wrapped = RpPromptEngine.sceneNote("stay (tense)\nand quiet")
        assertEquals("stay (tense)\nand quiet", RpPromptEngine.sceneNoteBody(wrapped))
        assertEquals(null, RpPromptEngine.sceneNoteBody("no note"))
        val wide = "（Scene note, not spoken aloud:\nstay tense\n）"
        assertEquals("stay tense", RpPromptEngine.sceneNoteBody(wide))
        assertEquals("*She waits.*", RpPromptEngine.withoutLeadingSceneNote("$wide\n*She waits.*"))
        val square = "[Scene note, not spoken aloud:\nstay tense\n]"
        assertEquals("stay tense", RpPromptEngine.sceneNoteBody(square))
        assertEquals("*She waits.*", RpPromptEngine.withoutLeadingSceneNote("$square\n*She waits.*"))
        val lenticular = "【Scene note, not spoken aloud:\nstay tense\n】"
        assertEquals("stay tense", RpPromptEngine.sceneNoteBody(lenticular))
        assertEquals("*She waits.*", RpPromptEngine.withoutLeadingSceneNote("$lenticular\n*She waits.*"))
        val brace = "{Scene note, not spoken aloud:\nstay tense\n}"
        assertEquals("stay tense", RpPromptEngine.sceneNoteBody(brace))
        assertEquals("*She waits.*", RpPromptEngine.withoutLeadingSceneNote("$brace\n*She waits.*"))
        val braceWide = "｛Scene note, not spoken aloud:\nstay tense\n｝"
        assertEquals("stay tense", RpPromptEngine.sceneNoteBody(braceWide))
        assertEquals("*She waits.*", RpPromptEngine.withoutLeadingSceneNote("$braceWide\n*She waits.*"))
        val tortoise = "〔Scene note, not spoken aloud:\nstay tense\n〕"
        assertEquals("stay tense", RpPromptEngine.sceneNoteBody(tortoise))
        assertEquals("*She waits.*", RpPromptEngine.withoutLeadingSceneNote("$tortoise\n*She waits.*"))
        val whiteLent = "〖Scene note, not spoken aloud:\nstay tense\n〗"
        assertEquals("stay tense", RpPromptEngine.sceneNoteBody(whiteLent))
        assertEquals("*She waits.*", RpPromptEngine.withoutLeadingSceneNote("$whiteLent\n*She waits.*"))
        val angle = "〈Scene note, not spoken aloud:\nstay tense\n〉"
        assertEquals("stay tense", RpPromptEngine.sceneNoteBody(angle))
        assertEquals("*She waits.*", RpPromptEngine.withoutLeadingSceneNote("$angle\n*She waits.*"))
        val doubleAngle = "《Scene note, not spoken aloud:\nstay tense\n》"
        assertEquals("stay tense", RpPromptEngine.sceneNoteBody(doubleAngle))
        assertEquals("*She waits.*", RpPromptEngine.withoutLeadingSceneNote("$doubleAngle\n*She waits.*"))
        val whiteParen = "｟Scene note, not spoken aloud:\nstay tense\n｠"
        assertEquals("stay tense", RpPromptEngine.sceneNoteBody(whiteParen))
        assertEquals("*She waits.*", RpPromptEngine.withoutLeadingSceneNote("$whiteParen\n*She waits.*"))
        val whiteTortoise = "〘Scene note, not spoken aloud:\nstay tense\n〙"
        assertEquals("stay tense", RpPromptEngine.sceneNoteBody(whiteTortoise))
        assertEquals("*She waits.*", RpPromptEngine.withoutLeadingSceneNote("$whiteTortoise\n*She waits.*"))
        val mathAngle = "⟨Scene note, not spoken aloud:\nstay tense\n⟩"
        assertEquals("stay tense", RpPromptEngine.sceneNoteBody(mathAngle))
        assertEquals("*She waits.*", RpPromptEngine.withoutLeadingSceneNote("$mathAngle\n*She waits.*"))
        val heavyOrnament = "❰Scene note, not spoken aloud:\nstay tense\n❱"
        assertEquals("stay tense", RpPromptEngine.sceneNoteBody(heavyOrnament))
        assertEquals("*She waits.*", RpPromptEngine.withoutLeadingSceneNote("$heavyOrnament\n*She waits.*"))
        val whiteSquare = "〚Scene note, not spoken aloud:\nstay tense\n〛"
        assertEquals("stay tense", RpPromptEngine.sceneNoteBody(whiteSquare))
        assertEquals("*She waits.*", RpPromptEngine.withoutLeadingSceneNote("$whiteSquare\n*She waits.*"))
        val mathDouble = "⟪Scene note, not spoken aloud:\nstay tense\n⟫"
        assertEquals("stay tense", RpPromptEngine.sceneNoteBody(mathDouble))
        assertEquals("*She waits.*", RpPromptEngine.withoutLeadingSceneNote("$mathDouble\n*She waits.*"))
        val mathTortoise = "⟬Scene note, not spoken aloud:\nstay tense\n⟭"
        assertEquals("stay tense", RpPromptEngine.sceneNoteBody(mathTortoise))
        assertEquals("*She waits.*", RpPromptEngine.withoutLeadingSceneNote("$mathTortoise\n*She waits.*"))
        val mathWhiteSquare = "⟦Scene note, not spoken aloud:\nstay tense\n⟧"
        assertEquals("stay tense", RpPromptEngine.sceneNoteBody(mathWhiteSquare))
        assertEquals("*She waits.*", RpPromptEngine.withoutLeadingSceneNote("$mathWhiteSquare\n*She waits.*"))
        val whiteCurly = "⦃Scene note, not spoken aloud:\nstay tense\n⦄"
        assertEquals("stay tense", RpPromptEngine.sceneNoteBody(whiteCurly))
        assertEquals("*She waits.*", RpPromptEngine.withoutLeadingSceneNote("$whiteCurly\n*She waits.*"))
        val flattenedParen = "❨Scene note, not spoken aloud:\nstay tense\n❩"
        assertEquals("stay tense", RpPromptEngine.sceneNoteBody(flattenedParen))
        assertEquals("*She waits.*", RpPromptEngine.withoutLeadingSceneNote("$flattenedParen\n*She waits.*"))
    }
}
