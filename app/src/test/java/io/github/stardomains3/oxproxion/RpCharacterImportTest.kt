package io.github.stardomains3.oxproxion

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RpCharacterImportTest {

    private val v2 = """
        {"spec":"chara_card_v2","spec_version":"2.0","data":{
          "name":"Mira Vance","description":"A mechanic with grease on her sleeves.",
          "personality":"Gruff, loyal","scenario":"Her workshop, late at night.",
          "first_mes":"*wipes her hands* You again?",
          "mes_example":"<START>\n{{user}}: The coupling leaks.\n{{char}}: *sighs* Show me.\n<START>\n{{user}}: Thanks.\n{{char}}: Don't mention it.",
          "system_prompt":"Stay grounded.","creator_notes":"ignored"}}
    """.trimIndent()

    @Test fun v2Json_mapsFields() {
        val card = RpCharacterImport.parse(v2.toByteArray())!!.single()
        assertEquals("Mira Vance", card.name)
        // Description leads, Tavern's short personality follows.
        assertEquals("A mechanic with grease on her sleeves.\n\nGruff, loyal", card.personality)
        assertEquals("Her workshop, late at night.", card.scenario)
        assertEquals("*wipes her hands* You again?", card.greeting)
        assertEquals("Stay grounded.", card.instruction)
        assertEquals("", card.exportKey)
        assertNull(card.avatarBase64)
    }

    @Test fun v2Json_convertsExamples() {
        val card = RpCharacterImport.parse(v2.toByteArray())!!.single()
        val examples = Json.decodeFromString<List<RpExampleDialog>>(card.examplesJson)
        assertEquals(2, examples.size)
        assertEquals(RpExampleDialog("The coupling leaks.", "*sighs* Show me."), examples[0])
        assertEquals(RpExampleDialog("Thanks.", "Don't mention it."), examples[1])
    }

    @Test fun v1Json_readsFlatFields() {
        val v1 = """{"name":"Old Card","description":"Retired pilot.","personality":"","scenario":"A bar.","first_mes":"Hey.","mes_example":""}"""
        val card = RpCharacterImport.parse(v1.toByteArray())!!.single()
        assertEquals("Old Card", card.name)
        assertEquals("Retired pilot.", card.personality)
        assertEquals("A bar.", card.scenario)
        assertEquals("Hey.", card.greeting)
        assertEquals("[]", card.examplesJson)
    }

    @Test fun v3Json_readsDataBlock() {
        val v3 = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"Kestrel","description":"Detective.","first_mes":"It never stops raining."}}"""
        val card = RpCharacterImport.parse(v3.toByteArray())!!.single()
        assertEquals("Kestrel", card.name)
        assertEquals("Detective.", card.personality)
        assertEquals("It never stops raining.", card.greeting)
    }

    @Test fun pngWithTextChunk_readsCardAndKeepsImageAsAvatar() {
        val png = pngWith(textChunk("chara", b64(v2)))
        val card = RpCharacterImport.parse(png)!!.single()
        assertEquals("Mira Vance", card.name)
        assertEquals("*wipes her hands* You again?", card.greeting)
        assertEquals(Base64.getEncoder().encodeToString(png), card.avatarBase64)
    }

    @Test fun pngWithInternationalTextChunk_readsCcv3First() {
        val v3 = """{"spec":"chara_card_v3","data":{"name":"Ondine","description":"Tide witch."}}"""
        val png = pngWith(textChunk("chara", b64(v2)), itxtChunk("ccv3", b64(v3), compressed = false))
        assertEquals("Ondine", RpCharacterImport.parse(png)!!.single().name)
        val zipped = pngWith(itxtChunk("chara", b64(v3), compressed = true))
        assertEquals("Ondine", RpCharacterImport.parse(zipped)!!.single().name)
    }

    @Test fun pngWithoutCard_isNotACard() {
        assertNull(RpCharacterImport.parse(pngWith(textChunk("Software", "gimp"))))
    }

    @Test fun gradationBackup_passesThrough() {
        val backup = """{"characters":[{"name":"A","exportKey":"k1","personality":"p"},{"name":"B"}]}"""
        val list = RpCharacterImport.parse(backup.toByteArray())!!
        assertEquals(listOf("A", "B"), list.map { it.name })
        assertEquals("k1", list[0].exportKey)
        assertEquals(0, RpCharacterImport.parse("""{"characters":[]}""".toByteArray())!!.size)
    }

    @Test fun garbage_isNotACard() {
        assertNull(RpCharacterImport.parse("hello".toByteArray()))
        assertNull(RpCharacterImport.parse("""{"lorebooks":[]}""".toByteArray()))
        assertNull(RpCharacterImport.parse("""{"name":"Nameless object only"}""".toByteArray()))
        assertNull(RpCharacterImport.parse(ByteArray(0)))
        assertNotNull(RpCharacterImport.parse("﻿$v2".toByteArray()))
    }

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val len = data.size
        out.write(byteArrayOf((len shr 24).toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte()))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(data)
        out.write(ByteArray(4)) // the reader skips the CRC
        return out.toByteArray()
    }

    private fun textChunk(key: String, value: String) =
        chunk("tEXt", key.toByteArray(Charsets.ISO_8859_1) + 0 + value.toByteArray(Charsets.ISO_8859_1))

    private fun itxtChunk(key: String, value: String, compressed: Boolean): ByteArray {
        val body = if (compressed) {
            val deflater = Deflater()
            deflater.setInput(value.toByteArray())
            deflater.finish()
            val buf = ByteArray(4096)
            buf.copyOf(deflater.deflate(buf)).also { deflater.end() }
        } else {
            value.toByteArray()
        }
        val head = key.toByteArray(Charsets.ISO_8859_1) + 0 + byteArrayOf(if (compressed) 1 else 0, 0) + 0 + 0
        return chunk("iTXt", head + body)
    }

    private fun pngWith(vararg chunks: ByteArray): ByteArray {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val ihdr = chunk("IHDR", ByteArray(13))
        return signature + ihdr + chunks.reduce { a, b -> a + b } + chunk("IEND", ByteArray(0))
    }

    @Test fun signatureCheck() {
        assertTrue(RpCharacterImport.isPng(pngWith(textChunk("a", "b"))))
    }

    /** One Import picker: content, not the file name, decides characters vs lorebooks. */
    @Test fun importDetectsLorebookBackups() {
        val lore = """{"lorebooks":[{"name":"World","content":"x","isActive":true}]}""".toByteArray()
        val chars = """{"characters":[{"name":"Mira"}]}""".toByteArray()
        val tavern = """{"spec":"chara_card_v2","data":{"name":"Mira"}}""".toByteArray()
        org.junit.Assert.assertTrue(RpImportFlow.isLorebookBackup(lore))
        org.junit.Assert.assertFalse(RpImportFlow.isLorebookBackup(chars))
        org.junit.Assert.assertFalse(RpImportFlow.isLorebookBackup(tavern))
        org.junit.Assert.assertFalse(RpImportFlow.isLorebookBackup("not json".toByteArray()))
    }
}
