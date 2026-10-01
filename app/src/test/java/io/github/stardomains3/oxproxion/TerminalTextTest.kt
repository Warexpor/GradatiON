package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.TerminalText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TerminalTextTest {

    @Test fun plainTextIsLeftAlone() {
        val text = "hello\n\tworld"
        assertSame(text, TerminalText.readable(text))
    }

    @Test fun colorCursorAndTitleAreDropped() {
        val raw = "\u001b[32mok\u001b[0m \u001b[?25l\u001b]0;title\u0007done"
        assertEquals("ok done", TerminalText.readable(raw))
    }

    @Test fun carriageReturnRewritesTheLine() {
        assertEquals("Xello", TerminalText.readable("hello\rX"))
        assertEquals("Done", TerminalText.readable("Downloading\r\u001b[KDone"))
        assertEquals("", TerminalText.readable("hello\u001b[2K"))
    }

    @Test fun backspaceReplacesThePreviousCharacter() {
        assertEquals("abd", TerminalText.readable("abc\bd"))
    }

    @Test fun c1CsiIsColorToo() {
        assertEquals("ok", TerminalText.readable("\u009b31mok\u009b0m"))
    }

    @Test fun aSecondPassChangesNothing() {
        val once = TerminalText.readable("\u001b[31mhi\u001b[0m\rbye")
        assertEquals(once, TerminalText.readable(once))
        assertEquals("bye", once)
    }
}
