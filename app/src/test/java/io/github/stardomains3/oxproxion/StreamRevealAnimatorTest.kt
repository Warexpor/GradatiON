package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRevealAnimatorTest {

    /** Mirrors [StreamRevealAnimator]'s frame loop on a fake clock. */
    private class PacingSimulator {
        val pacing = StreamRevealPacing.State()
        var target = ""
        var shown = 0
        var finishing = false
        private var lastFrameMs = 0L
        private var lastArrivalMs = 0L

        fun setTarget(text: String, nowMs: Long) {
            val growth = text.length - target.length
            target = text
            if (growth > 0) {
                StreamRevealPacing.noteTargetGrowth(pacing, growth, nowMs)
                lastArrivalMs = nowMs
            }
        }

        fun frame(nowMs: Long) {
            val dtMs = if (lastFrameMs == 0L) 16f else (nowMs - lastFrameMs).toFloat()
            lastFrameMs = nowMs
            val backlog = target.length - shown
            val out = StreamRevealPacing.charsForFrame(
                pacing,
                StreamRevealPacing.FrameInput(shown, target.length, dtMs, finishing, nowMs - lastArrivalMs)
            )
            pacing.revealCarry = out.revealCarry
            if (out.charsToReveal > 0) {
                shown = (shown + out.charsToReveal).coerceAtMost(target.length)
                if (shown < target.length &&
                    (finishing || backlog >= StreamRevealPacing.WORD_SNAP_BACKLOG_THRESHOLD)
                ) {
                    shown = StreamRevealPacing.snapToWordEnd(target, shown)
                }
            }
        }
    }

    @Test
    fun sparseTokensRevealSmoothlyWithoutLongStalls() {
        val sim = PacingSimulator()
        var t = 0L
        val history = mutableListOf<Int>()
        repeat(20) {
            t += 200
            sim.setTarget(sim.target + "abc ", t)
            repeat(12) {
                t += 16
                val before = sim.shown
                sim.frame(t)
                assertTrue("per-frame jump too large for slow input", sim.shown - before <= 2)
                history.add(sim.shown)
            }
            t += 8
        }
        // Past the warm-up, a slow stream should spread each token over its gap, not pop it.
        val steady = history.drop(history.size / 2)
        var longestStall = 0
        var run = 0
        for ((a, b) in steady.zipWithNext()) {
            run = if (b == a) run + 1 else 0
            longestStall = maxOf(longestStall, run)
        }
        assertTrue("stalled $longestStall frames in a row", longestStall <= 6)
        for (i in 1 until history.size) assertTrue(history[i] >= history[i - 1])
    }

    @Test
    fun pauseReleasesTheHeldTail() {
        val sim = PacingSimulator()
        var t = 0L
        repeat(10) {
            t += 60
            sim.setTarget(sim.target + "word ", t)
            repeat(4) { t += 16; sim.frame(t) }
        }
        repeat(40) { t += 16; sim.frame(t) }
        assertEquals("the last word stays hidden during a pause", sim.target.length, sim.shown)
    }

    @Test
    fun largeBurstCatchesUpQuickly() {
        val sim = PacingSimulator()
        val burst = "x".repeat(400)
        sim.setTarget(burst, 1L)
        var t = 1L
        while (sim.shown < burst.length && t < 1200) {
            t += 16
            sim.frame(t)
        }
        assertTrue("burst took too long: ${t}ms", t <= 900)
    }

    @Test
    fun finishingDrainsEverything() {
        val sim = PacingSimulator()
        sim.setTarget("The quick brown fox jumps over the lazy dog.", 1L)
        sim.finishing = true
        var t = 1L
        while (sim.shown < sim.target.length && t < 400) {
            t += 16
            sim.frame(t)
        }
        assertEquals(sim.target.length, sim.shown)
    }
}
