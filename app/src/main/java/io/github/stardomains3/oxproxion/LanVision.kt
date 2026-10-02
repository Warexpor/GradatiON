package io.github.stardomains3.oxproxion

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Whether a model on the user's own server can take a picture. Servers that say so are asked
 * (Ollama's capabilities, LM Studio's model type); otherwise the name decides. Without this every
 * LAN model was text-only, and the photo button stayed off even for a vision model.
 */
object LanVision {

    private val visionName = Regex(
        "vision|llava|bakllava|moondream|minicpm-?v|pixtral|gemma-?3|qwen[0-9.]*-?vl|internvl|" +
            "smolvlm|mllama|llama-?4|paligemma|idefics|molmo|granite.*vision|mistral-small-?3\\.[12]|" +
            "(^|[^a-z])vl([^a-z]|$)|-vlm?([^a-z]|$)",
        RegexOption.IGNORE_CASE,
    )

    fun fromName(id: String): Boolean = visionName.containsMatchIn(id)

    /** Ollama `/api/show`: newer servers list `vision` in capabilities. */
    fun fromOllamaShow(show: JsonObject?): Boolean? {
        val caps = show?.get("capabilities") as? JsonArray ?: return null
        return caps.any { it.jsonPrimitive.contentOrNull.equals("vision", ignoreCase = true) }
    }

    /** Ollama `/api/tags`: older vision models carry a CLIP projector family. */
    fun fromOllamaTag(tag: JsonObject): Boolean {
        val details = tag["details"] as? JsonObject ?: return false
        val families = details["families"] as? JsonArray ?: return false
        return families.any {
            val f = it.jsonPrimitive.contentOrNull.orEmpty()
            f.equals("clip", ignoreCase = true) || f.equals("mllama", ignoreCase = true)
        }
    }

    /** LM Studio `/api/v0/models`: a vision model is `type: "vlm"`. */
    fun lmStudioVisionIds(body: JsonObject?): Set<String> {
        val data = body?.get("data") as? JsonArray ?: return emptySet()
        return data.mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { type.equals("vlm", ignoreCase = true) }
        }.toSet()
    }
}
