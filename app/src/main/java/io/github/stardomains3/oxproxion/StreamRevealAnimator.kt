package io.github.stardomains3.oxproxion

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

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNs: Long) {
            if (!running) return
            val dtMs = if (lastFrameNs == 0L) 16f else ((frameTimeNs - lastFrameNs) / 1_000_000f).coerceIn(8f, 33f)
            lastFrameNs = frameTimeNs

            val backlog = target.length - shown
            if (backlog <= 0) {
                if (finishing) {
                    finishing = false
                    stop()
                    onCaughtUp()
                } else {
                    stop()
                }
                return
            }

            val fadeFrom = shown
            val chars = charsToReveal(backlog, dtMs, finishing)
            shown = (shown + chars).coerceAtMost(target.length)
            if (!finishing && shown < target.length) {
                shown = snapToWordEnd(target, shown)
            }
            onFrame(target.substring(0, shown), fadeFrom)

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
        if (!text.startsWith(target.take(shown.coerceAtMost(target.length)))) {
            shown = longestCommonPrefixLen(target.take(shown), text)
        }
        target = text
        if (shown > target.length) shown = target.length
        ensureRunning()
    }

    fun displayed(): String =
        if (shown <= 0) "" else target.substring(0, shown.coerceAtMost(target.length))

    fun finishFast() {
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
    }

    private fun ensureRunning() {
        if (running) return
        running = true
        lastFrameNs = 0L
        choreographer.postFrameCallback(callback)
    }

    private fun stop() {
        running = false
        choreographer.removeFrameCallback(callback)
    }

    /**
     * Ease toward the network: reveal about 1/24th of the backlog per frame (a ~0.4s time
     * constant), never less than a character. A big chunk from the model spreads into a steady
     * flow instead of landing as a block (the old curve dumped 1000 chars in four frames).
     */
    private fun charsToReveal(backlog: Int, dtMs: Float, finishing: Boolean): Int {
        val frames = dtMs / 16f
        val base = kotlin.math.ceil(backlog / 24f).toInt().coerceAtLeast(1)
        val scaled = (base * frames).toInt().coerceAtLeast(1)
        return if (finishing) maxOf(scaled * 3, backlog / 6, 12) else scaled
    }

    private fun snapToWordEnd(text: String, index: Int): Int {
        if (index <= 0 || index >= text.length) return index
        val c = text[index - 1]
        if (c.isWhitespace() || c == '\n') return index
        val nextBreak = text.indexOfAny(charArrayOf(' ', '\n', '\t', '.', ',', ';', ':', '!', '?'), index)
        return if (nextBreak in index until index + 12) nextBreak + 1 else index
    }

    private fun longestCommonPrefixLen(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }
}
