package io.github.stardomains3.oxproxion

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A message part that is not a string must not throw. Opening the chat reads this on the main thread. */
class MessageContentTest {

    @Test
    fun aStringIsItself() {
        assertEquals("hi", MessageContent.text(JsonPrimitive("hi")))
    }

    @Test
    fun jsonNullIsEmpty() {
        assertEquals("", MessageContent.text(JsonNull))
    }

    @Test
    fun theFirstTextPartIsShown() {
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", "hello")
            })
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", "data:image/png;base64,qq") })
            })
        }
        assertEquals("hello", MessageContent.text(content))
        assertEquals("data:image/png;base64,qq", MessageContent.imageUrl(content))
        assertTrue(MessageContent.hasImage(content))
    }

    @Test
    fun aTypeOrTextThatIsNotAStringIsSkipped() {
        val content = buildJsonArray {
            add(JsonPrimitive("nope"))
            add(buildJsonObject {
                put("type", buildJsonObject { put("weird", true) })
                put("text", "hidden")
            })
            add(buildJsonObject {
                put("type", "text")
                put("text", buildJsonObject { put("nested", 1) })
            })
            add(buildJsonObject {
                put("type", "text")
                put("text", JsonNull)
            })
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonArray { add("nope") })
            })
            add(buildJsonObject {
                put("type", "text")
                put("text", "kept")
            })
        }
        assertEquals("kept", MessageContent.text(content))
        assertNull(MessageContent.imageUrl(content))
        assertTrue(MessageContent.hasImage(content))
    }

    @Test
    fun aPlainObjectIsNotText() {
        assertEquals("", MessageContent.text(buildJsonObject { put("text", "no") }))
        assertFalse(MessageContent.hasImage(JsonPrimitive("no")))
    }
}
