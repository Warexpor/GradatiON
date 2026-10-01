package io.github.stardomains3.oxproxion

/**
 * A long message you sent is cut until you ask for the rest. The cutoff used to be a
 * hardcoded English "continued", and the control lived in the action row that stays
 * hidden until the bubble is tapped.
 */
object UserMessageFold {
    const val MAX_LINES = 3

    fun isLong(text: String, maxChars: Int): Boolean {
        // A trailing newline is not another line of the message. Counting it folded a
        // three-line note and added an ellipsis that hid nothing.
        val body = text.trimEnd()
        return body.length > maxChars || body.lineSequence().count() > MAX_LINES
    }

    /** How many earlier rows are the same message, so two copies fold on their own. */
    fun earlierCopies(index: Int, same: (Int) -> Boolean): Int {
        var count = 0
        var i = 0
        while (i < index) {
            if (same(i)) count++
            i++
        }
        return count
    }

    /**
     * [text] when it fits. Otherwise the first [MAX_LINES] lines, and within that a word
     * (or a line end) when the characters are what still overflow. Length used to win
     * first, so a stack of short lines was cut mid-list and could stay far past three lines.
     */
    fun collapse(text: String, maxChars: Int): String {
        val body = text.trimEnd()
        if (!isLong(body, maxChars)) return text
        val lines = body.lineSequence().iterator()
        val kept = StringBuilder()
        var count = 0
        while (count < MAX_LINES && lines.hasNext()) {
            if (count > 0) kept.append('\n')
            kept.append(lines.next())
            count++
        }
        val moreLines = lines.hasNext()
        val limited = kept.toString()
        if (limited.length > maxChars) {
            val head = limited.take(maxChars)
            val cut = maxOf(head.lastIndexOf(' '), head.lastIndexOf('\n'))
            // A space in the first half is the word before a long token (a link). Breaking
            // there used to leave "See…" and hide the rest of the line.
            val end = if (cut > 0 && cut >= maxChars / 2) cut else maxChars
            return limited.take(end).trimEnd() + "…"
        }
        return if (moreLines) limited.trimEnd() + "…" else text
    }
}
