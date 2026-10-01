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
