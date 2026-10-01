package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class HistoryListTest {

    private val zone = TimeZone.getTimeZone("UTC")
    private val labels = HistoryList.Labels(
        pinned = "Pinned",
        today = "Today",
        yesterday = "Yesterday",
        week = "This week",
        earlier = "Earlier",
    )

    /** Thursday 1 Oct 2026, 15:00 UTC. */
    private val now = utc(2026, 10, 1, 15)

    private fun utc(year: Int, month: Int, day: Int, hour: Int): Long {
        val calendar = Calendar.getInstance(zone)
        calendar.set(year, month - 1, day, hour, 0, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun session(id: Long, timestamp: Long, title: String = "chat $id") =
        ChatSession(id = id, title = title, modelUsed = "m", timestamp = timestamp)

    private fun titles(items: List<HistoryListItem>): List<String> = items.map { item ->
        when (item) {
            is HistoryListItem.Header -> item.title
            is HistoryListItem.Session -> item.session.title
        }
    }

    @Test fun days_group_under_today_yesterday_this_week_and_earlier() {
        val items = HistoryList.build(
            sessions = listOf(
                session(1, utc(2026, 10, 1, 9), "today"),
                session(2, utc(2026, 9, 30, 23), "yesterday"),
                session(3, utc(2026, 9, 25, 0), "week edge"),
                session(4, utc(2026, 9, 24, 23), "just older"),
                session(5, utc(2026, 1, 2, 15), "january"),
            ),
            pinnedIds = emptySet(),
            previews = emptyMap(),
            now = now,
            labels = labels,
            zone = zone,
        )
        assertEquals(
            listOf("Today", "today", "Yesterday", "yesterday", "This week", "week edge", "Earlier", "just older", "january"),
            titles(items),
        )
    }

    @Test fun pinned_chats_lead_and_are_not_repeated_in_their_day() {
        val items = HistoryList.build(
            sessions = listOf(
                session(1, utc(2026, 10, 1, 8), "loose"),
                session(2, utc(2026, 10, 1, 12), "pinned new"),
                session(3, utc(2026, 1, 2, 12), "pinned old"),
            ),
            pinnedIds = setOf(2, 3),
            previews = mapOf(2L to "latest line"),
            now = now,
            labels = labels,
            zone = zone,
        )
        assertEquals(listOf("Pinned", "pinned new", "pinned old", "Today", "loose"), titles(items))
        val pinnedNew = items[1] as HistoryListItem.Session
        assertTrue(pinnedNew.pinned)
        assertEquals("latest line", pinnedNew.preview)
    }

    @Test fun a_january_chat_is_not_this_week_in_december() {
        val newYearEve = utc(2026, 12, 31, 12)
        val january = utc(2026, 1, 2, 15)
        assertEquals(HistoryList.Section.EARLIER, HistoryList.section(january, newYearEve, pinned = false, zone))
        assertEquals(HistoryList.TimestampKind.MONTH_DAY, HistoryList.timestampKind(january, newYearEve, zone))
        assertEquals(HistoryList.TimestampKind.TIME, HistoryList.timestampKind(utc(2026, 12, 30, 18), newYearEve, zone))
        assertEquals(HistoryList.TimestampKind.FULL, HistoryList.timestampKind(utc(2025, 12, 31, 12), newYearEve, zone))
    }

    @Test fun the_week_boundary_is_six_days_before_today() {
        val start = HistoryList.startOfDay(now, zone)
        assertEquals(HistoryList.Section.WEEK, HistoryList.section(start - 6 * 86_400_000L, now, false, zone))
        assertEquals(HistoryList.Section.EARLIER, HistoryList.section(start - 6 * 86_400_000L - 1, now, false, zone))
        assertEquals(HistoryList.TimestampKind.WEEKDAY, HistoryList.timestampKind(utc(2026, 9, 28, 12), now, zone))
        assertEquals(HistoryList.TimestampKind.TIME, HistoryList.timestampKind(utc(2026, 9, 30, 1), now, zone))
    }

    @Test fun preview_folds_a_user_line_and_names_a_photo() {
        val you = { text: String -> "You: $text" }
        assertEquals("You: hello there", HistoryList.preview("user", "\"hello\\n**there**\"", you, "Photo"))
        assertEquals("Look", HistoryList.preview("assistant", "\"Look\"", you, "Photo"))
        assertEquals(
            "You: Look at this",
            HistoryList.preview(
                "user",
                """[{"type":"text","text":"Look at this"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AAAA"}}]""",
                you,
                "Photo",
            ),
        )
        assertEquals(
            "Photo",
            HistoryList.preview(
                "user",
                """[{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AAAA""",
                you,
                "Photo",
            ),
        )
        assertEquals("hello", HistoryList.preview("assistant", "\"hello", you, "Photo"))
        assertEquals("", HistoryList.preview("user", "", you, "Photo"))
        assertEquals("You: 100% sure", HistoryList.preview("user", "\"100% sure\"", you, "Photo"))
    }
}
