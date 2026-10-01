package io.github.stardomains3.oxproxion

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Caps how much of a backup is read. The old path loaded the whole stream into a string and only
 * then checked the size, so a huge file was already in memory when the check failed.
 */
internal object ImportBounds {
    const val MAX_TEXT_BYTES = 5 * 1024 * 1024

    /** Roleplay backups embed portrait JPEGs, so they are allowed more than a chat transcript. */
    const val MAX_RP_BYTES = 16 * 1024 * 1024

    class TooLarge(val limitBytes: Int) : IOException("import larger than $limitBytes bytes")

    fun readUtf8(input: InputStream, maxBytes: Int = MAX_TEXT_BYTES): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) throw IOException("import read returned no bytes")
            if (total > maxBytes - n) throw TooLarge(maxBytes)
            out.write(buf, 0, n)
            total += n
        }
        return decode(out.toByteArray())
    }

    /**
     * UTF-8, or UTF-16 when the file starts with a BOM. Notepad's "Unicode" is UTF-16 LE, and
     * that used to fail as a broken JSON file.
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            // FF FE 00 00 is UTF-32 LE. Reading that as UTF-16 would look like a valid, empty file.
            if (bytes.size >= 4 && bytes[2] == 0.toByte() && bytes[3] == 0.toByte()) {
                throw IOException("UTF-32 backups are not supported")
            }
            return String(bytes.copyOfRange(2, bytes.size), Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes.copyOfRange(2, bytes.size), Charsets.UTF_16BE)
        }
        return bytes.toString(Charsets.UTF_8)
    }
}
