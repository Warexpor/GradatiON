package io.github.stardomains3.oxproxion

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import io.noties.markwon.Markwon

/**
 * Renders a growing markdown string for streaming without re-parsing the whole reply every
 * frame. Text is split at the last blank line that sits outside a code fence: everything
 * before it is a run of closed blocks, rendered once and cached; only the open tail block is
 * parsed per frame. The final bind still renders the whole message in one pass, so any
 * cross-block nuance (loose list spacing, reference links) settles when the stream ends.
 *
 * [polish] styles a rendered run from an offset on. Closed blocks are polished once, as they
 * close; per frame only the open tail is, so the cost of a frame does not grow with the reply.
 */
internal class IncrementalMarkdown(
    private val parse: (String) -> Spanned,
    private val preprocess: (String) -> String = { it },
    private val polish: (Spannable, Int) -> Unit = { _, _ -> }
) {
    constructor(
        markwon: Markwon,
        preprocess: (String) -> String = { it },
        polish: (Spannable, Int) -> Unit = { _, _ -> }
    ) : this({ markwon.toMarkdown(it) }, preprocess, polish)

    private var stableSource = ""
    private val stableRendered = SpannableStringBuilder()
    private val blocks = MarkdownBlockBoundary()

    /**
     * After the last [render], index in the returned spannable where the open (unstable) tail
     * begins — equal to [stableRendered] length, so closed blocks sit at `[0, openTailStart)`.
     */
    var openTailStart: Int = 0
        private set

    /** True if the last [render] called [reset] because the source no longer extended prior stable text. */
    var didReset: Boolean = false
        private set

    fun reset() {
        stableSource = ""
        stableRendered.clear()
        stableRendered.clearSpans()
        openTailStart = 0
        blocks.reset()
    }

    fun render(text: String): SpannableStringBuilder {
        didReset = false
        if (!text.startsWith(stableSource)) {
            reset()
            didReset = true
        }
        val boundary = blocks.boundary(text)
        if (boundary > stableSource.length) {
            appendStable(text.substring(stableSource.length, boundary))
            stableSource = text.substring(0, boundary)
        }
        val tail = text.substring(stableSource.length)
        val out = SpannableStringBuilder(stableRendered)
        openTailStart = out.length
        if (tail.isNotBlank()) {
            val rendered = parse(preprocess(tail))
            if (rendered.isNotEmpty()) {
                val from = seamStart(out)
                if (out.isNotEmpty()) out.append(BLOCK_GAP)
                out.append(rendered)
                polish(out, from)
            }
        }
        return out
    }

    private fun appendStable(chunk: String) {
        if (chunk.isBlank()) return
        val rendered = parse(preprocess(chunk))
        if (rendered.isEmpty()) return
        val from = seamStart(stableRendered)
        if (stableRendered.isNotEmpty()) stableRendered.append(BLOCK_GAP)
        stableRendered.append(rendered)
        polish(stableRendered, from)
    }

    /** Where a pass over newly appended text starts: one back, so the gap it forms with the text before is seen. */
    private fun seamStart(text: CharSequence): Int = (text.length - 1).coerceAtLeast(0)

    private companion object {
        const val BLOCK_GAP = "\n\n"
    }
}

/**
 * Index just past the last blank line that is not inside a fenced code block, or 0.
 *
 * Scans with indexes and resumes after the last complete line, so a growing reply does not
 * allocate a string per line on every frame. A shorter string than the one already scanned
 * starts over; the cut matches a full scan.
 */
internal class MarkdownBlockBoundary {
    private var scanAt = 0
    private var inFence = false
    private var fenceChar = '\u0000'
    private var prevBlank = false
    private var boundary = 0

    fun reset() {
        scanAt = 0
        inFence = false
        fenceChar = '\u0000'
        prevBlank = false
        boundary = 0
    }

    fun boundary(text: String): Int {
        if (scanAt > text.length) reset()
        var lineStart = scanAt
        while (lineStart < text.length) {
            val nl = text.indexOf('\n', lineStart)
            if (nl < 0) break // last line is still being written
            noteFence(text, lineStart, nl)
            val blank = isBlank(text, lineStart, nl)
            // A blank line after content, outside a fence, closes the previous block. Indented
            // continuation (lists, indented code) keeps its block open, so only split when the
            // next line starts flush. If that next character is not written yet, stay on this
            // line and decide once it arrives — a full rescan would do the same.
            if (blank && !inFence && !prevBlank && lineStart > 0) {
                val next = nl + 1
                if (next >= text.length) break
                if (!text[next].isWhitespace()) boundary = next
            }
            prevBlank = blank
            lineStart = nl + 1
        }
        scanAt = lineStart
        return boundary
    }

    private fun noteFence(text: String, start: Int, end: Int) {
        var i = start
        while (i < end && text[i].isWhitespace()) i++
        if (end - i < 3) return
        val c = text[i]
        if ((c != '`' && c != '~') || text[i + 1] != c || text[i + 2] != c) return
        if (!inFence) {
            inFence = true
            fenceChar = c
        } else if (c == fenceChar && closingRun(text, i, end, c)) {
            inFence = false
        }
    }

    /** True when the trimmed line is nothing but [c], the way a closing fence is. */
    private fun closingRun(text: String, start: Int, end: Int, c: Char): Boolean {
        var j = end
        while (j > start && text[j - 1].isWhitespace()) j--
        for (k in start until j) if (text[k] != c) return false
        return j > start
    }

    private fun isBlank(text: String, start: Int, end: Int): Boolean {
        for (i in start until end) if (!text[i].isWhitespace()) return false
        return true
    }
}
