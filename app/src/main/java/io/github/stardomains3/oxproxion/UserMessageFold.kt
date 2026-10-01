package io.github.stardomains3.oxproxion

/**
 * A long message you sent is cut until you ask for the rest. The cutoff used to be a
 * hardcoded English "continued", and the control lived in the action row that stays
 * hidden until the bubble is tapped.
 */
object UserMessageFold {
    const val MAX_LINES = 3

    fun isLong(text: String, maxChars: Int): Boolean =
        text.length > maxChars || text.lineSequence().count() > MAX_LINES

    /**
     * [text] when it fits. Otherwise the first [MAX_LINES] lines, and within that a word
     * (or a line end) when the characters are what still overflow. Length used to win
     * first, so a stack of short lines was cut mid-list and could stay far past three lines.
     */
    fun collapse(text: String, maxChars: Int): String {
        if (!isLong(text, maxChars)) return text
        val lines = text.lineSequence().iterator()
        val kept = StringBuilder()
        var count = 0
        while (count < MAX_LINES && lines.hasNext()) {
            if (count > 0) kept.append('\n')
            kept.append(lines.next())
            count++
        }
        val moreLines = lines.hasNext()
        var limited = kept.toString()
        if (limited.length > maxChars) {
            val head = limited.take(maxChars)
            val cut = maxOf(head.lastIndexOf(' '), head.lastIndexOf('\n'))
            val end = if (cut > 0) cut else maxChars
            return limited.take(end).trimEnd() + "…"
        }
        return if (moreLines) limited.trimEnd() + "…" else text
    }
}
