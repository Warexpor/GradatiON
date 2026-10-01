package io.github.stardomains3.oxproxion

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Text and image parts of a chat message.
 *
 * A provider, or a damaged row, may put an object, an array, or null where a string belongs.
 * [JsonElement.jsonPrimitive] throws on those, and [JsonPrimitive.content] throws on [JsonNull].
 * Opening the chat, History, and export all read this on the main thread, so a throw closed the app.
 */
internal object MessageContent {
    /** The first text part, or the string itself. Missing and non-string parts are skipped. */
    fun text(content: JsonElement): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.firstNotNullOfOrNull { partText(it) }.orEmpty()
        else -> ""
    }

    /** Every text part, in order. A part that is not an object, or whose text is not a string, is skipped. */
    fun allText(content: JsonArray): List<String> = content.mapNotNull { partText(it) }

    fun imageUrls(content: JsonElement): List<String> {
        val array = content as? JsonArray ?: return emptyList()
        return array.mapNotNull { part ->
            val obj = part as? JsonObject ?: return@mapNotNull null
            if (partType(obj) != "image_url") return@mapNotNull null
            (obj["image_url"] as? JsonObject)?.string("url")
        }
    }

    fun imageUrl(content: JsonElement): String? = imageUrls(content).firstOrNull()

    /** True when any part says it is an image, even if the url itself is missing or not a string. */
    fun hasImage(content: JsonElement): Boolean {
        val array = content as? JsonArray ?: return false
        return array.any { partType(it) == "image_url" }
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
