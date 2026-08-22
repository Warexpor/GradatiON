package io.github.stardomains3.oxproxion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpPromptEngineTest {

    @Test
    fun buildSystemPrompt_llmSkipsThirdPerson() {
        val prompt = RpPromptEngine.buildSystemPrompt(
            character = null,
            persona = "",
            lang = "en",
            lore = "",
            instruction = "",
            thirdPerson = true,
            showThoughts = false,
            isLlm = true
        )
        assertFalse(prompt.contains("third person", ignoreCase = true))
        assertTrue(prompt.contains("GradatiON"))
    }

    @Test
    fun buildSystemPrompt_includesCharacterName() {
        val char = RpCharacter(id = 1, name = "Mira", personality = "Bold", greeting = "Hi")
        val prompt = RpPromptEngine.buildSystemPrompt(
            character = char,
            persona = "Alex",
            lang = "en",
            lore = "",
            instruction = "",
            thirdPerson = false,
            showThoughts = false,
            isLlm = false
        )
        assertTrue(prompt.contains("Mira"))
        assertTrue(prompt.contains("Alex"))
        assertTrue(prompt.contains("Bold"))
    }

    @Test
    fun prepareLore_truncatesLongText() {
        val long = "x".repeat(20_000)
        val out = RpPromptEngine.prepareLore(long)
        assertTrue(out.length < long.length)
        assertTrue(out.contains("truncated"))
    }

    @Test
    fun reminderParser_extractsReminder() {
        val parsed = RpReminderParser.parse("Hello _(Reminder: stay tense)_ there")
        assertTrue(parsed.userText.contains("Hello"))
        assertTrue(parsed.userText.contains("there"))
        assertTrue(parsed.reminder?.contains("stay tense") == true)
    }
}
