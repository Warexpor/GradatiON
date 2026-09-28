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

    @Test
    fun expandMacros_replacesCardPlaceholders() {
        val out = RpPromptEngine.expandMacros("{{char}} waves at {{ user }}. <BOT> knows <USER>.", "Mira", "Alex")
        org.junit.Assert.assertEquals("Mira waves at Alex. Mira knows Alex.", out)
    }

    @Test
    fun buildSystemPrompt_memoryMacrosAndCraft() {
        val char = RpCharacter(id = 1, name = "Mira", scenario = "{{user}} walks into {{char}}'s garage.",
            examplesJson = """[{"user":"hi","char":"*nods*"},{"user":"bye","char":"later"}]""")
        val prompt = RpPromptEngine.buildSystemPrompt(
            character = char, persona = "A pilot", lore = "", instruction = "",
            thirdPerson = false, showThoughts = false, isLlm = false,
            memory = "{{user}} owes {{char}} a favor.", userName = "Alex"
        )
        assertTrue(prompt.contains("Alex walks into Mira's garage."))
        assertTrue(prompt.contains("## Memory"))
        assertTrue(prompt.contains("Alex owes Mira a favor."))
        assertTrue(prompt.contains("is Alex."))
        assertTrue("examples on their own lines", prompt.contains("\n  Jordan: bye"))
        assertTrue(prompt.contains("Never speak, act or decide for the user"))
        assertFalse(prompt.contains("{{"))
    }

    @Test
    fun exampleDialogUsesStandInNotTheRealUser() {
        val char = RpCharacter(
            id = 1, name = "Mira",
            examplesJson = """[{"user":"{{user}} waves","char":"{{char}} waves back at {{random_user_2}}"}]"""
        )
        val prompt = RpPromptEngine.buildSystemPrompt(
            character = char, persona = "A pilot", lore = "", instruction = "",
            thirdPerson = false, showThoughts = false, isLlm = false,
            memory = "Keep the promise.", facts = "Others:\n- Dockmaster: runs the night pier",
            userName = "Alex"
        )
        assertTrue(prompt.contains("is Alex."))
        assertTrue(prompt.contains("## Memory"))
        assertTrue(prompt.contains("Keep the promise."))
        assertTrue(prompt.contains("Dockmaster: runs the night pier"))
        assertTrue(prompt.contains("Jordan: Jordan waves"))
        assertTrue(prompt.contains("Mira: Mira waves back at Riley"))
        assertFalse(prompt.contains("Alex: Jordan"))
        assertFalse(prompt.contains("Alex waves"))
    }

    @Test
    fun buildSystemPrompt_blankMemoryAddsNoSection() {
        val prompt = RpPromptEngine.buildSystemPrompt(
            character = RpCharacter(id = 1, name = "Mira"), persona = "", lore = "",
            instruction = "", thirdPerson = false, showThoughts = false, isLlm = false, memory = "  "
        )
        assertFalse(prompt.contains("## Memory"))
    }

    @Test
    fun buildSystemPrompt_clipsDefinitionFromTheEnd() {
        val card = "HEAD stays.\n" + "x".repeat(200)
        val prompt = RpPromptEngine.buildSystemPrompt(
            character = RpCharacter(id = 1, name = "Mira", prompt = card),
            persona = "", lore = "", instruction = "",
            thirdPerson = false, showThoughts = false, isLlm = false,
            definitionCap = 40
        )
        assertTrue(prompt.contains("HEAD stays."))
        assertFalse(prompt.contains("x".repeat(80)))
        assertTrue(prompt.contains("## Response Format"))
    }
}
