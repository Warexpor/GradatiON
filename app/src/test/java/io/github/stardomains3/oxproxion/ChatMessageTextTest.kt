package io.github.stardomains3.oxproxion

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * A short read must fail the message, not skip ahead and write a hole into an export.
 */
class ChatMessageTextTest {

    @Test
    fun aShortSliceDoesNotSkipTheRest() = runBlocking {
        ChatMessageText.safeCharsForTest = 1
        ChatMessageText.sliceCharsForTest = 4
        try {
            ChatMessageText.read(
                sqliteLength = 8,
                full = { error("full read") },
                slice = { start, _ -> if (start == 1) "ab" else "cdef" },
            )
            fail("a short slice should fail the read")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("ended early"))
        } finally {
            ChatMessageText.safeCharsForTest = null
            ChatMessageText.sliceCharsForTest = null
        }
    }

    @Test
    fun aShortEmojiSliceDoesNotSkipTheRest() = runBlocking {
        ChatMessageText.safeCharsForTest = 1
        ChatMessageText.sliceCharsForTest = 4
        // One emoji is one SQLite character and two UTF-16 units. Two of them used to
        // satisfy a 4-character slice, and the next step skipped the gap.
        val emoji = "\uD83D\uDE00"
        try {
            ChatMessageText.read(
                sqliteLength = 8,
                full = { error("full read") },
                slice = { start, _ -> if (start == 1) emoji + emoji else "WXYZ" },
            )
            fail("a short emoji slice should fail the read")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("ended early"))
        } finally {
            ChatMessageText.safeCharsForTest = null
            ChatMessageText.sliceCharsForTest = null
        }
    }

    @Test
    fun anEmojiSliceIsCountedInCodePoints() = runBlocking {
        ChatMessageText.safeCharsForTest = 1
        ChatMessageText.sliceCharsForTest = 2
        val emoji = "\uD83D\uDE00"
        try {
            val text = ChatMessageText.read(
                sqliteLength = 4,
                full = { error("full read") },
                slice = { _, _ -> emoji + emoji },
            )
            assertEquals(emoji + emoji + emoji + emoji, text)
        } finally {
            ChatMessageText.safeCharsForTest = null
            ChatMessageText.sliceCharsForTest = null
        }
    }

    @Test
    fun aLongSliceDoesNotRepeatTheOverlap() = runBlocking {
        ChatMessageText.safeCharsForTest = 1
        ChatMessageText.sliceCharsForTest = 2
        val emoji = "\uD83D\uDE00"
        try {
            val text = ChatMessageText.read(
                sqliteLength = 4,
                full = { error("full read") },
                // Four emoji is longer than the two-character step. The extra used to be
                // kept, and the next step wrote them again.
                slice = { _, _ -> emoji + emoji + emoji + emoji },
            )
            assertEquals(emoji + emoji + emoji + emoji, text)
        } finally {
            ChatMessageText.safeCharsForTest = null
            ChatMessageText.sliceCharsForTest = null
        }
    }
}
