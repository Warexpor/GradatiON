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

    @Test fun cardPreviewKeepsHeadWhenAsked() {
        val lines = (1..50).joinToString("\n") { "L$it" }
        assertEquals("L1\nL2\nL3", ToolOutputText.cardPreview(lines, maxLines = 3, head = true))
    }

    @Test fun clipKeepsHeadForReadsAndTailForShell() {
        val text = "START" + "x".repeat(20) + "END"
        val head = ToolOutputText.clip("read", text, 8)
        val tail = ToolOutputText.clip("execute", text, 8)
        assertTrue(head.startsWith("START"))
        assertTrue(head.endsWith("…"))
        assertTrue(tail.endsWith("END"))
        assertTrue(tail.startsWith("…"))
        assertEquals("short", ToolOutputText.clip("read", "short", 8))
    }

    @Test fun keptEndDistinguishesHeadAndTail() {
        assertEquals(ToolOutputText.KeptEnd.TAIL, ToolOutputText.keptEnd("…tail"))
        assertEquals(ToolOutputText.KeptEnd.HEAD, ToolOutputText.keptEnd("head…"))
        assertEquals(ToolOutputText.KeptEnd.NONE, ToolOutputText.keptEnd("whole"))
    }

    @Test fun phoneTailTruncatedDetectsEllipsis() {
        assertTrue(ToolOutputText.isPhoneTailTruncated("…" + "x".repeat(10)))
        assertTrue(ToolOutputText.isPhoneTailTruncated("..." + "tail"))
        assertFalse(ToolOutputText.isPhoneTailTruncated("ok\nlog"))
        assertFalse(ToolOutputText.isPhoneTailTruncated(""))
    }
}
