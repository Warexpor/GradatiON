package io.github.stardomains3.oxproxion

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class TitleMarkdownTest {

    private fun styles(s: CharSequence) =
        (s as Spanned).getSpans(0, s.length, StyleSpan::class.java).map { it.style to s.substring(s.getSpanStart(it), s.getSpanEnd(it)) }

    @Test fun plainTitlesPassThrough() {
        assertEquals("Trip to Rome", TitleMarkdown.render("  Trip to Rome ").toString())
    }

    @Test fun boldAndItalicLoseTheirMarkers() {
        val r = TitleMarkdown.render("**Big** plan and *quiet* tea")
        assertEquals("Big plan and quiet tea", r.toString())
        assertEquals(listOf(Typeface.BOLD to "Big", Typeface.ITALIC to "quiet"), styles(r))
    }

    @Test fun snakeCaseAndUnpairedMarkersStayAsTyped() {
        assertEquals("my_var_name", TitleMarkdown.render("my_var_name").toString())
        assertEquals("2 * 3 = 6", TitleMarkdown.render("2 * 3 = 6").toString())
    }

    @Test fun headingAndListMarkersAreDropped() {
        assertEquals("Ideas", TitleMarkdown.render("## Ideas").toString())
        assertEquals("Ideas", TitleMarkdown.render("- Ideas").toString())
    }

    @Test fun codeAndNestedEmphasis() {
        assertEquals("run ls now", TitleMarkdown.render("run `ls` now").toString())
        assertEquals("both", TitleMarkdown.render("**_both_**").toString())
    }
}
