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
    fun loreCardFieldsKeepSpeechStyleWithTheSetting() {
        val fields = RpPromptEngine.loreCardFields(
            RpCharacter(
                name = "Mira",
                scenario = "docks",
                personality = "bold",
                style = "calls it Greyhaven",
                greeting = "hi",
                prompt = "long",
                instruction = "never lie"
            )
        )
        org.junit.Assert.assertEquals(
            listOf("docks", "bold", "calls it Greyhaven", "hi", "long", "never lie"),
            fields
        )
    }

    @Test
    fun chatNamesUseTheSameFallbacksAsThePrompt() {
        org.junit.Assert.assertEquals("Mira" to "Alex", RpPromptEngine.chatNames("Mira", "Alex"))
        org.junit.Assert.assertEquals("GradatiON" to "the user", RpPromptEngine.chatNames("  ", ""))
    }

    @Test
    fun expandKnownMacrosLeavesABlankNameAlone() {
        org.junit.Assert.assertEquals(
            "Mira owes {{user}}.",
            RpPromptEngine.expandKnownMacros("{{char}} owes {{user}}.", "Mira", "")
        )
        org.junit.Assert.assertEquals(
            "{{char}} owes Alex.",
            RpPromptEngine.expandKnownMacros("<BOT> owes <USER>.", "", "Alex")
        )
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
    fun buildSystemPrompt_expandsMacrosInTheInstruction() {
        val prompt = RpPromptEngine.buildSystemPrompt(
            character = RpCharacter(id = 1, name = "Mira"), persona = "", lore = "",
            instruction = "{{char}} never lies to {{user}}.", thirdPerson = false, showThoughts = false,
            isLlm = false, userName = "Alex"
        )
        assertTrue(prompt.contains("Additional instruction: Mira never lies to Alex."))
        assertFalse(prompt.contains("{{"))
    }

    @Test
    fun buildSystemPrompt_cutsLongMemoryOnALineBreak() {
        val line = "- a fact that matters to the story"
        val memory = (1..400).joinToString("\n") { "$line $it" }
        val prompt = RpPromptEngine.buildSystemPrompt(
            character = RpCharacter(id = 1, name = "Mira"), persona = "", lore = "", instruction = "",
            thirdPerson = false, showThoughts = false, isLlm = false, memory = memory
        )
        val kept = prompt.substringAfter("## Memory (the user asked to keep this true)\n").substringBefore("\n## Response Format")
        assertTrue(kept.length <= RpPromptEngine.MEMORY_MAX_CHARS)
        assertTrue("ends on a whole line: $kept", kept.lines().last().matches(Regex("""- a fact that matters to the story \d+""")))
    }

    @Test
    fun buildSystemPrompt_loreHeadingIsEnglishOnly() {
        val prompt = RpPromptEngine.buildSystemPrompt(
            character = RpCharacter(id = 1, name = "Mira"), persona = "", lore = "The docks are grey.", instruction = "",
            thirdPerson = false, showThoughts = false, isLlm = false
        )
        assertTrue(prompt.contains("World Lore:\nThe docks are grey."))
        assertFalse(prompt.contains("Мир"))
    }

    @Test
    fun buildSystemPrompt_formatFollowsShowThoughts() {
        fun prompt(thoughts: Boolean) = RpPromptEngine.buildSystemPrompt(
            character = RpCharacter(id = 1, name = "Mira"), persona = "", lore = "", instruction = "",
            thirdPerson = false, showThoughts = thoughts, isLlm = false
        )
        assertTrue(prompt(true).contains("You can show inner thoughts in (parentheses)"))
        assertTrue(prompt(true).endsWith("insufferable about this.)"))
        assertFalse(prompt(true).contains("no thoughts from the user"))
        assertTrue(prompt(false).contains("no thoughts from the user"))
        assertFalse(prompt(false).contains("inner thoughts"))
        assertTrue(prompt(false).endsWith("the faint smirk.*"))
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

    @Test
    fun rewriteDirective_carriesTheNoteOutOfCharacter() {
        val d = RpPromptEngine.rewriteDirective("  make it shorter  ")
        assertTrue(d.startsWith("(OOC:"))
        assertTrue(d.contains("What to change: make it shorter\n"))
        assertTrue(d.contains("Write only the new version"))
    }

    @Test
    fun craftTellsTheCharacterAPhotoIsInTheScene() {
        val prompt = RpPromptEngine.buildSystemPrompt(
            character = RpCharacter(id = 1, name = "Mira"),
            persona = "", lore = "", instruction = "",
            thirdPerson = false, showThoughts = false, isLlm = false
        )
        assertTrue(prompt.contains("photo is in the scene"))
        assertTrue(RpPromptEngine.PHOTO_TURN.contains("says nothing"))
    }
}
