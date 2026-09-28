package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The streaming splitter must agree with a full scan, including when the reply grows a
 * character at a time. A mismatch would move a paragraph across the cached/uncached cut.
 */
class MarkdownBlockBoundaryTest {

    @Test fun matchesAFullScanAsTheReplyGrows() {
        val samples = listOf(
            "Hello",
            "Hello\n",
            "Hello\n\nWorld",
            "Hello\n\nWorld\n",
            "Para one.\n\nPara two.\n\nPara three, still open",
            "List:\n\n- one\n- two\n\nAfter",
            "```\ncode\n```\n\nAfter the fence",
            "```kotlin\nfun a()\n```\n\nDone",
            "~~~\ncode\n~~~\n\nAfter",
            "```\nnot closed yet\n\nstill code",
            "Before\n\n```\ninside\n\nblank\n```\n\nAfter",
            "    indented\n\nflush",
            "\n\nstarts blank\n\nthen text",
            "A\n\n    continuation stays with the block\n\nNext",
            "``` \n",
            "text\n\n```kotlin extra\nline\n```\n\n",
        )
        for (full in samples) {
            val fresh = MarkdownBlockBoundary()
            assertEquals(full, reference(full), fresh.boundary(full))
            val growing = MarkdownBlockBoundary()
            val buf = StringBuilder()
            for (ch in full) {
                buf.append(ch)
                val text = buf.toString()
                assertEquals(text, reference(text), growing.boundary(text))
            }
        }
    }

    @Test fun aShorterStringRescansInsteadOfKeepingAStaleCut() {
        val b = MarkdownBlockBoundary()
        val long = "One\n\nTwo\n\nThree"
        assertEquals(reference(long), b.boundary(long))
        val short = "One\n\nTwo"
        assertEquals(reference(short), b.boundary(short))
    }

    /** The previous line-by-line scan, kept here so a faster scan cannot drift. */
    private fun reference(text: String): Int {
        var inFence = false
        var fenceMarker = ""
        var lastBoundary = 0
        var lineStart = 0
        var prevLineBlank = false
        while (lineStart < text.length) {
            val nl = text.indexOf('\n', lineStart)
            if (nl < 0) break
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
}
