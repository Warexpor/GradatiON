package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream

class RpCardImportTest {

    private val v2 = """
        {"spec":"chara_card_v2","spec_version":"2.0","data":{
          "name":"Mira Vance",
          "description":"{{char}} is a starship mechanic.",
          "personality":"sharp, loyal",
          "scenario":"A dock on Ceres.",
          "first_mes":"*She looks up.* \"You again, {{user}}?\"",
          "mes_example":"<START>\n{{user}}: Hi.\n{{char}}: *nods* Hey.\n<START>\n{{user}}: Fix it?\n{{char}}: Maybe.",
          "system_prompt":"{{original}}",
          "post_history_instructions":"Keep replies short.",
          "alternate_greetings":["Another hello"],
          "character_book":{"entries":[
            {"keys":["Ceres"],"content":"Ceres is a dwarf planet.","enabled":true,"insertion_order":2},
            {"keys":[],"content":"Ships are old here.","enabled":true,"constant":true,"insertion_order":1},
            {"keys":["secret"],"content":"Hidden.","enabled":false}
          ]},
          "extensions":{"depth_prompt":{"prompt":"Stay grumpy.","depth":4}}
        }}
    """.trimIndent()

    @Test
    fun v2CardMapsOntoTheCharacter() {
        val card = RpCardImport.fromJson(v2)!!
        val c = card.character
        assertEquals("Mira Vance", c.name)
        assertEquals("{{char}} is a starship mechanic.\n\nsharp, loyal", c.personality)
        assertEquals("A dock on Ceres.", c.scenario)
        assertTrue(c.greeting.startsWith("*She looks up.*"))
        assertEquals(2, RpPromptEngine.parseExamples(c.examplesJson).size)
        assertEquals("Hi.", RpPromptEngine.parseExamples(c.examplesJson)[0].user)
        // {{original}} alone is no instruction; the post-history note and depth prompt are.
        assertEquals("Keep replies short.\n\nStay grumpy.", c.instruction)
        assertEquals("Mira Vance", c.lorebookName)
        assertNull("a JSON card leaves the phone's portrait", c.avatarBase64)
    }

    @Test
    fun cardBookBecomesKeyedLore() {
        val book = RpCardImport.fromJson(v2)!!.lorebook!!
        assertEquals("Ships are old here.\n\n[keys: Ceres]\nCeres is a dwarf planet.", book.content)
        val entries = RpLore.parse(book.content)
        assertEquals(listOf(emptyList(), listOf("Ceres")), entries.map { it.keys })
        assertTrue(book.content.contains("Hidden.").not())
    }

    @Test
    fun v1CardAndBackupsAreToldApart() {
        val v1 = """{"name":"Ash","description":"A ranger.","first_mes":"Hello."}"""
        val card = RpCardImport.fromJson(v1)!!
        assertEquals("Ash", card.character.name)
        assertNull(card.lorebook)
        assertNull(card.character.lorebookName)
        assertNull(RpCardImport.fromJson("""{"characters":[{"name":"Ash"}]}"""))
        assertNull(RpCardImport.fromJson("""{"name":"","first_mes":"x"}"""))
        assertNull(RpCardImport.fromJson("""{"name":"Just a name"}"""))
    }

    @Test
    fun pngCardIsReadFromItsTextChunk() {
        val payload = Base64.getEncoder().encodeToString(v2.toByteArray())
        val png = png(listOf(chunk("tEXt", "chara".toByteArray() + 0 + payload.toByteArray())))
        val card = RpCardImport.fromPng(png)
        assertNotNull(card)
        assertEquals("Mira Vance", card!!.character.name)
        assertEquals(Base64.getEncoder().encodeToString(png), card.character.avatarBase64)
    }

    @Test
    fun v3ChunkWinsAndCompressedChunksRead() {
        val v3 = v2.replace("Mira Vance", "Mira Three").replace("chara_card_v2", "chara_card_v3")
        val old = Base64.getEncoder().encodeToString(v2.toByteArray())
        val new = Base64.getEncoder().encodeToString(v3.toByteArray())
        val png = png(listOf(
            chunk("tEXt", "chara".toByteArray() + 0 + old.toByteArray()),
            chunk("zTXt", "ccv3".toByteArray() + 0 + 0 + deflate(new.toByteArray())),
        ))
        assertEquals("Mira Three", RpCardImport.fromPng(png)!!.character.name)
    }

    @Test
    fun plainPngIsNoCard() {
        assertNull(RpCardImport.fromPng(png(emptyList())))
        assertNull(RpCardImport.fromPng(byteArrayOf(1, 2, 3)))
        // A length that runs past the end stops the scan instead of throwing.
        val torn = png(emptyList()).copyOf(8) + byteArrayOf(0x7f, 0, 0, 0) + "tEXt".toByteArray()
        assertNull(RpCardImport.fromPng(torn))
    }

    private fun png(chunks: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        chunks.forEach(out::write)
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val n = data.size
        out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        out.write(type.toByteArray())
        out.write(data)
        out.write(ByteArray(4)) // CRC, not checked
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private operator fun ByteArray.plus(b: Int): ByteArray = this + byteArrayOf(b.toByte())
}
