package io.github.stardomains3.oxproxion

import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.URLSpan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A link tap opens the link. The same tap must not also open the action row.
 * The offset math matches LinkMovementMethod, including the empty tail of a line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class MessageTapTest {

    @Test fun plain_words_toggle_the_row() {
        assertTrue(MessageTap.togglesActions("hello", 0))
        assertTrue(MessageTap.togglesActions("hello", 4))
    }

    @Test fun a_link_does_not_toggle_the_row() {
        val text = SpannableString("see the link here")
        text.setSpan(URLSpan("https://example.com"), 8, 12, 0)
        assertTrue(MessageTap.togglesActions(text, 0))
        assertFalse(MessageTap.togglesActions(text, 8))
        assertFalse(MessageTap.togglesActions(text, 11))
        assertTrue(MessageTap.togglesActions(text, 13))
    }

    @Test fun a_tap_on_the_word_is_that_word() {
        val text = SpannableString("hello world")
        text.setSpan(URLSpan("https://example.com"), 6, 11, 0)
        val paint = TextPaint().apply { textSize = 20f }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 800).build()
        val hello = MessageTap.offsetAt(layout, layout.getPrimaryHorizontal(0), 1f, 0, 0, 0, 0)
        val world = MessageTap.offsetAt(layout, layout.getPrimaryHorizontal(6) + 1f, 1f, 0, 0, 0, 0)
        assertTrue(MessageTap.togglesActions(text, hello))
        assertFalse(MessageTap.togglesActions(text, world))
    }

    @Test fun the_empty_tail_of_a_line_lands_on_its_last_character() {
        val paint = TextPaint().apply { textSize = 20f }
        val layout = StaticLayout.Builder.obtain("hi", 0, 2, paint, 800).build()
        val past = MessageTap.offsetAt(layout, 700f, 1f, 0, 0, 0, 0)
        assertTrue(past <= 2)
        assertTrue(past >= 1)
    }
}
