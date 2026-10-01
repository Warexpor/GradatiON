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
        var bytes = out.toByteArray()
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            bytes = bytes.copyOfRange(3, bytes.size)
        }
        return bytes.toString(Charsets.UTF_8)
    }
}
