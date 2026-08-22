package io.github.stardomains3.oxproxion

object RpReplyCleaner {
    private val thinkRegex = Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL)
    private val leakRegex = Regex(
        """(?i)(^(Format:|Response Format:|##\s*Response Format|Always respond in English|DO NOT output|INSTRUCTIONS?|Never break character|THE ONLY TABOOS|No restrictions|No filters|No limits|IMPORTANT:|Answer IMMEDIATELY).*$)""",
        RegexOption.MULTILINE
    )

    fun clean(text: String): String {
        var out = thinkRegex.replace(text, "")
        out = leakRegex.replace(out, "")
        return out.trim()
    }
}
