package io.github.stardomains3.oxproxion

/**
 * Turns a lorebook's text into the slice that belongs in this prompt.
 *
 * Text before the first `[keys: …]` line is always included, so an old book that is one
 * block of prose still goes in whole. A block under `[keys: docks, grey haven]` is included
 * only when the scan mentions one of those keys. A key written as {{char}} or {{user}} is
 * the person in this chat. Entries keep pulling each other until nothing new matches, so a
 * chain of three still arrives together. A long always-on block is cut so a block that
 * actually matched still fits.
 */
object RpLore {
    private const val SCAN_CHARS = 4_000
    /** Name, scenario and the other card text, plus Memory and facts, stay even when the chat is long. */
    private const val PIN_CHARS = 1_500
    /**
     * When the card text is long enough to fill the pin, Memory and facts still keep this much
     * between them. A short card gives them whatever is left instead.
     */
    private const val NOTES_CHARS = 800
    /** The reply a rewrite is changing, so its keys still match after the chat has moved on. */
    private const val FOCUS_CHARS = 1_500
    /** ASCII or fullwidth brackets and colon, so a header typed either way still splits. */
    private val header = Regex("""(?m)^[ \t]*[\[［][ \t]*keys[ \t]*[:：][ \t]*(.*?)[ \t]*[\]］][ \t]*$""")
    private val keySplit = Regex("[,;、，|]")
    private val wrappingQuotes = setOf('"', '\'', '“', '”', '‘', '’', '「', '」', '『', '』', '«', '»', '‹', '›', '„', '‚', '《', '》', '〈', '〉', '｢', '｣', '〝', '〞', '〟', '❝', '❞', '❛', '❜', '﹁', '﹂', '﹃', '﹄', '〔', '〕', '〖', '〗', '＂', '＇', '❮', '❯', '｟', '｠', '〘', '〙', '⟨', '⟩', '❰', '❱', '〚', '〛', '⟪', '⟫', '⟬', '⟭')

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
            val keys = mark.groupValues[1].split(keySplit).map(::cleanKey).filter { it.isNotEmpty() }
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
     * [pinned] is the card: name, scenario and the rest, kept from the start so the setting
     * survives a long chat. [notes] is the Memory note and this chat's facts. They share the
     * pin, and a long Memory note cannot use it all, so a fact still matches. [focus] is a
     * beat that has to match even when [recent] (newest last) would have pushed it out — the
     * reply a rewrite is changing. [recent] fills whatever of [maxChars] is left.
     */
    fun sceneScan(
        pinned: List<String>,
        recent: List<String>,
        maxChars: Int = SCAN_CHARS,
        focus: List<String> = emptyList(),
        notes: List<String> = emptyList()
    ): String {
        if (maxChars <= 0) return ""
        val pinBudget = minOf(PIN_CHARS, maxChars)
        val noteParts = notes.map { it.trim() }.filter { it.isNotEmpty() }
        val noteCap = minOf(NOTES_CHARS, pinBudget / 2)
        val noteNeed = if (noteParts.isEmpty()) 0 else minOf(joinedLen(noteParts), noteCap)
        val reserveSep = if (noteNeed > 0) 1 else 0
        val identity = clipTail(join(pinned), (pinBudget - noteNeed - reserveSep).coerceAtLeast(0))
        val notesRoom = (pinBudget - identity.length - if (identity.isEmpty() || noteParts.isEmpty()) 0 else 1)
            .coerceAtLeast(0)
        val noteText = if (noteParts.isEmpty()) "" else share(noteParts, notesRoom)
        val pin = when {
            identity.isEmpty() -> noteText
            noteText.isEmpty() -> identity
            else -> identity + "\n" + noteText
        }
        var room = maxChars - pin.length - if (pin.isEmpty()) 0 else 1
        val focusText = if (room <= 0) "" else share(focus, minOf(FOCUS_CHARS, room))
        room -= focusText.length + if (focusText.isEmpty()) 0 else 1
        val scene = if (room <= 0) "" else scanOf(recent, room)
        return listOf(pin, focusText, scene).filter { it.isNotEmpty() }.joinToString("\n")
    }

    /**
     * Give every part a turn at [budget]. A short part takes only what it needs and the
     * spare goes to the longer ones, so the second note is not dropped just because the
     * first is long. Each part keeps its start, cut on a line when it has to be cut.
     */
    fun share(parts: List<String>, budget: Int): String {
        val items = parts.map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty() || budget <= 0) return ""
        if (items.size == 1) return clipTail(items[0], budget)
        val seps = items.size - 1
        if (budget <= seps) return clipTail(items.first(), budget)
        var left = budget - seps
        val caps = IntArray(items.size)
        val open = items.indices.toMutableList()
        while (open.isNotEmpty() && left > 0) {
            val shareSize = left / open.size
            val extra = left % open.size
            val finished = ArrayList<Int>()
            var consumed = 0
            open.forEachIndexed { n, i ->
                val grant = shareSize + if (n < extra) 1 else 0
                if (grant > 0 && items[i].length <= grant) {
                    caps[i] = items[i].length
                    finished += i
                    consumed += items[i].length
                }
            }
            if (finished.isEmpty()) {
                open.forEachIndexed { n, i ->
                    caps[i] = shareSize + if (n < extra) 1 else 0
                }
                break
            }
            open.removeAll(finished.toSet())
            left -= consumed
        }
        return items.mapIndexed { i, text -> clipTail(text, caps[i]) }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun joinedLen(parts: List<String>): Int =
        parts.sumOf { it.length } + (parts.size - 1).coerceAtLeast(0)

    /** A period or comma stuck on the end of a key is not part of the word. */
    private val keyTrail = setOf('.', ',', ';', ':', '!', '?', '。', '，', '、', '…', '؟', '۔', '।', '॥', '։', '၊', '။', '׃', '።', ';', '།', '។', '՜', '՞', '؛', '．', '‽', '჻', '᙮', '෴', '܀', '܁', '܂', '᠃', '‼', '⁇', '⁈', '⁉', '៕', 'ฯ', '⳹', '⳾', '⸮', '᥄', '᥅', '꓿', '꘎', '꘏', '｡', '﹒', '፧', '፨', '߹', '᱾', '᱿', '꛳', '꛷', '︒', '﹗', '﹖', '︖', '︕', '꩝', '꩞', '꩟', '᭞', '᭟', '᠅', '᰻', '᰼', '༎', '༏', '༔', '꫰', '꫱', '꣎', '꣏', '꧉', '꡶', '꡷', '꥟', '᨞', '᨟', '᯼', '᯽', '᯾', '᯿', '᛫', '᛬', '᛭', '࡞', '⵰', '࠹', '࠾', '꧈', '꧋', '꧞', '꧟')

    /** Quotes around a key are not part of the word, so `"locket"` still matches locket. */
    private fun cleanKey(raw: String): String {
        var k = raw.trim()
        if (k.length >= 2 && k.first() in wrappingQuotes && k.last() in wrappingQuotes) {
            k = k.substring(1, k.length - 1).trim()
        }
        while (k.length > 1 && k.last() in keyTrail) k = k.dropLast(1).trimEnd()
        return k
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

    /** A blank name leaves its placeholder, so a key is not wiped when that person has no name yet. */
    private fun expandNames(text: String, charName: String, userName: String): String =
        RpPromptEngine.expandKnownMacros(text, charName, userName)

    private fun expandKeys(entry: Entry, charName: String, userName: String): Entry {
        if (entry.keys.isEmpty() || (charName.isBlank() && userName.isBlank())) return entry
        val keys = entry.keys.map { key ->
            expandNames(key, charName, userName).trim().ifEmpty { key }
        }
        return if (keys == entry.keys) entry else entry.copy(keys = keys)
    }

    /**
     * [charName] and [userName] expand {{char}} and {{user}} inside keys, and inside an
     * entry that just came in, so the next key in the chain still matches. The text that
     * is returned keeps the placeholders; the prompt fills those in.
     */
    fun select(
        content: String,
        scan: String,
        maxChars: Int = 12_000,
        charName: String = "",
        userName: String = "",
    ): String {
        val entries = parse(content).map { expandKeys(it, charName, userName) }
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
            val broughtIn = picked.joinToString("\n") { expandNames(entries[it].text, charName, userName) }
            if (broughtIn.isBlank()) break
            take(broughtIn)
            if (picked.size == before) break
        }

        return fitPicked(entries, picked.sorted(), maxChars)
    }

    /**
     * Book order, within [maxChars]. A matched block is kept even when the always-on preface
     * is longer than the budget: the preface is cut to the room the matches leave, and a short
     * preface is kept beside a match that has to be cut.
     */
    private fun fitPicked(entries: List<Entry>, order: List<Int>, maxChars: Int): String {
        if (maxChars <= 0) return ""
        val texts = order.map { it to entries[it].text.trim() }.filter { it.second.isNotEmpty() }
        if (texts.isEmpty()) return ""
        val open = texts.filter { entries[it.first].keys.isEmpty() }
        val keyed = texts.filter { entries[it.first].keys.isNotEmpty() }
        val openRaw = joinedLen(open.map { it.second })
        val reserve = if (open.isEmpty() || keyed.isEmpty()) 0 else minOf(openRaw, maxChars / 3)
        val reserveSep = if (reserve > 0) 2 else 0
        val kept = LinkedHashMap<Int, String>()
        var keyedLen = 0
        var keyedCount = 0
        val keyedBudget = (maxChars - reserve - reserveSep).coerceAtLeast(0)
        for ((i, text) in keyed) {
            val add = text.length + if (keyedCount == 0) 0 else 2
            if (keyedLen + add > keyedBudget) {
                if (keyedCount == 0 && keyedBudget > 0) {
                    val piece = clipTail(text, keyedBudget)
                    if (piece.isNotEmpty()) {
                        kept[i] = piece
                        keyedLen = piece.length
                    }
                }
                break
            }
            kept[i] = text
            keyedLen += add
            keyedCount++
        }
        val gap = if (kept.isNotEmpty() && open.isNotEmpty()) 2 else 0
        var openRoom = (maxChars - keyedLen - gap).coerceAtLeast(0)
        var openCount = 0
        for ((i, text) in open) {
            if (openRoom <= 0) break
            val sep = if (openCount == 0) 0 else 2
            val room = openRoom - sep
            if (room <= 0) break
            val piece = if (text.length <= room) text else clipTail(text, room)
            if (piece.isEmpty()) break
            kept[i] = piece
            openRoom -= sep + piece.length
            openCount++
            if (piece.length < text.length) break
        }
        return texts.mapNotNull { (i, _) -> kept[i] }.joinToString("\n\n")
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
        Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR,
        Character.UnicodeScript.TIBETAN, Character.UnicodeScript.ETHIOPIC
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
