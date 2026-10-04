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

    @Test fun two_copies_of_the_same_message_do_not_share_a_row() {
        assertEquals(
            UserMessageFold.rowKey("same line", null, 0),
            UserMessageFold.rowKey("same line", null, 0),
        )
        assertTrue(
            UserMessageFold.rowKey("same line", null, 0) !=
                UserMessageFold.rowKey("same line", null, 1)
        )
        assertTrue(
            UserMessageFold.rowKey("same line", "file://a", 0) !=
                UserMessageFold.rowKey("same line", null, 0)
        )
    }

    @Test fun two_copies_of_a_long_message_fold_on_their_own() {
        // The predicate is "same text as the row being folded", the way the bubble uses it.
        val copies = setOf(0, 2)
        fun sameAs(target: Int): (Int) -> Boolean = { i -> i in copies && target in copies }
        assertEquals(0, UserMessageFold.earlierCopies(0, sameAs(0)))
        assertEquals(0, UserMessageFold.earlierCopies(1, sameAs(1)))
        assertEquals(1, UserMessageFold.earlierCopies(2, sameAs(2)))
    }

    @Test fun a_trailing_newline_is_not_another_line() {
        val text = "one\ntwo\nthree\n"
        assertFalse(UserMessageFold.isLong(text, maxChars = 500))
        assertEquals(text, UserMessageFold.collapse(text, maxChars = 500))
    }

    @Test fun a_long_token_after_a_short_word_keeps_the_window() {
        val text = "See " + "https://example.com/" + "a".repeat(200)
        val cut = UserMessageFold.collapse(text, maxChars = 80)
        assertTrue(cut.endsWith("…"))
        assertTrue(cut.startsWith("See https://"))
        assertTrue(cut.length > 20)
    }

    @Test fun many_short_lines_still_stop_at_three() {
        val text = (1..80).joinToString("\n") { "hi" }
        assertTrue(text.length > 150)
        assertEquals("hi\nhi\nhi…", UserMessageFold.collapse(text, maxChars = 150))
    }

    @Test fun a_fold_cut_does_not_leave_half_an_emoji() {
        // "😀" is two UTF-16 units. A limit of 10 lands on its high surrogate.
        val text = "a".repeat(9) + "😀" + " and more words so this folds"
        val cut = UserMessageFold.collapse(text, maxChars = 10)
        assertTrue(cut.endsWith("…"))
        assertFalse(cut.dropLast(1).any { it.isHighSurrogate() })
        assertEquals("aaaaaaaaa…", cut)
    }
}
