package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMessageFoldTest {
    @Test fun a_short_message_is_left_alone() {
        assertFalse(UserMessageFold.isLong("hello", maxChars = 150))
        assertEquals("hello", UserMessageFold.collapse("hello", maxChars = 150))
    }

    @Test fun a_long_line_cuts_on_a_word() {
        val text = "word ".repeat(40).trim()
        assertTrue(UserMessageFold.isLong(text, maxChars = 30))
        val cut = UserMessageFold.collapse(text, maxChars = 30)
        assertTrue(cut.endsWith("…"))
        assertFalse(cut.contains("continued"))
        assertTrue(cut.length < text.length)
        assertFalse(cut.dropLast(1).endsWith(" "))
    }

    @Test fun extra_lines_keep_the_first_three() {
        val text = "one\ntwo\nthree\nfour"
        assertEquals("one\ntwo\nthree…", UserMessageFold.collapse(text, maxChars = 500))
    }

    @Test fun many_short_lines_still_stop_at_three() {
        val text = (1..80).joinToString("\n") { "hi" }
        assertTrue(text.length > 150)
        assertEquals("hi\nhi\nhi…", UserMessageFold.collapse(text, maxChars = 150))
    }
}
