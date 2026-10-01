package io.github.stardomains3.oxproxion.code

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * ACP content blocks inside `agent_message_chunk`, `user_message_chunk`, and tool output.
 * A bare string or an array of strings stays rejected: those frames used to be ignored, and
 * treating them as text would show the wire garbage. An array of content objects is read.
 */
object AcpMessageContent {

    const val MAX_RESOURCE_CHARS = 4_000

    sealed class Piece {
        /** [block] sits on its own line when it follows other text (a link or a file body). */
        data class Text(val text: String, val block: Boolean) : Piece()
        data class Image(val mimeType: String, val data: String) : Piece()
    }

    data class UserPromptBody(val text: String, val imageCount: Int)

    sealed class Parse {
        data class Ok(val pieces: List<Piece>) : Parse()
        data class Rejected(val reason: String) : Parse()
    }

    fun parse(content: JsonElement?): Parse {
        if (content == null || content is JsonNull || content is JsonPrimitive) {
            return Parse.Rejected("without content")
        }
        val pieces = ArrayList<Piece>()
        val rejected = ArrayList<String>()
        when (content) {
            is JsonArray -> {
                if (content.none { it is JsonObject }) return Parse.Rejected("without content")
                for (el in content) {
                    val o = el as? JsonObject ?: continue
                    collectObject(o, pieces, rejected)
                }
            }
            is JsonObject -> collectObject(content, pieces, rejected)
        }
        if (pieces.isEmpty()) return Parse.Rejected(rejected.firstOrNull() ?: "without content")
        return Parse.Ok(pieces)
    }

    /** Text a thought or a tool block can show. Images are skipped. Null when there is none. */
    fun textOf(content: JsonElement?): String? {
        val pieces = (parse(content) as? Parse.Ok)?.pieces ?: return null
        return joinText(pieces, continuing = false)
    }

    fun userBody(content: JsonElement?): UserPromptBody? {
        val pieces = (parse(content) as? Parse.Ok)?.pieces ?: return null
        val text = joinText(pieces, continuing = false).orEmpty()
        val images = pieces.count { it is Piece.Image }
        if (text.isEmpty() && images == 0) return null
        return UserPromptBody(text, images)
    }

    /**
     * Raw text chunks append as the agent sent them. A link or file body starts a new line
     * when [continuing] an open message or when text already sits in this chunk.
     */
    fun joinText(pieces: List<Piece>, continuing: Boolean): String? {
        val sb = StringBuilder()
        var any = false
        for (p in pieces) {
            if (p !is Piece.Text) continue
            if (p.block && (any || continuing)) {
                if (sb.isEmpty() || sb.last() != '\n') sb.append('\n')
            }
            sb.append(p.text)
            any = true
        }
        return if (any) sb.toString() else null
    }

    private fun collectObject(o: JsonObject, pieces: MutableList<Piece>, rejected: MutableList<String>) {
        when (o.str("type")) {
            "image" -> considerImage(o, pieces, rejected)
            "resource_link" -> {
                val label = linkLabel(o.str("name"), o.str("uri"), o.str("title")) ?: return
                pieces += Piece.Text(label, block = true)
            }
            "resource" -> collectResource(o["resource"] as? JsonObject, pieces, rejected)
            "content" -> {
                val inner = o["content"]
                if (inner is JsonObject) collectObject(inner, pieces, rejected)
                else if (inner is JsonArray) {
                    for (el in inner) {
                        val child = el as? JsonObject ?: continue
                        collectObject(child, pieces, rejected)
                    }
                }
            }
            // Missing type: older fixtures send `{text: "..."}` with no type.
            "text", null -> {
                val text = o.str("text") ?: return
                if (text.isEmpty()) return
                pieces += Piece.Text(text, block = false)
            }
            else -> rejected += "type ${o.str("type")}"
        }
    }

    private fun collectResource(res: JsonObject?, pieces: MutableList<Piece>, rejected: MutableList<String>) {
        if (res == null) return
        val text = res.str("text")?.takeIf { it.isNotEmpty() }
        if (text != null) {
            pieces += Piece.Text(clipResource(text), block = true)
            return
        }
        val blob = res.str("blob")?.trim().orEmpty()
        val mime = res.str("mimeType")?.trim()?.lowercase().orEmpty()
        if (blob.isNotEmpty() && mime.isNotEmpty()) {
            val before = pieces.size
            considerImage(mime, blob, pieces, rejected)
            if (pieces.size > before) return
        }
        val label = linkLabel(null, res.str("uri"), null) ?: return
        pieces += Piece.Text(label, block = true)
    }

    private fun considerImage(o: JsonObject, pieces: MutableList<Piece>, rejected: MutableList<String>) {
        val mime = o.str("mimeType")?.trim()?.lowercase().orEmpty()
        val data = o.str("data")?.trim().orEmpty()
        considerImage(mime, data, pieces, rejected)
    }

    private fun considerImage(mime: String, data: String, pieces: MutableList<Piece>, rejected: MutableList<String>) {
        when {
            mime.isEmpty() || data.isEmpty() -> rejected += "image missing mimeType/data"
            !CodePromptImages.isAllowedMime(mime) -> rejected += "unsupported mime $mime"
            data.length > CodePromptImages.MAX_INLINE_BASE64_CHARS -> rejected += "image data too large"
            else -> pieces += Piece.Image(mime, data)
        }
    }

    private fun linkLabel(name: String?, uri: String?, title: String?): String? {
        val n = name?.trim()?.ifEmpty { null } ?: title?.trim()?.ifEmpty { null }
        val u = uri?.trim()?.ifEmpty { null }
        return when {
            n != null && u != null && n != u -> "$n ($u)"
            n != null -> n
            else -> u
        }
    }

    private fun clipResource(text: String): String =
        if (text.length > MAX_RESOURCE_CHARS) text.take(MAX_RESOURCE_CHARS) + "…" else text

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
}
