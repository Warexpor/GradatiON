package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.ToolOutputText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOutputTextTest {

    @Test fun cardPreviewKeepsTail() {
        val lines = (1..50).joinToString("\n") { "L$it" }
        val preview = ToolOutputText.cardPreview(lines, maxLines = 3)
        assertEquals("L48\nL49\nL50", preview)
    }

    @Test fun cardPreviewShortOutputUnchanged() {
        assertEquals("a\nb", ToolOutputText.cardPreview("a\nb", maxLines = 40))
    }

    @Test fun filterLinesBlankQueryReturnsAll() {
        val src = "alpha\nbeta\ngamma"
        assertEquals(src, ToolOutputText.filterLines(src, ""))
        assertEquals(src, ToolOutputText.filterLines(src, "   "))
    }

    @Test fun filterLinesCaseInsensitive() {
        val src = "Foo BAR\nbaz\nfoo again"
        assertEquals("Foo BAR\nfoo again", ToolOutputText.filterLines(src, "foo"))
        assertEquals("Foo BAR", ToolOutputText.filterLines(src, "BAR"))
    }

    @Test fun filterLinesNoMatchIsEmpty() {
        assertEquals("", ToolOutputText.filterLines("one\ntwo", "zzz"))
    }

    @Test fun phoneTailTruncatedDetectsEllipsis() {
        assertTrue(ToolOutputText.isPhoneTailTruncated("…" + "x".repeat(10)))
        assertTrue(ToolOutputText.isPhoneTailTruncated("..." + "tail"))
        assertFalse(ToolOutputText.isPhoneTailTruncated("ok\nlog"))
        assertFalse(ToolOutputText.isPhoneTailTruncated(""))
    }
}
