package io.github.stardomains3.oxproxion

import android.text.SpannableStringBuilder
import io.noties.markwon.Markwon

/**
 * Renders a growing markdown string for streaming without re-parsing the whole reply every
 * frame. Text is split at the last blank line that sits outside a code fence: everything
 * before it is a run of closed blocks, rendered once and cached; only the open tail block is
 * parsed per frame. The final bind still renders the whole message in one pass, so any
 * cross-block nuance (loose list spacing, reference links) settles when the stream ends.
 */
internal class IncrementalMarkdown(private val markwon: Markwon) {
    private var stableSource = ""
    private val stableRendered = SpannableStringBuilder()

    fun reset() {
        stableSource = ""
        stableRendered.clear()
        stableRendered.clearSpans()
    }

    fun render(text: String): CharSequence {
        if (!text.startsWith(stableSource)) reset()
        val boundary = stableBoundary(text)
        if (boundary > stableSource.length) {
            appendStable(text.substring(stableSource.length, boundary))
            stableSource = text.substring(0, boundary)
        }
        val tail = text.substring(stableSource.length)
        val out = SpannableStringBuilder(stableRendered)
        if (tail.isNotBlank()) {
            val rendered = markwon.toMarkdown(tail)
            if (rendered.isNotEmpty()) {
                if (out.isNotEmpty()) out.append(BLOCK_GAP)
                out.append(rendered)
            }
        }
        return out
    }

    private fun appendStable(chunk: String) {
        if (chunk.isBlank()) return
        val rendered = markwon.toMarkdown(chunk)
        if (rendered.isEmpty()) return
        if (stableRendered.isNotEmpty()) stableRendered.append(BLOCK_GAP)
        stableRendered.append(rendered)
    }

    /** Index just past the last blank line that is not inside a fenced code block, or 0. */
    private fun stableBoundary(text: String): Int {
        var inFence = false
        var fenceMarker = ""
        var lastBoundary = 0
        var lineStart = 0
        var prevLineBlank = false
        while (lineStart < text.length) {
            val nl = text.indexOf('\n', lineStart)
            if (nl < 0) break // last line is still being written
            val line = text.substring(lineStart, nl)
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val marker = trimmed.take(3)
                if (!inFence) {
                    inFence = true
                    fenceMarker = marker
                } else if (marker == fenceMarker && trimmed.trimEnd().all { it == marker[0] }) {
                    inFence = false
                }
            }
            val blank = line.isBlank()
            // A blank line after content, outside a fence, closes the previous block. Indented
            // continuation (lists, indented code) keeps its block open, so only split when the
            // next line starts flush.
            if (blank && !inFence && !prevLineBlank && lineStart > 0) {
                val next = nl + 1
                if (next < text.length && !text[next].isWhitespace()) {
                    lastBoundary = next
                }
            }
            prevLineBlank = blank
            lineStart = nl + 1
        }
        return lastBoundary
    }

    private companion object {
        const val BLOCK_GAP = "\n\n"
    }
}
