package io.github.stardomains3.oxproxion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.noties.markwon.Markwon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A code card paints its language name and padding into the text so the row has a height.
 * Copy and read-aloud have to skip that chrome and keep the code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class ChatMarkdownReadableTest {

    private fun render(markdown: String): CharSequence {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val markwon = Markwon.builder(context).usePlugin(ChatMarkdown.plugin(context)).build()
        return markwon.toMarkdown(markdown)
    }

    @Test fun a_reply_without_code_is_unchanged() {
        val rendered = render("Hello **there**")
        assertEquals(rendered.toString(), ChatMarkdown.readable(rendered))
        assertEquals("Hello there", ChatMarkdown.readable(rendered))
    }

    @Test fun a_code_card_keeps_the_code_and_drops_the_header() {
        val rendered = render("See this:\n\n```kotlin\nval n = 1\n```\n\nDone.")
        val plain = ChatMarkdown.readable(rendered)
        val raw = rendered.toString()
        assertTrue(raw.contains("kotlin"))
        assertFalse(plain.contains("kotlin"))
        assertTrue(plain.contains("val n = 1"))
        assertTrue(plain.contains("See this:"))
        assertTrue(plain.contains("Done."))
        assertFalse(plain.contains("\u00A0"))
    }

    @Test fun the_word_in_the_code_is_kept_once() {
        val rendered = render("```kotlin\nkotlin\n```")
        val plain = ChatMarkdown.readable(rendered)
        assertEquals(1, plain.split("kotlin").size - 1)
        assertTrue(plain.contains("kotlin"))
    }
}
