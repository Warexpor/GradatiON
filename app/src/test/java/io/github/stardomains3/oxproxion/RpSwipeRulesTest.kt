package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    fun aRebuiltFileReplacesTheLinkEveryVersionWasShowing() {
        val alts = listOf("She nods.", "She turns.")
        val pictures = listOf("content://cache/1", "content://cache/1")
        val adopted = RpSwipeRules.adoptVisibleFile(alts, pictures, 0, "She nods.", "content://files/a")
        assertEquals(listOf("content://files/a", "content://files/a"), adopted)
        assertSame(pictures, RpSwipeRules.adoptVisibleFile(alts, pictures, 0, "She nods.", ""))
        assertSame(pictures, RpSwipeRules.adoptVisibleFile(alts, pictures, 0, "She turns.", "content://files/a"))
        val blank = listOf("", "content://cache/2")
        assertSame(blank, RpSwipeRules.adoptVisibleFile(alts, blank, 0, "She nods.", "content://files/a"))
        assertSame(pictures, RpSwipeRules.remapPicture(pictures, "content://other", "content://files/a"))
    }

    @Test
    fun anOlderSwipeSaveStillDecodes() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val state = json.decodeFromString<RpSwipeState>("""{"alts":["a","b"],"index":1}""")
        assertEquals(listOf("a", "b"), state.alts)
        assertEquals(1, state.index)
        assertTrue(state.pictureUris.isEmpty())
    }

    @Test
    fun aNewTurnKeepsTheVersionsOnThatReply() {
        val state = RpSwipeState(alts = listOf("a", "b", "c"), index = 1)
        val earlier = RpSwipeRules.archive(state, position = 3, visibleText = "b")
        assertEquals(RpVersions(listOf("a", "b", "c"), 1), earlier[3])
        // One version is nothing to swipe.
        assertTrue(RpSwipeRules.archive(RpSwipeState(alts = listOf("a")), 3, "a").isEmpty())
    }

    @Test
    fun earlierVersionsFollowTheTranscript() {
        val earlier = mapOf(1 to RpVersions(listOf("x", "y"), 0), 3 to RpVersions(listOf("p", "q"), 0))
        val text = { m: String? -> m }
        // Reply 1 shows its other version now; reply 3 was cut off.
        val kept = RpSwipeRules.reconcileEarlier(earlier, listOf(null, "y", null), text)
        assertEquals(mapOf(1 to RpVersions(listOf("x", "y"), 1)), kept)
        // An edit that is none of them drops the entry.
        assertTrue(RpSwipeRules.reconcileEarlier(earlier, listOf(null, "z"), text).isEmpty())
        assertSame(earlier, RpSwipeRules.reconcileEarlier(earlier, listOf(null, "x", null, "p"), text))
    }

    @Test
    fun aRewriteOfAnEarlierReplyIsAnotherVersion() {
        var earlier = RpSwipeRules.addEarlierVersion(emptyMap(), 2, "old", "", "new")
        assertEquals(RpVersions(listOf("old", "new"), 1), earlier[2])
        // Undo selects the old one rather than adding it again.
        earlier = RpSwipeRules.addEarlierVersion(earlier, 2, "new", "", "old")
        assertEquals(RpVersions(listOf("old", "new"), 0), earlier[2])
        // A reply with a picture keeps it on the new version.
        val pic = RpSwipeRules.addEarlierVersion(emptyMap(), 2, "old", "file:///p.jpg", "new")[2]!!
        assertEquals(listOf("file:///p.jpg", "file:///p.jpg"), pic.pictureUris)
        // Versions that never tracked pictures start tracking once a file is known.
        var untracked = RpSwipeRules.addEarlierVersion(emptyMap(), 2, "old", "", "mid")
        untracked = RpSwipeRules.addEarlierVersion(untracked, 2, "mid", "file:///late.jpg", "new")
        assertEquals(
            listOf("file:///late.jpg", "file:///late.jpg", "file:///late.jpg"),
            untracked[2]!!.pictureUris,
        )
    }

    @Test
    fun aCardGreetingThatReplacesARewriteDropsItsVersions() {
        // Rewrite left versions on the opening; the card line then overwrote the bubble.
        val earlier = mapOf(0 to RpVersions(listOf("*Mira looks at you.*", "She does not look up."), 1))
        val kept = RpSwipeRules.reconcileEarlier(earlier, listOf("You again?")) { it }
        assertTrue(kept.isEmpty())
    }

    @Test
    fun continueAddsAVersionAndKeepsTheOthers() {
        // Three versions, the second picked, then Continue: all three stay, the grown one is new.
        val (alts, pics, index) = RpSwipeRules.addContinued(
            listOf("a", "b", "c"), emptyList(), 1, "b", "", "b more",
        )
        assertEquals(listOf("a", "b", "c", "b more"), alts)
        assertEquals(3, index)
        assertTrue(pics.isEmpty())
        // A single reply becomes two: the base is the swipe back.
        val (seeded, seededPics, at) = RpSwipeRules.addContinued(
            emptyList(), emptyList(), 0, "base", "file:///p.jpg", "base more",
        )
        assertEquals(listOf("base", "base more"), seeded)
        assertEquals(listOf("file:///p.jpg", "file:///p.jpg"), seededPics)
        assertEquals(1, at)
        // Tracked pictures: the grown version keeps the base's.
        val (_, tracked, _) = RpSwipeRules.addContinued(
            listOf("x", "y"), listOf("", "file:///y.jpg"), 1, "y", "file:///y.jpg", "y more",
        )
        assertEquals(listOf("", "file:///y.jpg", "file:///y.jpg"), tracked)
        // The same grown text twice selects it instead of adding it again.
        val (again, _, againAt) = RpSwipeRules.addContinued(
            listOf("base", "base more"), emptyList(), 0, "base", "", "base more",
        )
        assertEquals(listOf("base", "base more"), again)
        assertEquals(1, againAt)
    }

    @Test
    fun removingOneMessageMovesLaterVersionsUp() {
        val v = RpVersions(listOf("p", "q"), 0)
        val w = RpVersions(listOf("r", "s"), 1)
        val earlier = mapOf(1 to v, 3 to w, 5 to v)
        assertEquals(mapOf(1 to v, 4 to v), RpSwipeRules.afterRemoval(earlier, 3))
        assertEquals(mapOf(1 to v, 2 to w, 4 to v), RpSwipeRules.afterRemoval(earlier, 2))
        assertSame(emptyMap<Int, RpVersions>(), RpSwipeRules.afterRemoval(emptyMap(), 0))
    }

    @Test
    fun aBranchTakesTheVersionsBeforeItsCut() {
        val v = RpVersions(listOf("p", "q"), 1)
        val state = RpSwipeState(alts = listOf("n1", "n2"), index = 0, earlier = mapOf(1 to v, 5 to v))
        // Cut before the newest reply: only the earlier ones before the cut.
        assertEquals(mapOf(1 to v), RpSwipeRules.forBranch(state, 4, 7, "n1"))
        // The whole chat: the newest reply's versions go along under its position.
        val all = RpSwipeRules.forBranch(state, 8, 7, "n1")
        assertEquals(RpVersions(listOf("n1", "n2"), 0), all[7])
        assertEquals(v, all[5])
    }
}
