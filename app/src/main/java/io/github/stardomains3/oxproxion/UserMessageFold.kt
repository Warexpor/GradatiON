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

    /** [text] when it fits; otherwise the head, cut on a word when length is what trips it. */
    fun collapse(text: String, maxChars: Int): String {
        if (!isLong(text, maxChars)) return text
        if (text.length > maxChars) {
            val head = text.take(maxChars)
            val cut = head.lastIndexOf(' ')
            val end = if (cut > 0) cut else maxChars
            return text.take(end).trimEnd() + "…"
        }
        return text.lineSequence().take(MAX_LINES).joinToString("\n").trimEnd() + "…"
    }
}
