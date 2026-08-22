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
}
