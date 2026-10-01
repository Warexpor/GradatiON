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

    @Test
    fun aPhotoWithNoCaptionGainsASceneLineOnTheWireOnly() {
        val photo = buildJsonArray {
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,qq") })
            })
        }
        val noted = MessageContent.withScenePhotoNote(photo, RpPromptEngine.PHOTO_TURN)
        assertEquals(RpPromptEngine.PHOTO_TURN, MessageContent.text(noted))
        assertEquals("data:image/jpeg;base64,qq", MessageContent.imageUrl(noted))
        val again = MessageContent.withScenePhotoNote(noted, RpPromptEngine.PHOTO_TURN)
        assertTrue(again === noted)
        val blankCaption = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", "  ")
            })
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,qq") })
            })
        }
        assertEquals(
            RpPromptEngine.PHOTO_TURN,
            MessageContent.text(MessageContent.withScenePhotoNote(blankCaption, RpPromptEngine.PHOTO_TURN))
        )
        val captioned = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", "the docks")
            })
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,qq") })
            })
        }
        assertTrue(MessageContent.withScenePhotoNote(captioned, RpPromptEngine.PHOTO_TURN) === captioned)
    }

    @Test
    fun anImageOnlyUserTurnCarriesTheSceneLineAndDropsTheFileUri() {
        val photo = buildJsonArray {
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,qq") })
            })
        }
        val wire = FlexibleMessage(role = "user", content = photo, imageUri = "content://scene/1").toApiMessage()
        assertEquals(RpPromptEngine.PHOTO_TURN, MessageContent.text(wire.content))
        assertEquals(null, wire.imageUri)
        val stored = FlexibleMessage(role = "user", content = photo, imageUri = "content://scene/1")
        assertEquals("", MessageContent.text(stored.content))
        val assistant = FlexibleMessage(role = "assistant", content = photo).toApiMessage()
        assertEquals("", MessageContent.text(assistant.content))
    }
}
