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
        assertFalse(MessageContent.hasImage(assistant.content))
        assertFalse(assistant.content.toString().contains("base64"))
    }

    @Test
    fun aGeneratedPictureStaysInTheChatAndLeavesTheRequest() {
        val story = JsonPrimitive("She holds it up.")
        val jpeg = "data:image/jpeg;base64,AQI="
        val message = ScenePhoto.withGeneratedPicture(
            FlexibleMessage(role = "assistant", content = story),
            ScenePhoto.GeneratedPicture("content://owned/1", jpeg),
        )
        assertEquals("She holds it up.", MessageContent.text(message.content))
        assertEquals(jpeg, MessageContent.imageUrl(message.content))
        assertEquals("content://owned/1", message.imageUri)
        val wire = message.toApiMessage()
        assertEquals("She holds it up.", MessageContent.text(wire.content))
        assertFalse(MessageContent.hasImage(wire.content))
        assertEquals(null, wire.imageUri)
        assertFalse(wire.content.toString().contains("base64"))
        val edited = ScenePhoto.replaceTextKeepingPicture(message.content, "She puts it down.")
        assertEquals("She puts it down.", MessageContent.text(edited))
        assertEquals(jpeg, MessageContent.imageUrl(edited))
    }

    @Test
    fun aBlankCaptionDoesNotStayBesideTheSceneLine() {
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
        val noted = MessageContent.withScenePhotoNote(blankCaption, RpPromptEngine.PHOTO_TURN)
        assertEquals(RpPromptEngine.PHOTO_TURN, MessageContent.text(noted))
        assertEquals("data:image/jpeg;base64,qq", MessageContent.imageUrl(noted))
        assertEquals(listOf(RpPromptEngine.PHOTO_TURN), MessageContent.allText(noted as kotlinx.serialization.json.JsonArray))
    }

    @Test
    fun aSavedPictureRoundTripsAndStaysOffTheWire() {
        val story = JsonPrimitive("*She smiles.*")
        val stored = MessageContent.forStorage(story, "content://generated/1")
        assertEquals("*She smiles.*", MessageContent.text(stored))
        assertEquals("content://generated/1", MessageContent.unwrap(stored).fileUri)
        val wire = FlexibleMessage(role = "assistant", content = stored, imageUri = "content://generated/1").toApiMessage()
        assertEquals("*She smiles.*", MessageContent.text(wire.content))
        assertEquals(null, wire.imageUri)
        assertFalse(wire.content.toString().contains("kept"))
        assertFalse(wire.content.toString().contains("content://"))
        val photo = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", "look")
            })
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,qq") })
            })
        }
        val savedPhoto = MessageContent.forStorage(photo, "content://scene/1")
        assertEquals("look", MessageContent.text(savedPhoto))
        assertEquals("data:image/jpeg;base64,qq", MessageContent.imageUrl(savedPhoto))
        assertEquals("content://scene/1", MessageContent.unwrap(savedPhoto).fileUri)
    }

    @Test
    fun olderScenePhotosLeaveTheRequest() {
        fun photo(caption: String?) = buildJsonArray {
            if (caption != null) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", caption)
                })
            }
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,qq") })
            })
        }
        val messages = listOf(
            FlexibleMessage(role = "user", content = photo("first light")),
            FlexibleMessage(role = "user", content = photo(null)),
            FlexibleMessage(role = "assistant", content = JsonPrimitive("She looks.")),
            FlexibleMessage(role = "user", content = photo("the docks")),
            FlexibleMessage(role = "user", content = photo("the key")),
        )
        val wire = messages.toApiMessages()
        assertEquals("first light", MessageContent.text(wire[0].content))
        assertTrue(wire[0].content.toString().contains(RpPromptEngine.PHOTO_EARLIER))
        assertFalse(MessageContent.hasImage(wire[0].content))
        assertEquals(RpPromptEngine.PHOTO_EARLIER, MessageContent.text(wire[1].content))
        assertFalse(MessageContent.hasImage(wire[1].content))
        assertEquals("She looks.", MessageContent.text(wire[2].content))
        assertEquals("the docks", MessageContent.text(wire[3].content))
        assertTrue(MessageContent.hasImage(wire[3].content))
        assertFalse(wire[3].content.toString().contains(RpPromptEngine.PHOTO_EARLIER))
        assertEquals("the key", MessageContent.text(wire[4].content))
        assertTrue(MessageContent.hasImage(wire[4].content))
    }
}
