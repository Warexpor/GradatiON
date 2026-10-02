package io.github.stardomains3.oxproxion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanVisionTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test
    fun namesThatSee() {
        listOf(
            "llava:13b", "qwen2.5-vl-7b-instruct", "Qwen3-VL-8B", "gemma3:12b", "llama3.2-vision",
            "minicpm-v", "pixtral-12b", "moondream", "mistral-small-3.1-24b",
        ).forEach { assertTrue(it, LanVision.fromName(it)) }
        listOf("llama3.1:8b", "qwen2.5-coder", "deepseek-r1", "mistral-nemo", "devstral")
            .forEach { assertFalse(it, LanVision.fromName(it)) }
    }

    @Test
    fun ollamaSaysSoOrHasAProjector() {
        assertEquals(true, LanVision.fromOllamaShow(obj("""{"capabilities":["completion","vision"]}""")))
        assertEquals(false, LanVision.fromOllamaShow(obj("""{"capabilities":["completion"]}""")))
        assertNull("an older server does not say", LanVision.fromOllamaShow(obj("""{"details":{}}""")))
        assertTrue(LanVision.fromOllamaTag(obj("""{"details":{"families":["llama","clip"]}}""")))
        assertFalse(LanVision.fromOllamaTag(obj("""{"details":{"families":["llama"]}}""")))
    }

    @Test
    fun lmStudioMarksVlm() {
        val body = obj("""{"data":[{"id":"a","type":"vlm"},{"id":"b","type":"llm"}]}""")
        assertEquals(setOf("a"), LanVision.lmStudioVisionIds(body))
    }
}
