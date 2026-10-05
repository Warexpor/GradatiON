package io.github.stardomains3.oxproxion

/**
 * The brackets models wrap an echoed scene note or rewrite note in. One table instead of a
 * constant per shape: a model that answers in `【…】` or `⟦…⟧` is stripped like `(…)`.
 */
internal object RpBrackets {
    /** Open and close, two characters per pair. All in the BMP, so a pair is two chars. */
    private const val PAIRS =
        "()（）[]［］【】{}｛｝〔〕〖〗〈〉《》｟｠〘〙⟨⟩❰❱〚〛⟪⟫⟬⟭⟦⟧⦃⦄❨❩❪❫❬❭❲❳❴❵⦅⦆⦗⦘⦇⦈⦉⦊⧼⧽"

    val pairs: List<Pair<Char, Char>> = PAIRS.chunked(2).map { it[0] to it[1] }

    private val closeFor: Map<Char, Char> = pairs.toMap()

    /** Fullwidth parens, squares and braces nest with their ASCII twins. */
    private val family = mapOf('（' to '(', '）' to ')', '［' to '[', '］' to ']', '｛' to '{', '｝' to '}')

    fun closeOf(open: Char): Char? = closeFor[open]

    /**
     * Index of the closer that matches the bracket at the start of [text], or -1 when it never
     * closes or [text] does not start with one. ASCII and fullwidth twins count as one pair.
     */
    fun matchingClose(text: String): Int {
        val open = family[text.firstOrNull() ?: return -1] ?: text.first()
        val close = closeFor[open] ?: return -1
        var depth = 0
        for (i in text.indices) {
            val c = family[text[i]] ?: text[i]
            if (c == open) depth++
            else if (c == close) {
                depth--
                if (depth == 0) return i
            }
        }
        return -1
    }
}
