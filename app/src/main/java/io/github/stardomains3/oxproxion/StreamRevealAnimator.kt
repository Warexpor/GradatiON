package io.github.stardomains3.oxproxion

import android.os.SystemClock
import android.view.Choreographer

/**
 * Real-speed SSE target + visual write.
 * [onFrame] gets (displayedText, fadeFromIndex) so the UI can fade-in only the new tail
 * (Streamdown/Perplexity/Grok-style), not throttle the network.
 */
class StreamRevealAnimator(
    private val onFrame: (displayed: String, fadeFrom: Int) -> Unit,
    private val onCaughtUp: () -> Unit
) {
    private val choreographer = Choreographer.getInstance()
    private var target: String = ""
    private var shown: Int = 0
    private var finishing: Boolean = false
    private var running: Boolean = false
    private var lastFrameNs: Long = 0L
    private var lastSetTargetMs: Long = 0L
    private val pacing = StreamRevealPacing.State()

    /**
     * Animations are off for this stream: text shows the moment it arrives, with no pacing and no
     * frame loop. The owner reads the setting once per stream and sets this before the first word.
     */
    var instant: Boolean = false

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNs: Long) {
            if (!running) return
            val nowMs = frameTimeNs / 1_000_000L
            val dtMs = if (lastFrameNs == 0L) {
                16f
            } else {
                ((frameTimeNs - lastFrameNs) / 1_000_000f).coerceIn(
                    StreamRevealPacing.FRAME_DT_MIN_MS,
                    StreamRevealPacing.FRAME_DT_MAX_MS
                )
            }
            lastFrameNs = frameTimeNs

            val backlog = target.length - shown
            if (backlog <= 0) {
                if (finishing) {
                    finishing = false
                    stop()
                    onCaughtUp()
                } else if (nowMs - lastSetTargetMs >= StreamRevealPacing.IDLE_STOP_MS) {
                    stop()
                } else {
                    choreographer.postFrameCallback(this)
                }
                return
            }

            val fadeFrom = shown
            val backlogBefore = backlog
            val out = StreamRevealPacing.charsForFrame(
                pacing,
                StreamRevealPacing.FrameInput(
                    shown, target.length, dtMs, finishing, nowMs - lastSetTargetMs
                )
            )
            pacing.revealCarry = out.revealCarry
            if (out.charsToReveal > 0) {
                shown = (shown + out.charsToReveal).coerceAtMost(target.length)
                if (shown < target.length &&
                    (finishing || backlogBefore >= StreamRevealPacing.WORD_SNAP_BACKLOG_THRESHOLD)
                ) {
                    shown = StreamRevealPacing.snapToWordEnd(target, shown)
                }
                onFrame(target.substring(0, shown), fadeFrom)
            }

            if (shown >= target.length && finishing) {
                finishing = false
                stop()
                onCaughtUp()
            } else {
                choreographer.postFrameCallback(this)
            }
        }
    }

    fun setTarget(text: String) {
        if (text == target) return
        val held = shown.coerceAtMost(target.length)
        if (held > 0 && !text.regionMatches(0, target, 0, held)) {
            shown = longestCommonPrefixLen(target, text, held)
        }
        val growth = text.length - target.length
        val nowMs = SystemClock.uptimeMillis()
        target = text
        if (shown > target.length) shown = target.length
        if (growth > 0) {
            StreamRevealPacing.noteTargetGrowth(pacing, growth, nowMs)
            lastSetTargetMs = nowMs
        }
        if (instant) {
            revealAllNow()
            return
        }
        ensureRunning()
    }

    private fun revealAllNow() {
        if (shown >= target.length) return
        val from = shown
        shown = target.length
        onFrame(target, from)
    }

    /** [text] is already on screen (a reply that keeps growing): reveal only what comes after it. */
    fun seed(text: String) {
        stop()
        target = text
        shown = text.length
        finishing = false
        pacing.reset()
        lastSetTargetMs = SystemClock.uptimeMillis()
    }

    fun displayed(): String =
        if (shown <= 0) "" else target.substring(0, shown.coerceAtMost(target.length))

    fun finishFast() {
        if (instant) {
            stop()
            revealAllNow()
            finishing = false
            onCaughtUp()
            return
        }
        finishing = true
        ensureRunning()
    }

    fun snapToEnd() {
        shown = target.length
        finishing = false
        stop()
        if (target.isNotEmpty()) onFrame(target, target.length)
    }

    fun reset() {
        stop()
        target = ""
        shown = 0
        finishing = false
        lastFrameNs = 0L
        lastSetTargetMs = 0L
        pacing.reset()
    }

    private fun ensureRunning() {
        if (running) return
        running = true
        choreographer.postFrameCallback(callback)
    }

    private fun stop() {
        running = false
        choreographer.removeFrameCallback(callback)
    }

    private fun longestCommonPrefixLen(a: String, b: String, limit: Int = minOf(a.length, b.length)): Int {
        val n = minOf(limit, a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }
}
