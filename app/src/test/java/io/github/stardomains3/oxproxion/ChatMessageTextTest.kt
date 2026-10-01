package io.github.stardomains3.oxproxion

import kotlinx.coroutines.runBlocking
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
}
