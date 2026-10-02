package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("You: snake_case", HistoryList.preview("user", "\"snake_case\"", you, "Photo"))
        assertEquals("You: hello", HistoryList.preview("user", "\"_hello_\"", you, "Photo"))
    }

    @Test fun search_line_uses_a_clean_parse_and_a_mid_string_slice() {
        val you = { text: String -> "You: $text" }
        assertEquals(
            "You: the secret word is lantern",
            HistoryList.searchLine("user", "\"the secret word is lantern\"", "lantern", you, "Photo"),
        )
        val slice = "before ".repeat(8) + "lantern" + " after".repeat(12)
        val line = HistoryList.searchLine("user", slice, "lantern", you, "Photo")
        assertTrue(line.startsWith("You: …"))
        assertTrue(line.contains("lantern"))
        assertTrue(line.endsWith("…"))
        assertEquals("", HistoryList.searchLine("user", "\"hello\"", "   ", you, "Photo"))
        assertEquals(
            "",
            HistoryList.searchLine("user", "A".repeat(80), "AAAA", you, "Photo"),
        )
        val late = "alpha ".repeat(30) + "lantern " + "omega ".repeat(8)
        val lateLine = HistoryList.searchLine("user", "\"$late\"", "lantern", you, "Photo")
        assertTrue(lateLine.contains("lantern"))
        assertTrue(lateLine.indexOf("lantern") < 28)
        val cjk = "我们走在安静的街道上".repeat(4) + "公园" + "然后回家休息"
        assertTrue(cjk.length > 40)
        assertFalse(cjk.any { it.isWhitespace() })
        assertTrue(HistoryList.searchLine("user", "\"$cjk\"", "公园", you, "Photo").contains("公园"))
        val url = "https://example.com/" + "segment/".repeat(6)
        assertTrue(url.length > 40)
        assertFalse(url.contains(' '))
        assertTrue(HistoryList.searchLine("assistant", "\"$url\"", "example", you, "Photo").contains("example"))
        assertTrue(
            HistoryList.searchLine("user", "\"use snake_case here\"", "snake_case", you, "Photo").contains("snake_case"),
        )
        assertEquals("", HistoryList.searchLine("user", "\"hello\"", "you", you, "Photo"))
        assertEquals(
            "You: you there",
            HistoryList.searchLine("user", "\"you there\"", "you", you, "Photo"),
        )
    }

    @Test fun emphasis_skips_the_you_prefix_when_the_message_also_matches() {
        assertEquals(5, HistoryList.emphasisAt("You: lantern is lit", "lantern"))
        assertEquals(5, HistoryList.emphasisAt("You: you there", "you"))
        // The label is not the line. A title that is not one of those prefixes still matches.
        assertEquals(-1, HistoryList.emphasisAt("You: hello", "you"))
        assertEquals(-1, HistoryList.emphasisAt("Draft: still writing", "draft"))
        assertEquals(0, HistoryList.emphasisAt("Note: hello", "note"))
        assertEquals(-1, HistoryList.emphasisAt("hello", " "))
        assertEquals(-1, HistoryList.emphasisAt("", "a"))
    }

    @Test fun emphasis_folds_spaces_so_a_draft_hit_stays_bold() {
        // draftMatchIds folds "see  you" to find the chat; the row must mark the same words.
        val hit = HistoryList.emphasis("Draft: see you later", "see  you")
        assertEquals(7, hit!!.start)
        assertEquals(7, hit.length)
        val spaced = HistoryList.emphasis("Draft: see   you later", "see you")
        assertEquals(7, spaced!!.start)
        assertEquals(9, spaced.length)
        val preview = HistoryList.rowPreview("You: sent", "see\nyou later", "see  you") { "Draft: $it" }
        assertEquals(7, HistoryList.emphasisAt(preview, "see  you"))
    }

    @Test fun present_marks_the_open_chat_and_carries_the_query() {
        val items = HistoryList.build(
            sessions = listOf(session(4, now, "notes"), session(9, now, "other")),
            pinnedIds = setOf(4),
            previews = mapOf(4L to "last line"),
            now = now,
            labels = labels,
            zone = zone,
        )
        val shown = HistoryList.present(items, openId = 9, query = "  lantern ")
        val pinned = shown[1] as HistoryListItem.Session
        val other = shown[3] as HistoryListItem.Session
        assertTrue(pinned.pinned)
        assertEquals(false, pinned.open)
        assertEquals(true, other.open)
        assertEquals("lantern", other.query)
        assertEquals("lantern", pinned.query)
    }

    @Test fun an_unsent_draft_replaces_the_last_line_unless_the_message_matches() {
        val label = { text: String -> "Draft: $text" }
        assertEquals("Draft: still writing", HistoryList.rowPreview("You: sent", "still\nwriting", "", label))
        assertEquals("You: lantern", HistoryList.rowPreview("You: lantern", "other words", "lantern", label))
        assertEquals(
            "Draft: the lantern stays",
            HistoryList.rowPreview("You: sent", "the lantern stays", "lantern", label),
        )
        assertEquals("Draft: still writing", HistoryList.rowPreview("You: sent", "still writing", "title", label))
        assertEquals("You: sent", HistoryList.rowPreview("You: sent", "  \n", "", label))
        // "You:" is the label. The draft is what actually contains the word.
        assertEquals("Draft: see you later", HistoryList.rowPreview("You: sent", "see you later", "you", label))
        assertEquals("You: you there", HistoryList.rowPreview("You: you there", "other words", "you", label))
        assertEquals(
            "Draft: see you later",
            HistoryList.rowPreview("You: sent", "see\nyou later", "see  you", label),
        )
        val long = "word ".repeat(30) + "lantern" + " tail".repeat(20)
        val clipped = HistoryList.rowPreview("sent", long, "lantern", label)
        assertTrue(clipped.startsWith("Draft: …"))
        assertTrue(clipped.contains("lantern"))
        assertEquals(clipped.indexOf("lantern"), HistoryList.emphasisAt(clipped, "lantern"))
    }

    @Test fun search_includes_a_chat_whose_only_match_is_the_draft() {
        val drafts = mapOf("new" to "no row", "4" to "the lantern", "9" to "nothing")
        assertEquals(setOf(4L), HistoryList.draftMatchIds(drafts, "LANTERN"))
        assertEquals(setOf(4L), HistoryList.draftMatchIds(mapOf("4" to "see\nyou later"), "see you"))
        assertEquals(setOf(4L), HistoryList.draftMatchIds(mapOf("4" to "see you later"), "see  you"))
        assertTrue(HistoryList.draftMatchIds(drafts, "  ").isEmpty())
        val all = listOf(session(4, now, "notes"), session(9, now, "other"))
        val matched = listOf(session(9, now, "other"))
        assertEquals(listOf(9L, 4L), HistoryList.withDraftMatches(matched, all, drafts, "lantern").map { it.id })
        assertEquals(matched, HistoryList.withDraftMatches(matched, all, drafts, "nothing"))
        assertEquals(matched, HistoryList.withDraftMatches(matched, all, drafts, "   "))
    }

    @Test fun the_open_chat_scrolls_up_to_its_section_header() {
        val shown = HistoryList.present(
            HistoryList.build(
                sessions = listOf(session(4, now, "notes"), session(9, now - 86_400_000L, "other")),
                pinnedIds = emptySet(),
                previews = emptyMap(),
                now = now,
                labels = labels,
                zone = zone,
            ),
            openId = 9,
            query = "",
        )
        val anchor = HistoryList.openAnchor(shown)
        assertTrue(anchor > 0)
        assertTrue(shown[anchor] is HistoryListItem.Header)
        assertTrue((shown[anchor + 1] as HistoryListItem.Session).open)
        assertEquals(-1, HistoryList.openAnchor(emptyList()))
    }

    @Test fun search_folds_spaces_like_a_draft_row() {
        val you = { text: String -> "You: $text" }
        assertEquals(
            "You: see you later",
            HistoryList.searchLine("user", "\"see you later\"", "see  you", you, "Photo"),
        )
        assertEquals(
            "You: see you later",
            HistoryList.searchLine("user", "\"see\\nyou later\"", "see you", you, "Photo"),
        )
        assertTrue(HistoryList.searchLine("user", "\"see you later\"", "  ", you, "Photo").isEmpty())
    }

    @Test fun like_contains_puts_percent_between_words() {
        assertEquals("%lantern%", HistoryList.likeContains("  lantern "))
        assertEquals("%see%you%", HistoryList.likeContains("see  you"))
        assertEquals("%100\\%%", HistoryList.likeContains("100%"))
        assertEquals("%snake\\_case%", HistoryList.likeContains("snake_case"))
        assertEquals("%", HistoryList.likeContains("   "))
        assertEquals("see", HistoryList.searchAnchor("see  you later"))
        assertEquals("lantern", HistoryList.normalizeQuery("  lantern  "))
    }

    @Test fun a_staged_attachment_without_text_still_gets_a_draft_line() {
        val files = { n: Int -> if (n == 1) "File" else "$n files" }
        assertEquals(
            "Photo",
            HistoryList.attachmentDraft(true, true, 2, "Photo", "Audio", files),
        )
        assertEquals(
            "Audio",
            HistoryList.attachmentDraft(false, true, 1, "Photo", "Audio", files),
        )
        assertEquals(
            "2 files",
            HistoryList.attachmentDraft(false, false, 2, "Photo", "Audio", files),
        )
        assertEquals(
            "",
            HistoryList.attachmentDraft(false, false, 0, "Photo", "Audio", files),
        )
        val label = { text: String -> "Draft: $text" }
        assertEquals(
            "Draft: Photo",
            HistoryList.rowPreview("You: sent", "Photo", "", label),
        )
    }

    @Test fun search_finds_a_chat_whose_only_draft_is_a_staged_photo() {
        val prefs = mapOf("4" to "typed words", "9" to "")
        val all = listOf(session(4, now, "notes"), session(9, now, "photos"))
        val host = { id: Long -> if (id == 9L) "Photo" else prefs["$id"].orEmpty() }
        val drafts = HistoryList.draftTextsForSearch(all, prefs, host)
        assertEquals("typed words", drafts["4"])
        assertEquals("Photo", drafts["9"])
        assertEquals(setOf(9L), HistoryList.draftMatchIds(drafts, "photo"))
        assertEquals(setOf(4L), HistoryList.draftMatchIds(drafts, "typed"))
        val matched = listOf(session(4, now, "notes"))
        assertEquals(
            listOf(4L, 9L),
            HistoryList.withDraftMatches(matched, all, drafts, "photo").map { it.id },
        )
    }

    @Test fun search_finds_photo_even_when_a_caption_is_also_waiting() {
        assertEquals("hello", HistoryList.draftSearchText("hello", ""))
        assertEquals("Photo", HistoryList.draftSearchText("", "Photo"))
        assertEquals("Photo", HistoryList.draftSearchText("Photo", "Photo"))
        assertEquals("hello Photo", HistoryList.draftSearchText("hello", "Photo"))
        assertEquals("see the Photo later", HistoryList.draftSearchText("see the Photo later", "Photo"))
        val prefs = mapOf("4" to "hello")
        val all = listOf(session(4, now, "notes"))
        val host = { id: Long ->
            if (id == 4L) HistoryList.draftSearchText("hello", "Photo") else ""
        }
        val drafts = HistoryList.draftTextsForSearch(all, prefs, host)
        assertEquals("hello Photo", drafts["4"])
        assertEquals(setOf(4L), HistoryList.draftMatchIds(drafts, "photo"))
        assertEquals(setOf(4L), HistoryList.draftMatchIds(drafts, "hello"))
        // Idle row preview still prefers the caption alone.
        assertEquals(
            "Draft: hello",
            HistoryList.rowPreview("You: sent", "hello", "", { "Draft: $it" }),
        )
    }

    @Test fun draft_row_shows_photo_when_search_hits_the_attachment() {
        assertEquals("hello", HistoryList.draftRowText("hello", "Photo", ""))
        assertEquals("Photo", HistoryList.draftRowText("", "Photo", "photo"))
        assertEquals("hello", HistoryList.draftRowText("hello", "Photo", "hello"))
        assertEquals("hello Photo", HistoryList.draftRowText("hello", "Photo", "photo"))
        assertEquals("see the Photo later", HistoryList.draftRowText("see the Photo later", "Photo", "photo"))
        assertEquals("hello", HistoryList.draftRowText("hello", "Photo", "lantern"))
        val label = { text: String -> "Draft: $text" }
        val row = HistoryList.rowPreview(
            "You: sent",
            HistoryList.draftRowText("hello", "Photo", "photo"),
            "photo",
            label,
        )
        assertEquals("Draft: hello Photo", row)
        assertEquals(13, HistoryList.emphasisAt(row, "photo"))
    }
}
