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
            parsed != null -> RpChatSummaries.previewOf(raw)
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

    private fun fold(text: String): String =
        text.replace(Regex("[*_#>`~]"), "").replace(Regex("\\s+"), " ").trim()

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
}
