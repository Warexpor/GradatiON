package io.github.stardomains3.oxproxion

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Text and image parts of a chat message.
 *
 * A provider, or a damaged row, may put an object, an array, or null where a string belongs.
 * [JsonElement.jsonPrimitive] throws on those, and [JsonPrimitive.content] throws on [JsonNull].
 * Opening the chat, History, and export all read this on the main thread, so a throw closed the app.
 */
internal object MessageContent {
    /** Saved beside the body so a generated picture's file survives a reopen. Not a wire field. */
    private const val KEPT_TYPE = "kept_turn"

    data class Kept(val body: JsonElement, val fileUri: String?)

    /**
     * A file URI stashed for the database. The live transcript and the provider request use
     * [unwrap] so the extra object never leaves the row.
     */
    fun unwrap(content: JsonElement): Kept {
        val obj = content as? JsonObject ?: return Kept(content, null)
        if (obj.string("type") != KEPT_TYPE) return Kept(content, null)
        val body = obj["body"] ?: JsonPrimitive("")
        val file = obj.string("kept")?.takeIf { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
        return Kept(body, file)
    }

    /** The row to store. Image bytes already in [content] stay. A file URI is kept with them. */
    fun forStorage(content: JsonElement, imageUri: String?): JsonElement {
        val kept = unwrap(content)
        val file = imageUri?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("data:", ignoreCase = true) }
            ?: return kept.body
        if (kept.fileUri == file && content is JsonObject && content.string("type") == KEPT_TYPE) return content
        return buildJsonObject {
            put("type", KEPT_TYPE)
            put("body", kept.body)
            put("kept", file)
        }
    }

    /** The first text part, or the string itself. Missing and non-string parts are skipped. */
    fun text(content: JsonElement): String = textBody(unwrap(content).body)

    private fun textBody(content: JsonElement): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.firstNotNullOfOrNull { partText(it) }.orEmpty()
        else -> ""
    }

    /** Every text part, in order. A part that is not an object, or whose text is not a string, is skipped. */
    fun allText(content: JsonArray): List<String> = content.mapNotNull { partText(it) }

    fun imageUrls(content: JsonElement): List<String> {
        val array = unwrap(content).body as? JsonArray ?: return emptyList()
        return array.mapNotNull { part ->
            val obj = part as? JsonObject ?: return@mapNotNull null
            if (partType(obj) != "image_url") return@mapNotNull null
            (obj["image_url"] as? JsonObject)?.string("url")
        }
    }

    fun imageUrl(content: JsonElement): String? = imageUrls(content).firstOrNull()

    /** True when any part says it is an image, even if the url itself is missing or not a string. */
    fun hasImage(content: JsonElement): Boolean {
        val array = unwrap(content).body as? JsonArray ?: return false
        return array.any { partType(it) == "image_url" }
    }

    /**
     * Older scene photos stay as words. The last [keep] user turns that still have a picture
     * keep the bytes; anything earlier drops them so a long chat does not resend every photo.
     */
    fun keepRecentPhotos(messages: List<FlexibleMessage>, keep: Int = 2): List<FlexibleMessage> {
        if (keep < 0 || messages.isEmpty()) return messages
        var left = keep
        val drop = HashSet<Int>()
        for (i in messages.indices.reversed()) {
            val message = messages[i]
            if (message.role != "user" || !hasImage(message.content)) continue
            if (left > 0) left-- else drop.add(i)
        }
        if (drop.isEmpty()) return messages
        return messages.mapIndexed { index, message ->
            if (index !in drop) message
            else message.copy(content = withoutImages(message.content, RpPromptEngine.PHOTO_EARLIER))
        }
    }

    /** Image parts out. A caption stays, and [note] says a picture used to be here. */
    fun withoutImages(content: JsonElement, note: String): JsonElement {
        val body = unwrap(content).body
        val array = body as? JsonArray ?: return body
        if (array.none { partType(it) == "image_url" }) return body
        val kept = array.filterNot { part ->
            partType(part) == "image_url" || (partType(part) == "text" && partText(part).isNullOrBlank())
        }
        val notePart = buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive(note))
        }
        if (kept.none { partType(it) == "text" && !partText(it).isNullOrBlank() }) return JsonPrimitive(note)
        return JsonArray(kept + notePart)
    }

    /**
     * Image parts out, words left as they were. A generated picture is kept for the bubble
     * and the backup; the next request does not send it back.
     */
    fun stripImages(content: JsonElement): JsonElement {
        val array = content as? JsonArray ?: return content
        if (array.none { partType(it) == "image_url" }) return content
        val kept = array.filterNot { part ->
            partType(part) == "image_url" || (partType(part) == "text" && partText(part).isNullOrBlank())
        }
        if (kept.isEmpty()) return JsonPrimitive("")
        if (kept.size == 1) {
            val text = partText(kept[0])
            if (text != null) return JsonPrimitive(text)
        }
        return JsonArray(kept)
    }

    /**
     * An image with no words. Some providers reject that turn, and the picture still has to
     * be read as something in the scene. The stored bubble stays the picture alone; this is
     * the copy that goes on the wire, including a later rewrite of the same turn.
     * A blank text part does not count as a caption.
     */
    fun withScenePhotoNote(content: JsonElement, note: String): JsonElement {
        val array = content as? JsonArray ?: return content
        var hasText = false
        var hasImage = false
        for (item in array) {
            when (partType(item)) {
                "text" -> if (!partText(item).isNullOrBlank()) hasText = true
                "image_url" -> hasImage = true
            }
        }
        if (!hasImage || hasText) return content
        // A blank text part is not a caption. Leaving it lets a provider treat the turn as empty.
        val kept = array.filterNot { part ->
            partType(part) == "text" && partText(part).isNullOrBlank()
        }
        return JsonArray(buildList {
            add(buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(note))
            })
            addAll(kept)
        })
    }

    fun partType(part: JsonElement): String? = (part as? JsonObject)?.let { partType(it) }

    private fun partType(obj: JsonObject): String? = obj.string("type")

    private fun partText(part: JsonElement): String? {
        val obj = part as? JsonObject ?: return null
        if (partType(obj) != "text") return null
        return obj.string("text")
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
