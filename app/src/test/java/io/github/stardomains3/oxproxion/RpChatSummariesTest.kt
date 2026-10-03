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
    fun previewKeepsAnUnderscoreBetweenLettersThatAreNotAscii() {
        assertEquals("déjà_vu", RpChatSummaries.previewOf("\"déjà_vu\""))
        assertEquals(
            "You: déjà_vu",
            RpChatSummaries.rowLine("user", "\"déjà_vu\"", { "You: $it" }, "Photo")
        )
        val word = RpCharacter(name = "Mira", personality = "She keeps the карта_реки.")
        assertEquals("She keeps the карта_реки.", RpChatSummaries.tagline(word, "Alex"))
        val named = RpCharacter(name = "déjà_vu", personality = "{{bot}} waits")
        assertEquals("déjà_vu waits", RpChatSummaries.tagline(named, "Alex"))
        assertEquals("заметка", RpChatSummaries.previewOf("\"_заметка_\""))
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
    fun taglineSkipsAHorizontalRule() {
        val dashes = RpCharacter(name = "Mira", personality = "---\nShe keeps the locket.\nSecond stays off.")
        assertEquals("She keeps the locket.", RpChatSummaries.tagline(dashes, "Alex"))
        val spaced = RpCharacter(name = "Mira", personality = "- - -\n{{char}} waits at the docks.")
        assertEquals("Mira waits at the docks.", RpChatSummaries.tagline(spaced, "Alex"))
        val equals = RpCharacter(name = "Mira", personality = "===\nShe waits.")
        assertEquals("She waits.", RpChatSummaries.tagline(equals, "Alex"))
        val hashes = RpCharacter(name = "Mira", personality = "###\nShe waits at the docks.")
        assertEquals("She waits at the docks.", RpChatSummaries.tagline(hashes, "Alex"))
        val sharp = RpCharacter(name = "Mira", personality = "C#\nSecond stays off.")
        assertEquals("C#", RpChatSummaries.tagline(sharp, "Alex"))
        val fresh = RpChatSummaries.build(
            sessions = emptyList(),
            characters = listOf(dashes.copy(id = 4)),
            previews = emptyMap(),
            llmName = "GradatiON",
            noPreview = "No messages yet",
            startPrompt = "Tap to start",
            userName = "Alex",
        )
        assertEquals("She keeps the locket.", fresh[0].preview)
    }

    @Test
    fun taglineKeepsAStarOrBacktickThatIsPartOfTheWords() {
        val sum = RpCharacter(name = "Mira", personality = "She works out 2 * 3 and a*b.")
        assertEquals("She works out 2 * 3 and a*b.", RpChatSummaries.tagline(sum, "Alex"))
        val lone = RpCharacter(name = "Mira", personality = "A lone ` stays.")
        assertEquals("A lone ` stays.", RpChatSummaries.tagline(lone, "Alex"))
        val span = RpCharacter(name = "Mira", personality = "Use `snake_case` here.")
        assertEquals("Use snake_case here.", RpChatSummaries.tagline(span, "Alex"))
        val emph = RpCharacter(name = "Mira", personality = "*She* keeps the locket.")
        assertEquals("She keeps the locket.", RpChatSummaries.tagline(emph, "Alex"))
        val fence = RpCharacter(name = "Mira", personality = "```\nShe keeps the locket.")
        assertEquals("She keeps the locket.", RpChatSummaries.tagline(fence, "Alex"))
        assertEquals(
            "You: 2 * 3 and a lone `",
            RpChatSummaries.rowLine("user", "\"2 * 3 and a lone `\"", { "You: $it" }, "Photo"),
        )
    }

    @Test
    fun taglineStopsAtABreakThatIsNotANewline() {
        val br = RpCharacter(name = "Mira", personality = "She waits at the docks.<br>Second stays off.")
        assertEquals("She waits at the docks.", RpChatSummaries.tagline(br, "Alex"))
        val brSlash = RpCharacter(name = "Mira", personality = "She waits.<br/>Second stays off.")
        assertEquals("She waits.", RpChatSummaries.tagline(brSlash, "Alex"))
        val line = RpCharacter(name = "Mira", personality = "She waits.\u2028Second stays off.")
        assertEquals("She waits.", RpChatSummaries.tagline(line, "Alex"))
        val entity = RpCharacter(
            name = "Mira",
            personality = "She&apos;s at the docks &amp; the pier.",
        )
        assertEquals("She\u0027s at the docks & the pier.", RpChatSummaries.tagline(entity, "Alex"))
        val twice = RpCharacter(name = "Mira", personality = "Tom &amp;amp; Jerry")
        assertEquals("Tom &amp; Jerry", RpChatSummaries.tagline(twice, "Alex"))
        assertEquals("She's at the docks.", RpChatSummaries.previewOf("\"She&#39;s at the docks.\""))
        assertEquals("Hello there", RpChatSummaries.previewOf("\"Hello<br>there\""))
        val fresh = RpChatSummaries.build(
            sessions = emptyList(),
            characters = listOf(br.copy(id = 4)),
            previews = emptyMap(),
            llmName = "GradatiON",
            noPreview = "No messages yet",
            startPrompt = "Tap to start",
            userName = "Alex",
        )
        assertEquals("She waits at the docks.", fresh[0].preview)
    }

    @Test
    fun taglineDoesNotSplitAnEmojiAtTheLimit() {
        val gem = "\uD83D\uDC8E"
        val cut = RpCharacter(name = "Mira", personality = "a".repeat(139) + gem)
        val line = RpChatSummaries.tagline(cut, "Alex")
        assertEquals("a".repeat(139), line)
        assertFalse(line.any { it.isHighSurrogate() })
        val kept = RpCharacter(name = "Mira", personality = "She keeps the locket $gem")
        assertTrue(RpChatSummaries.tagline(kept, "Alex").endsWith(gem))
    }

    @Test
    fun loreTileFollowsTheBookThatWouldBeUsed() {
        assertFalse(RpChatSummaries.loreTileOn(loreEnabled = false, pinnedBookExists = true, activeBookExists = true))
        assertFalse(RpChatSummaries.loreTileOn(loreEnabled = true, pinnedBookExists = false, activeBookExists = false))
        assertTrue(RpChatSummaries.loreTileOn(loreEnabled = true, pinnedBookExists = false, activeBookExists = true))
        assertTrue(RpChatSummaries.loreTileOn(loreEnabled = true, pinnedBookExists = true, activeBookExists = false))
    }
}
