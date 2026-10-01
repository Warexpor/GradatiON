package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpSwipeRulesTest {

    private data class Msg(val role: String)

    private fun swipeable(messages: List<Msg>): Boolean =
        RpSwipeRules.isSwipeableMessages(
            messages,
            isUser = { it.role == "user" },
            isAssistant = { it.role == "assistant" }
        )

    @Test
    fun greetingOnlyNotSwipeable() {
        assertFalse(swipeable(listOf(Msg("assistant"))))
        assertFalse(swipeable(emptyList()))
    }

    @Test
    fun userWithoutReplyNotSwipeable() {
        assertFalse(swipeable(listOf(Msg("assistant"), Msg("user"))))
    }

    @Test
    fun replyAfterUserIsSwipeable() {
        assertTrue(
            swipeable(
                listOf(Msg("assistant"), Msg("user"), Msg("assistant"))
            )
        )
    }

    @Test
    fun assistantBeforeUserNotSwipeableAlone() {
        assertFalse(swipeable(listOf(Msg("assistant"), Msg("user"))))
    }

    @Test
    fun reconcileKeepsMatchingAlts() {
        val (alts, index) = RpSwipeRules.reconcileAltsAfterTruncate(
            listOf("a", "b", "c"),
            "b"
        )
        assertEquals(listOf("a", "b", "c"), alts)
        assertEquals(1, index)
    }

    @Test
    fun reconcileReseedsWhenLastReplyUnknown() {
        val (alts, index) = RpSwipeRules.reconcileAltsAfterTruncate(
            listOf("later-alt-1", "later-alt-2"),
            "earlier-reply"
        )
        assertEquals(listOf("earlier-reply"), alts)
        assertEquals(0, index)
    }

    @Test
    fun stashDoesNotDuplicateSelectedSeed() {
        val (alts, index) = RpSwipeRules.stashCurrentAlt(listOf("a"), 0, "a")
        assertEquals(listOf("a"), alts)
        assertEquals(0, index)
    }

    @Test
    fun stashKeepsMidSelectionIndex() {
        val (alts, index) = RpSwipeRules.stashCurrentAlt(listOf("a", "b", "c"), 1, "b")
        assertEquals(listOf("a", "b", "c"), alts)
        assertEquals(1, index)
    }

    @Test
    fun stashAppendsWhenVisibleDiffers() {
        val (alts, index) = RpSwipeRules.stashCurrentAlt(listOf("a"), 0, "edited")
        assertEquals(listOf("a", "edited"), alts)
        assertEquals(1, index)
    }

    @Test
    fun anOlderSaveKeepsSharingOnePictureUntilAFileIsKnown() {
        val legacy = RpSwipeRules.stashAlt(listOf("a"), emptyList(), 0, "a", "")
        assertEquals(listOf("a"), legacy.first)
        assertTrue(legacy.second.isEmpty())
        assertNull(RpSwipeRules.pictureForAlt(legacy.second, legacy.first.size, 0))

        val remembered = RpSwipeRules.stashAlt(listOf("She nods."), emptyList(), 0, "She nods.", "content://scene/1")
        assertEquals(listOf("content://scene/1"), remembered.second)
        val added = RpSwipeRules.appendAlt(remembered.first, remembered.second, "She turns.")
        assertEquals(listOf("She nods.", "She turns."), added.first)
        assertEquals(listOf("content://scene/1", ""), added.second)
        assertEquals("", RpSwipeRules.pictureForAlt(added.second, added.first.size, 1))
        val noted = RpSwipeRules.notePicture(added.first, added.second, added.third, "content://scene/2")
        assertEquals(listOf("content://scene/1", "content://scene/2"), noted)
    }

    @Test
    fun aBlankNoteDoesNotWipeThePictureAndADataUrlIsNotAFile() {
        val pictures = listOf("content://scene/1")
        assertEquals(pictures, RpSwipeRules.notePicture(listOf("a"), pictures, 0, ""))
        assertEquals("", RpSwipeRules.pictureUriOf("data:image/jpeg;base64,qq"))
        assertEquals("", RpSwipeRules.pictureUriOf("  "))
        assertEquals("content://scene/1", RpSwipeRules.pictureUriOf(" content://scene/1 "))
    }

    @Test
    fun anOlderSwipeSaveStillDecodes() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val state = json.decodeFromString<RpSwipeState>("""{"alts":["a","b"],"index":1}""")
        assertEquals(listOf("a", "b"), state.alts)
        assertEquals(1, state.index)
        assertTrue(state.pictureUris.isEmpty())
    }
}
