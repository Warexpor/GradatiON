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
}
