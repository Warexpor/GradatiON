package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.ApprovalOption
import io.github.stardomains3.oxproxion.code.CodeAwayFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure format / dedup helpers for Code away local notifications (§5.6). */
class CodeAwayFormatTest {

    @Test
    fun approvalDedupIncludesRequestId() {
        val a = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.APPROVAL, "s1", "r1")
        val b = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.APPROVAL, "s1", "r2")
        val c = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.APPROVAL, "s2", "r1")
        assertEquals("approval:s1:r1", a)
        assertNotEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun turnDoneDedupIsPerSession() {
        assertEquals(
            "turn:s1",
            CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, "s1"),
        )
        assertNotEquals(
            CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, "s1"),
            CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, "s2"),
        )
    }

    @Test
    fun shouldPostDedups() {
        val key = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.APPROVAL, "s", "r")
        assertTrue(CodeAwayFormat.shouldPost(emptySet(), key))
        assertFalse(CodeAwayFormat.shouldPost(setOf(key), key))
    }

    @Test
    fun shouldPostAgainAfterTurnKeyCleared() {
        // A3: clearing the turn-done key (on new UserPrompt) must allow a later post.
        val key = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, "s1")
        val posted = mutableSetOf(key)
        assertFalse(CodeAwayFormat.shouldPost(posted, key))
        posted.remove(key)
        assertTrue(CodeAwayFormat.shouldPost(posted, key))
    }

    @Test
    fun notificationIdsStableAndAwayFromLegacy() {
        val key = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, "sess")
        val id1 = CodeAwayFormat.notificationId(key)
        val id2 = CodeAwayFormat.notificationId(key)
        assertEquals(id1, id2)
        assertTrue(id1 >= CodeAwayFormat.NOTIF_ID_BASE)
        assertNotEquals(1, id1)
        assertNotEquals(2, id1)
    }

    @Test
    fun notificationIdsUseWideSpace() {
        // A6: 24-bit entropy under NOTIF_ID_BASE — many distinct session keys stay unique.
        val ids = (0 until 500).map {
            CodeAwayFormat.notificationId(
                CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, "sess-$it"),
            )
        }.toSet()
        assertTrue("expected high uniqueness, got ${ids.size}", ids.size >= 490)
        assertTrue(ids.all { it != 1 && it != 2 && it >= CodeAwayFormat.NOTIF_ID_BASE })
    }

    @Test
    fun headlines() {
        assertEquals("Approval needed · Edit foo.kt", CodeAwayFormat.approvalHeadline("Edit foo.kt"))
        assertEquals("Approval needed", CodeAwayFormat.approvalHeadline("  "))
        assertEquals("Turn finished · My session", CodeAwayFormat.turnDoneHeadline("My session"))
        assertEquals("Turn finished", CodeAwayFormat.turnDoneHeadline(""))
    }

    @Test
    fun pickAllowPreferOnce() {
        val always = ApprovalOption("a", "Always", ApprovalOption.Kind.ALLOW_ALWAYS)
        val once = ApprovalOption("o", "Allow", ApprovalOption.Kind.ALLOW_ONCE)
        assertEquals(once, CodeAwayFormat.pickAllow(listOf(always, once)))
        assertEquals(always, CodeAwayFormat.pickAllow(listOf(always)))
        assertNull(CodeAwayFormat.pickAllow(emptyList()))
    }

    @Test
    fun pickDenyPreferOnce() {
        val always = ApprovalOption("ra", "Never", ApprovalOption.Kind.REJECT_ALWAYS)
        val once = ApprovalOption("r", "Deny", ApprovalOption.Kind.REJECT_ONCE)
        assertEquals(once, CodeAwayFormat.pickDeny(listOf(always, once)))
        assertEquals(always, CodeAwayFormat.pickDeny(listOf(always)))
        assertNull(CodeAwayFormat.pickDeny(emptyList()))
    }

    @Test
    fun shouldNotifyTurnDoneSkipsCancelled() {
        // C2: local Stop must not post “Turn finished”.
        assertFalse(CodeAwayFormat.shouldNotifyTurnDone(true, "cancelled"))
        assertFalse(CodeAwayFormat.shouldNotifyTurnDone(false, "end_turn"))
        assertTrue(CodeAwayFormat.shouldNotifyTurnDone(true, "end_turn"))
        assertTrue(CodeAwayFormat.shouldNotifyTurnDone(true, "error"))
    }
}

