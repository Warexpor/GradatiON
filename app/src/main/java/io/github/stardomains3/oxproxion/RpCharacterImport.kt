package io.github.stardomains3.oxproxion

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater

/**
 * Reads what the import picker hands us: a GradatiON backup, a SillyTavern/Tavern card as JSON
 * (chara_card_v2 / v3, or the flat v1 fields), or a card PNG that carries the JSON in a text
 * chunk. Pure Kotlin so the formats can be tested without a device.
 */
object RpCharacterImport {
    private val json = Json { ignoreUnknownKeys = true }
    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** The characters in [bytes], an empty list for a valid but empty backup, or null when it is neither format. */
    fun parse(bytes: ByteArray): List<RpCharacterExport>? {
        if (bytes.isEmpty()) return null
        if (isPng(bytes)) {
            val card = cardJsonFromPng(bytes) ?: return null
            return fromCardJson(card, bytes)?.let { listOf(it) }
        }
        val text = bytes.toString(Charsets.UTF_8).removePrefix("﻿").trim()
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: return null
        if ("characters" in root) {
            return try {
                json.decodeFromJsonElement(RpCharacterBackup.serializer(), root).characters
            } catch (_: Exception) {
                null
            }
        }
        return fromCardObject(root, null)?.let { listOf(it) }
    }

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size > pngSignature.size && pngSignature.indices.all { bytes[it] == pngSignature[it] }

    /** The base64 JSON in the card's `ccv3` chunk (preferred, it is the newer spec) or `chara`. */
    fun cardJsonFromPng(png: ByteArray): String? {
        val found = HashMap<String, String>()
        var pos = pngSignature.size
        while (pos + 8 <= png.size) {
            val len = readInt(png, pos)
            val type = String(png, pos + 4, 4, Charsets.ISO_8859_1)
            val start = pos + 8
            if (len < 0 || start + len > png.size) break
            if (type == "tEXt" || type == "iTXt" || type == "zTXt") {
                textChunk(type, png.copyOfRange(start, start + len))?.let { (key, value) ->
                    if (key == "chara" || key == "ccv3") found.putIfAbsent(key, value)
                }
            }
            if (type == "IEND") break
            pos = start + len + 4 // data, then the 4-byte CRC
        }
        val encoded = found["ccv3"] ?: found["chara"] ?: return null
        return try {
            String(Base64.getMimeDecoder().decode(encoded.trim()), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun readInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun textChunk(type: String, data: ByteArray): Pair<String, String>? {
        val keyEnd = data.indexOfByte(0)
        if (keyEnd <= 0) return null
        val key = String(data, 0, keyEnd, Charsets.ISO_8859_1)
        return when (type) {
            "tEXt" -> key to String(data, keyEnd + 1, data.size - keyEnd - 1, Charsets.ISO_8859_1)
            "zTXt" -> {
                // keyword, NUL, compression method (0 = zlib), then the compressed text
                val body = data.copyOfRange(minOf(keyEnd + 2, data.size), data.size)
                inflate(body)?.let { key to it.toString(Charsets.ISO_8859_1) }
            }
            else -> {
                // iTXt: keyword, NUL, compressed flag, method, language, NUL, translated keyword, NUL, text
                if (keyEnd + 3 > data.size) return null
                val compressed = data[keyEnd + 1].toInt() == 1
                val langEnd = data.indexOfByte(0, keyEnd + 3)
                if (langEnd < 0) return null
                val translatedEnd = data.indexOfByte(0, langEnd + 1)
                if (translatedEnd < 0) return null
                val body = data.copyOfRange(translatedEnd + 1, data.size)
                val raw = if (compressed) inflate(body) else body
                raw?.let { key to it.toString(Charsets.UTF_8) }
            }
        }
    }

    private fun ByteArray.indexOfByte(value: Int, from: Int = 0): Int {
        for (i in from until size) if (this[i].toInt() == value) return i
        return -1
    }

    private fun inflate(data: ByteArray): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun fromCardJson(text: String, png: ByteArray?): RpCharacterExport? {
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: return null
        return fromCardObject(root, png)
    }

    /**
     * V2/V3 cards keep the fields under `data`; V1 has them flat on the root. Our fields have no
     * separate "description", so it leads the personality: that is where a card says who the
     * character is, and Tavern's short personality line follows.
     */
    fun fromCardObject(root: JsonObject, png: ByteArray?): RpCharacterExport? {
        val data = root["data"] as? JsonObject
        fun field(key: String): String =
            (data?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: (root[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

        val name = field("name").trim()
        if (name.isEmpty()) return null
        val spec = (root["spec"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val looksLikeCard = spec.startsWith("chara_card") ||
            listOf("description", "personality", "scenario", "first_mes", "mes_example").any { field(it).isNotBlank() }
        if (!looksLikeCard) return null
        val personality = listOf(field("description"), field("personality"))
            .map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
        return RpCharacterExport(
            name = name,
            personality = personality,
            greeting = field("first_mes").trim(),
            scenario = field("scenario").trim(),
            examplesJson = examplesFromTavern(field("mes_example")),
            instruction = field("system_prompt").trim(),
            avatarBase64 = png?.let { Base64.getEncoder().encodeToString(it) }
        )
    }

    private val startMarker = Regex("<START>", RegexOption.IGNORE_CASE)
    private val userTag = Regex("""^\s*(?:\{\{user\}\}|<USER>)\s*:\s*""", RegexOption.IGNORE_CASE)
    private val charTag = Regex("""^\s*(?:\{\{char\}\}|<BOT>)\s*:\s*""", RegexOption.IGNORE_CASE)

    /** Tavern's `mes_example` (`<START>` blocks of `{{user}}:` / `{{char}}:` lines) as our example list, JSON-encoded. */
    fun examplesFromTavern(raw: String): String {
        val out = ArrayList<RpExampleDialog>()
        for (block in raw.split(startMarker)) {
            var user = StringBuilder()
            var char = StringBuilder()
            var speaker = 0 // 1 = user, 2 = char
            fun flush() {
                if (user.isNotBlank() || char.isNotBlank()) {
                    out += RpExampleDialog(user.toString().trim(), char.toString().trim())
                }
                user = StringBuilder()
                char = StringBuilder()
            }
            for (line in block.lines()) {
                when {
                    userTag.containsMatchIn(line) -> {
                        // A user line after the character spoke opens the next pair.
                        if (speaker == 2) flush()
                        speaker = 1
                        user.append(if (user.isEmpty()) "" else "\n").append(line.replace(userTag, ""))
                    }
                    charTag.containsMatchIn(line) -> {
                        speaker = 2
                        char.append(if (char.isEmpty()) "" else "\n").append(line.replace(charTag, ""))
                    }
                    speaker == 1 -> user.append("\n").append(line)
                    speaker == 2 -> char.append("\n").append(line)
                }
            }
            flush()
        }
        return json.encodeToString(out)
    }
}
