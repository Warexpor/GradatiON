package io.github.stardomains3.oxproxion

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.InflaterInputStream

/**
 * Character cards from SillyTavern, Chub and the like: a PNG with the card hidden in a text
 * chunk, or the same card as JSON (V1, V2 `chara_card_v2` or V3 `chara_card_v3`). The card
 * becomes an ordinary backup row, so import, overwrite and portraits work as for a backup.
 */
object RpCardImport {

    data class Card(
        val character: RpCharacterExport,
        /** The card's own lorebook, pinned to it by name. Null when it has none. */
        val lorebook: RpLorebookExport?,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val examplesSerializer = ListSerializer(RpExampleDialog.serializer())
    private val pngSignature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )
    /** `{{original}}` in a card's prompt means "the app's own prompt here"; ours is already there. */
    private val originalMacro = Regex("""\{\{\s*original\s*\}\}""", RegexOption.IGNORE_CASE)

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= pngSignature.size && pngSignature.indices.all { bytes[it] == pngSignature[it] }

    /** The card in a PNG, with the picture as its portrait. Null when the PNG carries no card. */
    fun fromPng(bytes: ByteArray): Card? {
        val chunks = pngText(bytes)
        // V3 cards also carry a V2 copy for older apps; the V3 one is the newer text.
        val encoded = chunks["ccv3"] ?: chunks["chara"] ?: return null
        val text = try {
            Base64.getMimeDecoder().decode(encoded.trim()).toString(Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val card = fromJson(text) ?: return null
        val portrait = Base64.getEncoder().encodeToString(bytes)
        return card.copy(character = card.character.copy(avatarBase64 = portrait))
    }

    /**
     * A card in JSON, or null when [text] is not one (an app backup is not a card).
     * Throws when it is not JSON at all.
     */
    fun fromJson(text: String): Card? {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return null
        if (root.containsKey("characters")) return null
        // V2 and V3 keep everything under "data"; V1 has the fields at the top.
        val data = root["data"] as? JsonObject ?: root
        val name = data.str("name").trim()
        if (name.isEmpty()) return null
        if (data.str("first_mes").isBlank() && data.str("description").isBlank() &&
            data.str("personality").isBlank()
        ) return null

        val description = data.str("description").trim()
        val personality = data.str("personality").trim()
        val definition = listOf(description, personality)
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n\n")
        val examples = RpPromptEngine.parseExamplesFromEdit("\n" + data.str("mes_example"))
        val depthPrompt = (data["extensions"] as? JsonObject)
            ?.let { it["depth_prompt"] as? JsonObject }
            ?.str("prompt").orEmpty()
        val instruction = listOf(data.str("system_prompt"), data.str("post_history_instructions"), depthPrompt)
            .map { originalMacro.replace(it, "").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n\n")
        val lore = (data["character_book"] as? JsonObject)?.let(::bookText).orEmpty()
        val book = if (lore.isBlank()) null else RpLorebookExport(name = name, content = lore)
        val character = RpCharacterExport(
            name = name,
            personality = definition,
            greeting = data.str("first_mes").trim(),
            scenario = data.str("scenario").trim(),
            examplesJson = json.encodeToString(examplesSerializer, examples),
            instruction = instruction,
            lorebookName = book?.name,
        )
        return Card(character, book)
    }

    /**
     * A card's lorebook in our `[keys: …]` form. Always-on entries, and entries with no keys,
     * go first, before any header, so they are always sent. Disabled entries are left out.
     */
    internal fun bookText(book: JsonObject): String {
        val entries = when (val raw = book["entries"]) {
            is JsonArray -> raw.mapNotNull { it as? JsonObject }
            // Old SillyTavern world files keep entries in an object keyed by uid.
            is JsonObject -> raw.values.mapNotNull { it as? JsonObject }
            else -> emptyList()
        }.filter { it.bool("enabled") ?: (it.bool("disable")?.not() ?: true) }
            .sortedBy { it.int("insertion_order") ?: it.int("order") ?: 0 }
        val always = StringBuilder()
        val keyed = StringBuilder()
        for (entry in entries) {
            val content = entry.str("content").trim()
            if (content.isEmpty()) continue
            val keys = (entry.strings("keys") + entry.strings("key"))
                .map { it.replace("[", "").replace("]", "").replace("\n", " ").trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            if (entry.bool("constant") == true || keys.isEmpty()) {
                if (always.isNotEmpty()) always.append("\n\n")
                always.append(content)
            } else {
                if (keyed.isNotEmpty()) keyed.append("\n\n")
                keyed.append("[keys: ").append(keys.joinToString(", ")).append("]\n").append(content)
            }
        }
        return listOf(always.toString(), keyed.toString()).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    /** `tEXt`, `zTXt` and `iTXt` chunks by keyword. A torn file gives whatever came before the tear. */
    internal fun pngText(bytes: ByteArray): Map<String, String> {
        if (!isPng(bytes)) return emptyMap()
        val out = LinkedHashMap<String, String>()
        var at = pngSignature.size
        while (at + 8 <= bytes.size) {
            val length = readInt(bytes, at)
            val type = String(bytes, at + 4, 4, Charsets.ISO_8859_1)
            val start = at + 8
            if (length < 0 || start.toLong() + length > bytes.size) break
            val data = bytes.copyOfRange(start, start + length)
            when (type) {
                "tEXt" -> textChunk(data)?.let { (k, v) -> out[k] = v }
                "zTXt" -> zTextChunk(data)?.let { (k, v) -> out[k] = v }
                "iTXt" -> iTextChunk(data)?.let { (k, v) -> out[k] = v }
                "IEND" -> return out
            }
            at = start + length + 4
        }
        return out
    }

    private fun textChunk(data: ByteArray): Pair<String, String>? {
        val nul = data.indexOf(0).takeIf { it > 0 } ?: return null
        return String(data, 0, nul, Charsets.ISO_8859_1) to
            String(data, nul + 1, data.size - nul - 1, Charsets.ISO_8859_1)
    }

    private fun zTextChunk(data: ByteArray): Pair<String, String>? {
        val nul = data.indexOf(0).takeIf { it > 0 } ?: return null
        if (nul + 2 > data.size) return null
        val text = inflate(data.copyOfRange(nul + 2, data.size)) ?: return null
        return String(data, 0, nul, Charsets.ISO_8859_1) to text.toString(Charsets.ISO_8859_1)
    }

    private fun iTextChunk(data: ByteArray): Pair<String, String>? {
        val nul = data.indexOf(0).takeIf { it > 0 } ?: return null
        if (nul + 3 > data.size) return null
        val compressed = data[nul + 1].toInt() == 1
        // Language tag, then translated keyword, each ending in a NUL.
        var i = nul + 3
        repeat(2) {
            while (i < data.size && data[i] != 0.toByte()) i++
            i++
        }
        if (i > data.size) return null
        val body = data.copyOfRange(i, data.size)
        val text = if (compressed) inflate(body) ?: return null else body
        return String(data, 0, nul, Charsets.ISO_8859_1) to text.toString(Charsets.UTF_8)
    }

    private fun inflate(data: ByteArray): ByteArray? = try {
        InflaterInputStream(data.inputStream()).use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > ImportBounds.MAX_TEXT_BYTES) return null
            }
            out.toByteArray()
        }
    } catch (_: Exception) {
        null
    }

    private fun readInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.strings(key: String): List<String> = when (val v: JsonElement? = this[key]) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> v.contentOrNull?.split(',').orEmpty()
        else -> emptyList()
    }
}
