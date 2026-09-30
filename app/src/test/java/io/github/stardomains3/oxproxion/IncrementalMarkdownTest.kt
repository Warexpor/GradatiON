package io.github.stardomains3.oxproxion

import android.text.SpannableStringBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Streaming must not redo work on closed blocks: each one is polished once, as it closes, and
 * a frame that only grows the open tail polishes the tail alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class IncrementalMarkdownTest {

    private class Recorder {
        /** The `from` offset of every polish pass, in order. */
        val passes = mutableListOf<Int>()
        val md = IncrementalMarkdown({ SpannableStringBuilder(it) }, polish = { _, from -> passes += from })
    }

    @Test fun aFrameThatOnlyGrowsTheTailPolishesOnlyTheTail() {
        val r = Recorder()
        r.md.render("One\n\nTw")
        val tailStart = r.md.openTailStart
        assertTrue("the first block should have closed", tailStart > 0)

        r.passes.clear()
        for (frame in listOf("One\n\nTwo", "One\n\nTwo w", "One\n\nTwo wo", "One\n\nTwo wor")) {
            r.md.render(frame)
            assertEquals("the stable block must stay put", tailStart, r.md.openTailStart)
        }

        assertEquals("one tail pass per frame", 4, r.passes.size)
        // The pass starts one character back, to see the gap between the stable text and the tail;
        // anything earlier would mean a closed block is being polished again.
        assertTrue(r.passes.toString(), r.passes.all { it >= tailStart - 1 })
    }

    @Test fun aBlockThatClosesIsPolishedOnceAndThenLeftAlone() {
        val r = Recorder()
        r.md.render("One\n\nTwo\n\nTh")
        val afterTwoClosed = r.md.openTailStart
        r.passes.clear()

        r.md.render("One\n\nTwo\n\nThr")
        r.md.render("One\n\nTwo\n\nThre")
        assertEquals(2, r.passes.size)
        assertTrue(r.passes.toString(), r.passes.all { it >= afterTwoClosed - 1 })
    }

    @Test fun resetStartsOverFromTheTop() {
        val r = Recorder()
        r.md.render("One\n\nTwo")
        val out = r.md.render("Different\n\nText")
        assertTrue(r.md.didReset)
        // The closed block, the gap the renderer puts between blocks, then the open tail.
        assertEquals("Different\n\n\n\nText", out.toString())
    }
}
