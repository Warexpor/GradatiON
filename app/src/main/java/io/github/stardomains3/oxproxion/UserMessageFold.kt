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

    /**
     * Fold state and the action row share one key. The [copy] index is how many
     * identical rows sit above this one, so two sends of the same line stay apart.
     * A content hash used to be the whole key, and opening one row opened the other.
     */
    fun rowKey(text: String, imageUri: String?, copy: Int): String =
        text.hashCode().toString() + ":" + (imageUri ?: "") + ":" + copy

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
            // [String.take] counts UTF-16 units. A limit on the first half of an emoji
            // left a broken character before the ellipsis.
            val head = clipChars(limited, maxChars)
            val cut = maxOf(head.lastIndexOf(' '), head.lastIndexOf('\n'))
            // A space in the first half is the word before a long token (a link). Breaking
            // there used to leave "See…" and hide the rest of the line.
            val end = if (cut > 0 && cut >= maxChars / 2) cut else head.length
            return limited.substring(0, end).trimEnd() + "…"
        }
        return if (moreLines) limited.trimEnd() + "…" else text
    }

    /** Cut without ending on a high surrogate, the same way Speak and the hub tagline do. */
    private fun clipChars(text: String, limit: Int): String {
        if (text.length <= limit) return text
        var end = limit
        if (end > 0 && text[end - 1].isHighSurrogate()) end--
        return text.substring(0, end)
    }
}
