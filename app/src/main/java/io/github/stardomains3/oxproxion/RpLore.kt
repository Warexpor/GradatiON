package io.github.stardomains3.oxproxion

/**
 * Turns a lorebook's text into the slice that belongs in this prompt.
 *
 * Text before the first `[keys: …]` line is always included, so an old book that is one
 * block of prose still goes in whole. A block under `[keys: docks, grey haven]` is included
 * only when the recent scene mentions one of those keys. One extra pass picks up an entry
 * whose key appears only inside lore that already came in.
 */
object RpLore {
    private const val SCAN_CHARS = 4_000
    private val header = Regex("""(?m)^[ \t]*\[keys:[ \t]*(.*?)[ \t]*][ \t]*$""")

    data class Entry(val keys: List<String>, val text: String) {
        internal val matchers: List<KeyMatcher> by lazy { keys.map(::KeyMatcher) }
    }

    fun parse(content: String): List<Entry> {
        if (content.isBlank()) return emptyList()
        val marks = header.findAll(content).toList()
        if (marks.isEmpty()) {
            val text = content.trim()
            return if (text.isEmpty()) emptyList() else listOf(Entry(emptyList(), text))
        }
        val out = ArrayList<Entry>()
        val head = content.substring(0, marks.first().range.first).trim()
        if (head.isNotEmpty()) out += Entry(emptyList(), head)
        marks.forEachIndexed { i, mark ->
            val keys = mark.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val start = mark.range.last + 1
            val end = marks.getOrNull(i + 1)?.range?.first ?: content.length
            val text = content.substring(start, end).trim()
            if (text.isNotEmpty()) out += Entry(keys, text)
        }
        return out
    }

    /** Newest part of the scene, so a long chat does not keep ancient mentions alive. */
    fun scanOf(parts: List<String>, maxChars: Int = SCAN_CHARS): String {
        val text = parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        if (text.length <= maxChars) return text
        return text.takeLast(maxChars)
    }

    fun select(content: String, scan: String, maxChars: Int = 12_000): String {
        val entries = parse(content)
        if (entries.isEmpty()) return ""
        val picked = LinkedHashSet<Int>()
        fun take(haystack: String) {
            entries.forEachIndexed { i, entry ->
                if (i in picked) return@forEachIndexed
                if (entry.keys.isEmpty() || entry.matchers.any { it.hits(haystack) }) picked += i
            }
        }
        take(scan)
        val broughtIn = picked.joinToString("\n") { entries[it].text }
        if (broughtIn.isNotBlank()) take(broughtIn)

        val sb = StringBuilder()
        for (i in picked.sorted()) {
            val piece = entries[i].text.trim()
            if (piece.isEmpty()) continue
            val nextLen = if (sb.isEmpty()) piece.length else sb.length + 2 + piece.length
            if (nextLen > maxChars) {
                if (sb.isEmpty()) return piece
                break
            }
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(piece)
        }
        return sb.toString()
    }

    /**
     * A single word must sit on its own, so "dock" does not fire inside "docks", while
     * "Mira" still matches "Mira's". A phrase matches anywhere.
     */
    fun keyHits(key: String, haystack: String): Boolean = KeyMatcher(key).hits(haystack)

    /** Scripts written without spaces between words, where a key is always glued to its neighbours. */
    private val unspacedScripts = setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL, Character.UnicodeScript.THAI, Character.UnicodeScript.LAO,
        Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR
    )

    /** A key compiled once, so a scan does not rebuild its regex for every entry and every pass. */
    internal class KeyMatcher(key: String) {
        private val token = key.trim()
        private val escaped = Regex.escape(token)
        private val pattern: Regex? = when {
            token.isEmpty() -> null
            // A word boundary never exists inside unspaced text, so these keys match anywhere.
            token.any { it.isWhitespace() } || token.codePoints().anyMatch { Character.UnicodeScript.of(it) in unspacedScripts } ->
                Regex(escaped, RegexOption.IGNORE_CASE)
            else -> Regex("""(?<![\p{L}\p{N}])$escaped(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
        }

        fun hits(haystack: String) = pattern != null && haystack.isNotEmpty() && pattern.containsMatchIn(haystack)
    }
}
