package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Run: ./gradlew :app:testDebugUnitTest --tests '*ImportBoundsTest*'
 */
class ImportBoundsTest {

    @Test
    fun stopsReadingOnceTheCapIsPassed() {
        val payload = ByteArray(100) { 'a'.code.toByte() }
        val stream = ChunkedStream(payload, chunk = 8)

        val error = assertThrows(ImportBounds.TooLarge::class.java) {
            ImportBounds.readUtf8(stream, maxBytes = 20)
        }

        assertEquals(20, error.limitBytes)
        assertTrue("kept reading after the cap (${stream.position})", stream.position <= 24)
        assertTrue(stream.position < payload.size)
    }

    @Test
    fun stripsAUtf8Bom() {
        val body = "{\"sessions\":[]}".toByteArray(Charsets.UTF_8)
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body

        assertEquals("{\"sessions\":[]}", ImportBounds.readUtf8(ByteArrayInputStream(bytes), maxBytes = 100))
    }

    @Test
    fun decodesNotepadUtf16() {
        val body = "{\"sessions\":[]}"
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + body.toByteArray(Charsets.UTF_16BE)

        assertEquals(body, ImportBounds.readUtf8(ByteArrayInputStream(le), maxBytes = 200))
        assertEquals(body, ImportBounds.readUtf8(ByteArrayInputStream(be), maxBytes = 200))
    }

    @Test
    fun rejectsUtf32() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0, 0x7B, 0, 0, 0)
        val be = byteArrayOf(0, 0, 0xFE.toByte(), 0xFF.toByte(), 0, 0, 0, 0x7B)
        assertThrows(java.io.IOException::class.java) {
            ImportBounds.readUtf8(ByteArrayInputStream(le), maxBytes = 100)
        }
        assertThrows(java.io.IOException::class.java) {
            ImportBounds.readUtf8(ByteArrayInputStream(be), maxBytes = 100)
        }
    }

    private class ChunkedStream(private val payload: ByteArray, private val chunk: Int) : InputStream() {
        var position = 0
            private set

        override fun read(): Int {
            if (position >= payload.size) return -1
            return payload[position++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= payload.size) return -1
            val n = minOf(len, chunk, payload.size - position)
            payload.copyInto(b, off, position, position + n)
            position += n
            return n
        }
    }
}
