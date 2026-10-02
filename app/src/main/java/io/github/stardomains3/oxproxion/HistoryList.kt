package io.github.stardomains3.oxproxion

import java.util.Calendar
import java.util.TimeZone

sealed class HistoryListItem {
    data class Header(val title: String) : HistoryListItem()
    data class Session(
        val session: ChatSession,
        val pinned: Boolean,
        /** One line under the title. Blank when the chat has no readable last message. */
        val preview: String = "",
        /** The chat currently on screen. */
        val open: Boolean = false,
        /** The live search, so the row can mark the matching words. Blank when not searching. */
        val query: String = "",
    ) : HistoryListItem()
}

/**
 * The History drawer: pinned chats first, then the rest by day. Week labels used to compare
 * [Calendar.WEEK_OF_YEAR], which is 1 for both early January and late December, so a chat from
 * the other end of the year showed as a weekday.
 */
object HistoryList {

    enum class Section { PINNED, TODAY, YESTERDAY, WEEK, EARLIER }

    enum class TimestampKind { TIME, WEEKDAY, MONTH_DAY, FULL }

    data class Labels(
        val pinned: String,
        val today: String,
        val yesterday: String,
        val week: String,
        val earlier: String,
    )

    private const val DAY_MS = 24L * 60 * 60 * 1000
    /** One preview line. Longer unsent text is cut, with the match kept in view. */
    private const val DRAFT_LINE = 160
    /**
     * A History row is one line. A match already in that window stays as written;
     * a later one is pulled forward so the bold word is not ellipsized off the end.
     */
    private const val LINE = 36
    private const val LEAD = 10
    /** Today plus the six days before it. Matches the Roleplay history page. */
    private const val WEEK_DAYS = 6

    fun startOfDay(nowMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val calendar = Calendar.getInstance(zone)
        calendar.timeInMillis = nowMillis
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    fun section(
        timestamp: Long,
        now: Long,
        pinned: Boolean,
        zone: TimeZone = TimeZone.getDefault(),
    ): Section {
        if (pinned) return Section.PINNED
        val start = startOfDay(now, zone)
        return when {
            timestamp >= start -> Section.TODAY
            timestamp >= start - DAY_MS -> Section.YESTERDAY
            timestamp >= start - WEEK_DAYS * DAY_MS -> Section.WEEK
            else -> Section.EARLIER
        }
    }

    /** Time for today and yesterday; the weekday for the rest of the week; the date after that. */
    fun timestampKind(
        timestamp: Long,
        now: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): TimestampKind {
        val start = startOfDay(now, zone)
        if (timestamp >= start - DAY_MS) return TimestampKind.TIME
        if (timestamp >= start - WEEK_DAYS * DAY_MS) return TimestampKind.WEEKDAY
        val nowCal = Calendar.getInstance(zone).apply { timeInMillis = now }
        val thenCal = Calendar.getInstance(zone).apply { timeInMillis = timestamp }
        return if (nowCal.get(Calendar.YEAR) == thenCal.get(Calendar.YEAR)) {
            TimestampKind.MONTH_DAY
        } else {
            TimestampKind.FULL
        }
    }

    fun build(
        sessions: List<ChatSession>,
        pinnedIds: Set<Long>,
        previews: Map<Long, String>,
        now: Long,
        labels: Labels,
        zone: TimeZone = TimeZone.getDefault(),
    ): List<HistoryListItem> {
        val grouped = sessions.groupBy { section(it.timestamp, now, it.id in pinnedIds, zone) }
        return buildList {
            for (section in Section.entries) {
                val rows = grouped[section].orEmpty().sortedByDescending { it.timestamp }
                if (rows.isEmpty()) continue
                add(HistoryListItem.Header(labels.of(section)))
                rows.forEach { session ->
                    add(HistoryListItem.Session(session, section == Section.PINNED, previews[session.id].orEmpty()))
                }
            }
        }
    }

    /**
     * One line for a history row, from the start of a stored message (the database cuts a long
     * one, so this has to survive a JSON string or array that does not close).
     * [youLabel] receives the folded text; a photo with no caption stays [photoLabel].
     */
    fun preview(role: String, storedPrefix: String, youLabel: (String) -> String, photoLabel: String): String {
        val raw = storedPrefix.trim()
        if (raw.isEmpty()) return ""
        val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        val text = when {
            // Not previewOf: that strips every underscore, so snake_case no longer matches.
            parsed != null -> fold(MessageContent.text(parsed))
            raw.startsWith("\"") -> fold(jsonStringPrefix(raw))
            else -> fold(textFieldPrefix(raw))
        }.takeUnless { it.startsWith("data:") || it.contains("base64,") }.orEmpty()
        val body = when {
            text.isNotBlank() -> text
            hasImage(raw) -> photoLabel
            else -> ""
        }
        if (body.isEmpty()) return ""
        return if (role == "user" && text.isNotBlank()) youLabel(body) else body
    }

    /**
     * Where to scroll so the open chat is on screen, including its section header when
     * that header sits directly above the row. -1 when no row is the open chat.
     */
    fun openAnchor(items: List<HistoryListItem>): Int {
        val index = items.indexOfFirst { it is HistoryListItem.Session && it.open }
        if (index <= 0) return index
        return if (items[index - 1] is HistoryListItem.Header) index - 1 else index
    }

    /**
     * The line under a title. An unsent draft replaces the last message, unless [query]
     * already hits that message: the search result stays, and a title-only hit still
     * shows the draft so the unsent line is not hidden.
     * The "You:" prefix is not the message. A search for that word used to hide the draft.
     */
    fun rowPreview(messageLine: String, draft: String, query: String, draftLabel: (String) -> String): String {
        val folded = foldSpace(draft)
        if (folded.isEmpty()) return messageLine
        val needle = foldSpace(query)
        if (needle.isNotEmpty() && matchesBeyondLabel(messageLine, needle)) return messageLine
        val body = if (needle.isEmpty() || !folded.contains(needle, ignoreCase = true)) {
            if (folded.length <= DRAFT_LINE) folded else folded.take(DRAFT_LINE).trimEnd() + "…"
        } else {
            clipAround(folded, folded.indexOf(needle, ignoreCase = true), needle.length)
        }
        return draftLabel(body)
    }

    /**
     * Saved chats whose unsent text contains [query]. The unsaved slot has no row.
     * A line break is a space, the same way the row draws the draft, so a search
     * for the words on that row still finds the chat.
     */
    fun draftMatchIds(drafts: Map<String, String>, query: String): Set<Long> {
        val needle = foldSpace(query)
        if (needle.isEmpty()) return emptySet()
        return drafts.mapNotNullTo(HashSet()) { (key, text) ->
            val id = key.toLongOrNull() ?: return@mapNotNullTo null
            if (id > 0L && foldSpace(text).contains(needle, ignoreCase = true)) id else null
        }
    }

    /**
     * Prefs drafts plus any richer host preview (a staged Photo/Audio/files line). The host
     * wins so a chat with only a picture still matches a search for that label. Pass a
     * [hostPreview] that already merges caption and attachment via [draftSearchText] when
     * both are waiting.
     */
    fun draftTextsForSearch(
        sessions: List<ChatSession>,
        prefsDrafts: Map<String, String>,
        hostPreview: (Long) -> String,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>(prefsDrafts.size + sessions.size)
        prefsDrafts.forEach { (k, v) -> if (v.isNotBlank()) out[k] = v }
        for (session in sessions) {
            val preview = hostPreview(session.id)
            if (preview.isNotBlank()) out[ComposerDrafts.key(session.id)] = preview
        }
        return out
    }

    /**
     * Search haystack for one chat's draft. A caption alone, or an attachment label alone,
     * stays as written. When both are waiting, both words are kept so "Photo" still finds
     * a draft that also has typed text (the row preview still shows only the caption).
     */
    fun draftSearchText(caption: String, attachmentLabel: String): String {
        val text = foldSpace(caption)
        val attach = foldSpace(attachmentLabel)
        if (attach.isEmpty()) return text
        if (text.isEmpty()) return attach
        if (text.contains(attach, ignoreCase = true)) return text
        return "$text $attach"
    }

    /**
     * Draft line under a History title while searching. The caption alone stays when it already
     * contains [query], or when you are not searching for the attachment. When the hit is only
     * the Photo / Audio / files label, both are shown so the bold span has somewhere to land.
     */
    fun draftRowText(caption: String, attachmentLabel: String, query: String): String {
        val needle = foldSpace(query)
        val text = foldSpace(caption)
        val attach = foldSpace(attachmentLabel)
        if (needle.isEmpty()) return text.ifEmpty { attach }
        if (attach.isEmpty()) return text
        if (text.isEmpty()) return attach
        if (text.contains(needle, ignoreCase = true)) return text
        if (!attach.contains(needle, ignoreCase = true)) return text
        if (text.contains(attach, ignoreCase = true)) return text
        return "$text $attach"
    }

    /** [matched] plus chats the database search missed because the words are only in a draft. */
    fun withDraftMatches(
        matched: List<ChatSession>,
        all: List<ChatSession>,
        drafts: Map<String, String>,
        query: String,
    ): List<ChatSession> {
        val ids = draftMatchIds(drafts, query)
        if (ids.isEmpty()) return matched
        val have = matched.mapTo(HashSet()) { it.id }
        val extra = all.filter { it.id in ids && it.id !in have }
        return if (extra.isEmpty()) matched else matched + extra
    }

    /** Marks [openId] and carries [query] onto each row so a search rebinds the highlight. */
    fun present(items: List<HistoryListItem>, openId: Long?, query: String): List<HistoryListItem> {
        val q = query.trim()
        return items.map { item ->
            if (item is HistoryListItem.Session) {
                item.copy(open = openId != null && item.session.id == openId, query = q)
            } else {
                item
            }
        }
    }

    /**
     * One line for a search hit. [window] is a short slice of the stored message around the
     * match, and may start mid-JSON. A clean parse wins; otherwise the readable text around
     * [query]. Blank when the slice has no readable match (a title hit, or a word inside a photo).
     */
    fun searchLine(
        role: String,
        window: String,
        query: String,
        youLabel: (String) -> String,
        photoLabel: String,
    ): String {
        // Same folding as draft rows and the bold span: extra spaces and line breaks
        // in the query still match the words on the line.
        val needle = foldSpace(query)
        if (needle.isEmpty()) return ""
        val parsed = preview(role, window, youLabel, photoLabel)
        // A slice of a photo's data URL is one long token. It is not a line of the chat.
        // A line with no spaces still is: a link, or Chinese, Japanese or Korean.
        // "You:" is added here. It is not a match for the word in that label.
        if (isChatLine(parsed) && matchesBeyondLabel(parsed, needle)) {
            return clipMatch(parsed, needle)
        }
        val readable = lineFor(readableSource(window), needle)
        if (!isChatLine(readable)) return ""
        val hit = emphasis(readable, needle) ?: return ""
        val body = clipAround(readable, hit.start, hit.length)
        return if (role == "user") youLabel(body) else body
    }

    /**
     * History line for an unsent attachment with no caption. A photo wins over audio and
     * files so a staged picture still reads as "Photo", matching a sent photo with no text.
     */
    fun attachmentDraft(
        hasPhoto: Boolean,
        hasAudio: Boolean,
        fileCount: Int,
        photoLabel: String,
        audioLabel: String,
        filesLabel: (Int) -> String,
    ): String {
        if (hasPhoto) return photoLabel
        if (hasAudio) return audioLabel
        if (fileCount > 0) return filesLabel(fileCount)
        return ""
    }

    /** Trim and collapse whitespace, the same way a draft row and the bold span do. */
    fun normalizeQuery(query: String): String = foldSpace(query)

    /**
     * LIKE pattern for History search. Runs of spaces become `%` so a query of
     * "see you" still hits a stored line that has a newline or extra spaces between
     * those words, matching [draftMatchIds].
     */
    fun likeContains(query: String): String {
        val parts = foldSpace(query).split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "%"
        val escaped = parts.joinToString("%") { part ->
            part.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        }
        return "%$escaped%"
    }

    /**
     * The word used to center a search window. The full phrase may span a newline in
     * storage, so [instr] uses the first word.
     */
    fun searchAnchor(query: String): String {
        val needle = foldSpace(query)
        val first = needle.substringBefore(' ')
        return first.ifEmpty { needle }
    }

    /** Start and length of [query] in [text], after folding runs of spaces the same way a draft row does. */
    data class Emphasis(val start: Int, val length: Int)

    /**
     * Where to mark [query] in a row. Skips a short "You: " or "Draft: " lead when the
     * words after it also match, so the highlight lands on the message rather than the
     * prefix. A hit that is only that prefix is not a match: the label is not the line.
     * Runs of spaces in the query match any whitespace in the row, so a draft found by
     * [draftMatchIds] still gets a bold span.
     */
    fun emphasis(text: String, query: String): Emphasis? {
        val needle = foldSpace(query)
        if (needle.isEmpty() || text.isEmpty()) return null
        val labelEnd = historyLabelEnd(text)
        val bodyStart = if (labelEnd >= 0) labelEnd else 0
        findEmphasis(text, needle, bodyStart)?.let { return it }
        // The label is not the line. Only fall back to the whole string when there is no label.
        if (labelEnd >= 0) return null
        return findEmphasis(text, needle, 0)
    }

    fun emphasisAt(text: String, query: String): Int = emphasis(text, query)?.start ?: -1

    private fun findEmphasis(text: String, needle: String, from: Int): Emphasis? {
        if (from > text.length) return null
        val exact = text.indexOf(needle, startIndex = from, ignoreCase = true)
        if (exact >= 0) return Emphasis(exact, needle.length)
        val words = needle.split(' ').filter { it.isNotEmpty() }
        if (words.size < 2) return null
        val pattern = words.joinToString("\\s+") { Regex.escape(it) }
        val match = Regex(pattern, RegexOption.IGNORE_CASE).find(text, from) ?: return null
        return Emphasis(match.range.first, match.value.length)
    }

    /**
     * A sentence, a short token, or a line with no spaces that is still words.
     * A long run of the base64 alphabet is a slice of a photo, not a line.
     */
    private fun isChatLine(text: String): Boolean {
        val body = text.substringAfter(": ", text).trim()
        if (body.isEmpty()) return false
        if (body.length <= 40 || body.any { it.isWhitespace() }) return true
        return !isBase64Run(body)
    }

    private fun isBase64Run(text: String): Boolean {
        if (text.length <= 40) return false
        for (c in text) {
            val alphabet = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '='
            if (!alphabet) return false
        }
        return true
    }

    private fun readableSource(raw: String): String {
        val stripped = raw.replace(DATA_URL, " ")
        return unescape(stripped)
    }

    /** Folded markdown when that still contains [needle]; otherwise the characters the query needs. */
    private fun lineFor(text: String, needle: String): String {
        val folded = fold(text)
        if (needle.isEmpty() || folded.contains(needle, ignoreCase = true)) return folded
        val plain = WHITESPACE.replace(text, " ").trim()
        return if (plain.contains(needle, ignoreCase = true)) plain else folded
    }

    private fun unescape(raw: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    'n', 'r', 't' -> out.append(' ')
                    '"', '\\', '/' -> out.append(next)
                    'u' -> {
                        if (i + 5 >= raw.length) break
                        val code = raw.substring(i + 2, i + 6).toIntOrNull(16) ?: break
                        out.append(code.toChar())
                        i += 6
                        continue
                    }
                    else -> out.append(next)
                }
                i += 2
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** Spaces and line breaks are the same word break the row already draws. */
    private fun foldSpace(text: String): String = WHITESPACE.replace(text, " ").trim()

    /**
     * True when [needle] is in the message, not only in the "You:" or "Draft:" label
     * this list adds. Any other "Word:" is the message itself.
     */
    /** Index just after a "You: " or "Draft: " this list added, or -1 when the line has no such label. */
    private fun historyLabelEnd(text: String): Int {
        val colon = text.indexOf(": ")
        if (colon !in 0..8) return -1
        val label = text.substring(0, colon)
        if (!label.equals("You", ignoreCase = true) && !label.equals("Draft", ignoreCase = true)) return -1
        return colon + 2
    }

    private fun matchesBeyondLabel(text: String, needle: String): Boolean {
        if (needle.isEmpty()) return false
        val labelEnd = historyLabelEnd(text)
        val body = if (labelEnd >= 0) text.substring(labelEnd) else text
        // Fold the body too: a draft-style query of "see you" still hits "see\nyou".
        return foldSpace(body).contains(needle, ignoreCase = true)
    }

    private fun clipMatch(text: String, needle: String): String {
        val hit = emphasis(text, needle) ?: return text
        return clipAround(text, hit.start, hit.length)
    }

    private fun clipAround(text: String, at: Int, needleLen: Int): String {
        if (at < 0) return text
        if (at < LINE && text.length <= LINE + LEAD) return text.trim()
        val start = (at - LEAD).coerceAtLeast(0)
        val end = (at + needleLen + LINE).coerceAtMost(text.length)
        var snippet = text.substring(start, end).trim()
        if (start > 0) snippet = "…$snippet"
        if (end < text.length) snippet = "$snippet…"
        return snippet
    }

    private fun Labels.of(section: Section): String = when (section) {
        Section.PINNED -> pinned
        Section.TODAY -> today
        Section.YESTERDAY -> yesterday
        Section.WEEK -> week
        Section.EARLIER -> earlier
    }

    private fun hasImage(raw: String): Boolean =
        raw.contains("\"type\":\"image_url\"") || raw.contains("\"type\": \"image_url\"")

    /** The value of the first `"text":"..."` field, as far as the prefix goes. */
    private fun textFieldPrefix(raw: String): String {
        val key = "\"text\":\""
        val at = raw.indexOf(key)
        if (at < 0) return ""
        return jsonStringPrefix(raw.substring(at + key.length - 1))
    }

    /** Decodes a JSON string that may be missing its closing quote. */
    private fun jsonStringPrefix(raw: String): String {
        if (raw.length < 2 || raw[0] != '"') return ""
        val out = StringBuilder()
        var i = 1
        while (i < raw.length) {
            val c = raw[i]
            if (c == '"') break
            if (c == '\\') {
                if (i + 1 >= raw.length) break
                when (val next = raw[i + 1]) {
                    '"', '\\', '/' -> out.append(next)
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'u' -> {
                        if (i + 5 >= raw.length) return out.toString()
                        val code = raw.substring(i + 2, i + 6).toIntOrNull(16) ?: return out.toString()
                        out.append(code.toChar())
                        i += 6
                        continue
                    }
                    else -> out.append(next)
                }
                i += 2
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /**
     * Markdown marks come off for the preview. An underscore inside a word stays:
     * stripping every `_` turned a search for `snake_case` into a line that no longer
     * contained it, so the row could not show why it matched.
     */
    private fun fold(text: String): String =
        WHITESPACE.replace(
            MD_EDGE_UNDERSCORE.replace(MD_STARS.replace(text, ""), "").replace(MD_MARKS, ""),
            " ",
        ).trim()

    private val DATA_URL = Regex("data:[^\"\\s]*;base64,[A-Za-z0-9+/=]+")
    private val MD_STARS = Regex("\\*+")
    private val MD_MARKS = Regex("[#>`~]")
    private val MD_EDGE_UNDERSCORE = Regex("(?<![A-Za-z0-9])_|_(?![A-Za-z0-9])")
    private val WHITESPACE = Regex("\\s+")

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
}
