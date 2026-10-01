package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
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
}
