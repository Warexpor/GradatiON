package io.github.stardomains3.oxproxion

import java.io.IOException

/**
 * Reads a chat message without asking Android for the whole row at once.
 * The cursor window is about 2MB and stores strings as UTF-16, so one long attachment
 * (the composer allows a 1MB file) used to throw SQLiteBlobTooBigException on open and on export.
 * SQLite's length() and substr() count Unicode characters, which is the unit used here.
 */
internal object ChatMessageText {
    /** One read stays well under a 1MB cursor window, including the other columns. */
    const val SAFE_CHARS = 200_000
    const val SLICE_CHARS = 100_000

    /** Tests shrink these so a short string still takes the sliced path. Null in production. */
    @androidx.annotation.VisibleForTesting
    var safeCharsForTest: Int? = null

    @androidx.annotation.VisibleForTesting
    var sliceCharsForTest: Int? = null

    private fun safeChars() = safeCharsForTest ?: SAFE_CHARS
    private fun sliceChars() = sliceCharsForTest ?: SLICE_CHARS

    suspend fun read(
        sqliteLength: Long,
        full: suspend () -> String,
        slice: suspend (startInclusive: Int, length: Int) -> String
    ): String {
        if (sqliteLength <= 0L) return ""
        if (sqliteLength > Int.MAX_VALUE) {
            throw IOException("Chat message is too long to read ($sqliteLength characters)")
        }
        val length = sqliteLength.toInt()
        if (length <= safeChars()) return full()
        val sliceLen = sliceChars().coerceAtLeast(1)
        return buildString {
            var start = 1
            while (start <= length) {
                val part = slice(start, sliceLen)
                val expected = minOf(sliceLen, length - start + 1)
                // A short slice must not advance by the full step: that skips the gap and the
                // export then writes a message with a hole in it.
                if (part.length < expected) {
                    throw IOException("Chat message ended early at character $start of $length")
                }
                append(part)
                start += sliceLen
            }
        }
    }
}
