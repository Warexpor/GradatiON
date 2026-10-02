package io.github.stardomains3.oxproxion

object RpReplyCleaner {
    private val thinkRegex = Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL)
    /** A reply cut off mid-thought: the tag opened and never closed, so everything after it is reasoning. */
    private val openThinkRegex = Regex("""<think>.*""", RegexOption.DOT_MATCHES_ALL)
    // Only lines that read as the prompt echoed back. Story prose that happens to start with "No limits,"
    // or "Instructions were clear" must survive, so labels need their colon and the short phrases must
    // be the whole line.
    private val leakRegex = Regex(
        """(?i)^[ \t]*(?:(?:Response Format|Format|Instructions?|IMPORTANT|THE ONLY TABOOS)[ \t]*:|##[ \t]*Response Format\b|(?:Always respond in English|DO NOT output|Answer IMMEDIATELY)\b)[^\n]*$|^[ \t]*(?:Never break character|No restrictions|No filters|No limits)[ \t]*[.!:]?[ \t]*$""",
        RegexOption.MULTILINE
    )
    private val blankRuns = Regex("""\n{3,}""")
    /** A label some models put above a Rewrite. Only the opening line, and only that shape. */
    private val rewriteLabel = Regex(
        """(?i)^[ \t]*(?:here(?:'s| is) the (?:rewritten|new) (?:reply|version)[ \t]*:?|rewritten version[ \t]*:?)[ \t]*(?:\n+|$)"""
    )
    /**
     * A whole reply wrapped in a plain fence (or text/markdown). A fenced program stays, because
     * that fence has a language. Local models often wrap the story this way.
     */
    private val proseFence = Regex(
        """^```(?:text|markdown|md)?[ \t]*\r?\n(.*)\r?\n```\s*$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    fun clean(text: String): String {
        var out = unwrapProseFence(text.trim())
        out = RpPromptEngine.withoutLeadingSceneNote(out)
        out = thinkRegex.replace(out, "")
        out = openThinkRegex.replace(out, "")
        // Some providers drop the opening tag and send only the closing one.
        if (out.contains("</think>")) out = out.substringAfterLast("</think>")
        // The rewrite note is several lines. A single-line match used to leave the rest of it
        // in the bubble, so the story opened with the instruction.
        out = stripLeadingRewrite(out)
        out = leakRegex.replace(out, "")
        // A stripped line leaves its blank neighbours behind.
        out = blankRuns.replace(out, "\n\n")
        return out.trim()
    }

    /**
     * Drop a leading `(OOC: … Rewrite your last reply …)` (ASCII or fullwidth parens) even when the note wraps, then a
     * "here's the rewritten reply" label. An OOC line that is not that note stays: it can be
     * the character talking.
     */
    private fun stripLeadingRewrite(text: String): String {
        var out = text.trimStart()
        repeat(3) {
            val next = rewriteLabel.replace(stripRewriteOoc(out).trimStart(), "").trimStart()
            if (next == out) return out
            out = next
        }
        return out
    }

    private fun stripRewriteOoc(text: String): String {
        // ASCII or fullwidth paren/colon: some models echo the note that way.
        if (!text.startsWith("(OOC:", ignoreCase = true) &&
            !text.startsWith("（OOC:", ignoreCase = true) &&
            !text.startsWith("(OOC：", ignoreCase = true) &&
            !text.startsWith("（OOC：", ignoreCase = true)
        ) return text
        val close = closingParen(text)
        if (close < 0) return text
        val block = text.substring(0, close + 1)
        if (!block.contains("Rewrite your last reply", ignoreCase = true)) return text
        return text.substring(close + 1)
    }

    /** Index of the `)` / `）` that closes the open paren at the start, or -1 when it never closes. */
    private fun closingParen(text: String): Int {
        var depth = 0
        for (i in text.indices) {
            when (text[i]) {
                '(', '（' -> depth++
                ')', '）' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun unwrapProseFence(text: String): String {
        val inner = proseFence.matchEntire(text)?.groupValues?.get(1)?.trim().orEmpty()
        return inner.ifEmpty { text }
    }
}
