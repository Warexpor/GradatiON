package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RpAutoMemoryTest {

    @Test fun waitsUntilTheWindowIsNearlyFull() {
        assertFalse(RpAutoMemory.shouldUpdate(messageCount = 10, budget = 30, lastRunAt = 0))
        assertTrue(RpAutoMemory.shouldUpdate(messageCount = 24, budget = 30, lastRunAt = 0))
    }

    @Test fun allMessagesActsLikeASixtyMessageWindow() {
        assertFalse(RpAutoMemory.shouldUpdate(messageCount = 40, budget = Int.MAX_VALUE, lastRunAt = 0))
        assertTrue(RpAutoMemory.shouldUpdate(messageCount = 54, budget = Int.MAX_VALUE, lastRunAt = 0))
    }

    @Test fun failedRewriteDoesNotSkipTheNextWindow() {
        assertEquals(24, RpAutoMemory.watermarkAfter(previous = 24, messageCount = 30, saved = false))
        assertEquals(30, RpAutoMemory.watermarkAfter(previous = 24, messageCount = 30, saved = true))
        assertTrue(RpAutoMemory.shouldUpdate(messageCount = 30, budget = 30, lastRunAt = 24))
    }

    @Test fun thenRefreshesEverySixMessages() {
        assertFalse(RpAutoMemory.shouldUpdate(messageCount = 28, budget = 30, lastRunAt = 24))
        assertTrue(RpAutoMemory.shouldUpdate(messageCount = 30, budget = 30, lastRunAt = 24))
        assertTrue(RpAutoMemory.shouldUpdate(messageCount = 80, budget = 30, lastRunAt = 60))
    }

    @Test fun transcriptKeepsNewestWithinLimit() {
        val turns = (1..400).map { (if (it % 2 == 0) "assistant" else "user") to "line $it ".repeat(20) }
        val t = RpAutoMemory.transcript(turns, "Mira", "Sam")
        assertTrue(t.length <= RpAutoMemory.TRANSCRIPT_CHARS + 200)
        assertTrue(t.endsWith("line 400 ".repeat(20).trim()))
        assertTrue(t.lines().last().startsWith("Mira: "))
    }

    @Test fun promptKeepsUserMemoryOutOfTheRewrite() {
        val p = RpAutoMemory.prompt("Mira", "Sam", "Sam owes nothing", "- Dock is closed", "Sam: hi")
        assertTrue(p.contains("Sam owes nothing"))
        assertTrue(p.contains("do not change it"))
        assertTrue(p.contains("- Dock is closed"))
        assertTrue(p.contains("Others:"))
        assertTrue(p.contains("Sam: hi"))
    }

    @Test fun cleanNormalizesAndRejectsJunk() {
        assertNull(RpAutoMemory.clean(null))
        assertNull(RpAutoMemory.clean("  "))
        assertNull(RpAutoMemory.clean("Error: API request failed with status 401"))
        assertEquals(
            "- Mira owes Sam a favor\n- They leave at first light",
            RpAutoMemory.clean("```\n<think>hmm</think>\n* Mira owes Sam a favor\n\n• They leave at first light\n```")
        )
        val long = (1..200).joinToString("\n") { "- fact number $it" }
        assertTrue(RpAutoMemory.clean(long)!!.length <= RpAutoMemory.MEMORY_CHARS)
    }

    @Test fun cleanDropsAFenceLanguageTag() {
        assertEquals("- Mira owes Sam a favor", RpAutoMemory.clean("```text\n- Mira owes Sam a favor\n```"))
        assertEquals("- Mira owes Sam a favor", RpAutoMemory.clean("<think>hm</think>\n```markdown\n- Mira owes Sam a favor\n```"))
    }

    @Test fun cleanCapNeverLeavesAHalfLine() {
        val line = "- fact".padEnd(39, 'x')
        val out = RpAutoMemory.clean((1..100).joinToString("\n") { line })!!
        assertTrue(out.length <= RpAutoMemory.MEMORY_CHARS)
        assertTrue(out.lines().all { it == line })
    }

    @Test fun cleanCapKeepsALineThatEndsExactlyAtTheLimit() {
        val short = "- fact".padEnd(39, 'x')
        val last = "- fact".padEnd(40, 'x')
        // 39 short lines with their newlines are 1560 chars; the 40-char line ends at 1600, right at the cap.
        val long = (List(39) { short } + last + List(10) { short }).joinToString("\n")
        val out = RpAutoMemory.clean(long)!!
        assertEquals(40, out.lines().size)
        assertEquals(last, out.lines().last())
    }

    @Test fun transcriptExpandsNamesInTheScene() {
        val turns = listOf("user" to "{{user}} shows {{char}} the locket.")
        val t = RpAutoMemory.transcript(turns, "Mira", "Sam\$1")
        assertTrue(t.contains("Sam\$1: Sam\$1 shows Mira the locket."))
        assertFalse(t.contains("{{"))
    }

    @Test fun transcriptDropsRewriteNotesAndKeepsAPhotoBeat() {
        val turns = listOf(
            "user" to RpAutoMemory.turnBody("", showedPhoto = true),
            "user" to RpPromptEngine.rewriteDirective("shorter"),
            "user" to RpPromptEngine.PHOTO_TURN,
            "assistant" to "She waits."
        )
        val t = RpAutoMemory.transcript(turns, "Mira", "Sam")
        assertTrue(t.contains("Sam: ${RpAutoMemory.PHOTO_BEAT}"))
        assertTrue(t.contains("Mira: She waits."))
        assertFalse(t.contains("Rewrite your last reply"))
        assertFalse(t.contains("says nothing"))
    }

    @Test fun transcriptDropsContinueAndABareSceneNote() {
        val note = RpPromptEngine.sceneNote("mention the locket")
        val turns = listOf(
            "user" to RpPromptEngine.CONTINUE_USER_TURN,
            "user" to note,
            "assistant" to note + "\n\nShe opens it.",
            "assistant" to "The locket is warm."
        )
        val t = RpAutoMemory.transcript(turns, "Mira", "Sam")
        assertFalse(t.contains("Continue your last message"))
        assertFalse(t.contains("Scene note"))
        assertTrue(t.contains("She opens it."))
        assertTrue(t.contains("The locket is warm."))
    }

    @Test fun promptExpandsNamesInTheMemoryNote() {
        val p = RpAutoMemory.prompt("Mira", "Sam", "{{user}} owes {{char}} nothing", "{{char}} waits", "Sam: hi")
        assertTrue(p.contains("Sam owes Mira nothing"))
        assertTrue(p.contains("Mira waits"))
        assertFalse(p.contains("{{"))
    }

    @Test fun cleanDropsACopiedLineAfterNamesReplaceMacros() {
        assertEquals(
            "- They leave at dawn",
            RpAutoMemory.clean(
                "- Sam owes Mira nothing.\n- They leave at dawn",
                userMemory = "{{user}} owes {{char}} nothing",
                charName = "Mira",
                userName = "Sam"
            )
        )
        assertNull(
            RpAutoMemory.clean(
                "- sam owes mira nothing",
                userMemory = "Sam owes Mira nothing",
                charName = "Mira",
                userName = "Sam"
            )
        )
        assertEquals(
            "- Sam owes Mira nothing else",
            RpAutoMemory.clean(
                "- Sam owes Mira nothing else",
                userMemory = "Sam owes Mira nothing",
                charName = "Mira",
                userName = "Sam"
            )
        )
    }

    @Test fun cleanDropsAnUnclosedThinkAndCopiedMemory() {
        assertEquals("- They leave at dawn", RpAutoMemory.clean("- They leave at dawn\n<think>still scratching"))
        assertNull(RpAutoMemory.clean("<think>the facts never got written"))
        assertEquals(
            "- They leave at dawn",
            RpAutoMemory.clean("- Sam owes nothing\n- They leave at dawn", userMemory = "Sam owes nothing")
        )
        assertNull(RpAutoMemory.clean("- Sam owes nothing", userMemory = "- Sam owes nothing"))
    }

    @Test fun runKeyFollowsTheChatThatGainedAnId() {
        assertEquals(42L, RpAutoMemory.runKey(launchSessionId = null, currentSessionId = 42L, sameChat = true))
        assertEquals(RpAutoMemory.UNSAVED_KEY, RpAutoMemory.runKey(null, null, sameChat = true))
        assertNull(RpAutoMemory.runKey(launchSessionId = null, currentSessionId = 9L, sameChat = false))
        assertEquals(3L, RpAutoMemory.runKey(launchSessionId = 3L, currentSessionId = 9L, sameChat = false))
    }

    @Test fun cleanCapKeepsAnOverlongSingleLine() {
        val out = RpAutoMemory.clean("- " + "y".repeat(3000))!!
        assertEquals(RpAutoMemory.MEMORY_CHARS, out.length)
    }
}
