package io.github.stardomains3.oxproxion

/**
 * Turns a lorebook's text into the slice that belongs in this prompt.
 *
 * Text before the first `[keys: …]` line is always included, so an old book that is one
 * block of prose still goes in whole. A block under `[keys: docks, grey haven]` is included
 * only when the scan mentions one of those keys. Entries keep pulling each other until
 * nothing new matches, so a chain of three still arrives together.
 */
object RpLore {
    private const val SCAN_CHARS = 4_000
    /** Name, scenario, memory and facts stay in the scan even when the chat is long. */
    private const val PIN_CHARS = 1_500
    private val header = Regex("""(?m)^[ \t]*\[keys:[ \t]*(.*?)[ \t]*][ \t]*$""")
    private val keySplit = Regex("[,;、]")

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
            val keys = mark.groupValues[1].split(keySplit).map { it.trim() }.filter { it.isNotEmpty() }
            val start = mark.range.last + 1
            val end = marks.getOrNull(i + 1)?.range?.first ?: content.length
            val text = content.substring(start, end).trim()
            if (text.isNotEmpty()) out += Entry(keys, text)
        }
        return out
    }

    /**
     * Newest part of the scene, so a long chat does not keep ancient mentions alive.
     * The cut moves forward to a word break: a key sliced in half would miss, and the
     * leftover letters could match a shorter key.
     */
    fun scanOf(parts: List<String>, maxChars: Int = SCAN_CHARS): String {
        if (maxChars <= 0) return ""
        val text = parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        if (text.length <= maxChars) return text
        val start = text.length - maxChars
        if (text[start - 1].isWhitespace()) return text.substring(start).trimStart()
        var i = start
        while (i < text.length && !text[i].isWhitespace()) i++
        if (i >= text.length) return text.substring(start)
        return text.substring(i).trimStart()
    }

    /**
     * [pinned] is the part that must survive a long chat: the character's name and scenario,
     * who the user is, the Memory note, and this chat's facts. [recent] is the conversation,
     * newest last, and fills whatever of [maxChars] the pin did not use.
     */
    fun sceneScan(pinned: List<String>, recent: List<String>, maxChars: Int = SCAN_CHARS): String {
        if (maxChars <= 0) return ""
        val pin = clipTail(join(pinned), minOf(PIN_CHARS, maxChars))
        val room = maxChars - pin.length - if (pin.isEmpty()) 0 else 1
        val scene = if (room <= 0) "" else scanOf(recent, room)
        return when {
            pin.isEmpty() -> scene
            scene.isEmpty() -> pin
            else -> pin + "\n" + scene
        }
    }

    /** Keep the start. Step back to a line, then a word, so a pinned fact is not cut in half. */
    fun clipTail(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        val cut = text.take(maxChars)
        val line = cut.lastIndexOf('\n')
        if (line > maxChars / 2) return cut.take(line).trimEnd()
        val word = cut.lastIndexOf(' ')
        if (word > maxChars / 2) return cut.take(word).trimEnd()
        return cut.trimEnd()
    }

    private fun join(parts: List<String>) =
        parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

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
        // A key that lives only inside an entry that just came in still gets its block.
        // Stop when a pass adds nothing; the set cannot grow past the book.
        var guard = entries.size
        while (guard-- > 0) {
            val before = picked.size
            val broughtIn = picked.joinToString("\n") { entries[it].text }
            if (broughtIn.isBlank()) break
            take(broughtIn)
            if (picked.size == before) break
        }

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
