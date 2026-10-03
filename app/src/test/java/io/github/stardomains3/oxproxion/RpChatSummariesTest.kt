package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpChatSummariesTest {

    @Test
    fun taglineExpandsCardPlaceholders() {
        val c = RpCharacter(name = "Mira", personality = "{{char}} waits for {{user}} at the docks.")
        assertEquals("Mira waits for Alex at the docks.", RpChatSummaries.tagline(c, "Alex"))
        assertEquals("Mira waits for you at the docks.", RpChatSummaries.tagline(c))
    }

    @Test
    fun aFreshCharacterRowUsesTheExpandedTagline() {
        val c = RpCharacter(id = 4, name = "Mira", personality = "*{{char}}* keeps the locket.")
        val rows = RpChatSummaries.build(
            sessions = emptyList(),
            characters = listOf(c),
            previews = emptyMap(),
            llmName = "GradatiON",
            noPreview = "No messages yet",
            startPrompt = "Tap to start",
            userName = "Alex"
        )
        assertEquals(1, rows.size)
        assertTrue(rows[0].preview.startsWith("Mira keeps the locket."))
    }

    @Test
    fun taglineKeepsUnderscoresAndDropsALeadingMarkdownSpace() {
        val c = RpCharacter(
            name = "Mira",
            personality = "* She waits at config_name.\nThe second line stays off the row.",
        )
        assertEquals("She waits at config_name.", RpChatSummaries.tagline(c, "Alex"))
    }

    @Test
    fun previewKeepsSnakeCaseAndAPhotoWithNoCaption() {
        assertEquals(
            "use snake_case and note",
            RpChatSummaries.previewOf("\"use snake_case and _note_\"")
        )
        val photo = """[{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AAAA"}}]"""
        assertEquals("", RpChatSummaries.previewOf(photo))
        assertEquals("Photo", RpChatSummaries.rowLine("user", photo, { "You: $it" }, "Photo"))
        assertEquals("Photo", RpChatSummaries.rowLine("assistant", photo, { "You: $it" }, "Photo"))
        val captioned = """[{"type":"text","text":"*the* snake_case docks"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AAAA"}}]"""
        assertEquals("You: the snake_case docks", RpChatSummaries.rowLine("user", captioned, { "You: $it" }, "Photo"))
        assertEquals("the snake_case docks", RpChatSummaries.rowLine("assistant", captioned, { "You: $it" }, "Photo"))
        assertEquals("", RpChatSummaries.rowLine("user", "\"\"", { "You: $it" }, "Photo"))
    }

    @Test
    fun previewKeepsMarksThatBelongToTheWords() {
        assertEquals("C# and F#", RpChatSummaries.previewOf("\"C# and F#\""))
        assertEquals("look at ~/Downloads", RpChatSummaries.previewOf("\"look at ~/Downloads\""))
        assertEquals("a > b", RpChatSummaries.previewOf("\"a > b\""))
        assertEquals("Title", RpChatSummaries.previewOf("\"# Title\""))
        assertEquals("quoted", RpChatSummaries.previewOf("\"> quoted\""))
        assertEquals("gone", RpChatSummaries.previewOf("\"~~gone~~\""))
        assertEquals(
            "You: C# and ~/Downloads",
            RpChatSummaries.rowLine("user", "\"C# and ~/Downloads\"", { "You: $it" }, "Photo")
        )
    }

    @Test
    fun taglineSkipsALineThatIsOnlyMarkdown() {
        val ruled = RpCharacter(name = "Mira", personality = "***\nShe keeps the locket.\nSecond stays off.")
        assertEquals("She keeps the locket.", RpChatSummaries.tagline(ruled, "Alex"))
        val quoted = RpCharacter(name = "Mira", personality = ">\nShe waits at the docks.")
        assertEquals("She waits at the docks.", RpChatSummaries.tagline(quoted, "Alex"))
        val marksOnly = RpCharacter(name = "Mira", personality = "***", scenario = "{{char}} keeps the map.")
        assertEquals("Mira keeps the map.", RpChatSummaries.tagline(marksOnly, "Alex"))
    }

    @Test
    fun loreTileFollowsTheBookThatWouldBeUsed() {
        assertFalse(RpChatSummaries.loreTileOn(loreEnabled = false, pinnedBookExists = true, activeBookExists = true))
        assertFalse(RpChatSummaries.loreTileOn(loreEnabled = true, pinnedBookExists = false, activeBookExists = false))
        assertTrue(RpChatSummaries.loreTileOn(loreEnabled = true, pinnedBookExists = false, activeBookExists = true))
        assertTrue(RpChatSummaries.loreTileOn(loreEnabled = true, pinnedBookExists = true, activeBookExists = false))
    }
}
