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
    fun allocateResolvesKnown24BitCollision() {
        // AWAY-03: these two turn keys share a 24-bit hashCode; allocation must diverge.
        val a = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, "sess-84400")
        val b = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, "sess-200064")
        assertEquals(
            "fixture must collide on preferred hash",
            CodeAwayFormat.notificationId(a),
            CodeAwayFormat.notificationId(b),
        )
        val taken = mutableSetOf<Int>()
        val idA = CodeAwayFormat.allocateNotificationId(a, taken)
        taken += idA
        val idB = CodeAwayFormat.allocateNotificationId(b, taken)
        assertEquals(CodeAwayFormat.notificationId(a), idA)
        assertNotEquals(idA, idB)
        assertTrue(CodeAwayFormat.isAwayNotifId(idB))
        assertFalse("probed id must not reuse taken preferred", idB in taken)
    }

    @Test
    fun allocateReusesExistingWhenFree() {
        val key = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, "sess")
        val existing = CodeAwayFormat.NOTIF_ID_BASE + 0x12345
        val id = CodeAwayFormat.allocateNotificationId(key, emptySet(), existing)
        assertEquals(existing, id)
    }

    @Test
    fun allocateIgnoresExistingWhenTaken() {
        val key = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, "sess")
        val existing = CodeAwayFormat.NOTIF_ID_BASE + 0x12345
        val taken = setOf(existing)
        val id = CodeAwayFormat.allocateNotificationId(key, taken, existing)
        assertNotEquals(existing, id)
        assertTrue(CodeAwayFormat.isAwayNotifId(id))
        assertFalse(id in taken)
    }

    @Test
    fun allocateStableWhenUncontested() {
        val key = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.APPROVAL, "s", "r")
        val preferred = CodeAwayFormat.notificationId(key)
        assertEquals(preferred, CodeAwayFormat.allocateNotificationId(key, emptySet()))
        assertEquals(preferred, CodeAwayFormat.allocateNotificationId(key, setOf(preferred + 1)))
    }

    @Test
    fun takenFromPrefsSkipsExceptKeyAndNonAwayIds() {
        val keep = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, "alive")
        val other = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.APPROVAL, "s", "r")
        val keepId = CodeAwayFormat.NOTIF_ID_BASE + 0x11
        val otherId = CodeAwayFormat.NOTIF_ID_BASE + 0x22
        val entries = mapOf(
            keep to keepId,
            other to otherId,
            "junk" to 1, // legacy sticky FGS — not away space
            "also" to "nope",
        )
        assertEquals(setOf(otherId), CodeAwayFormat.takenFromPrefs(entries, exceptKey = keep))
        assertEquals(setOf(keepId, otherId), CodeAwayFormat.takenFromPrefs(entries))
    }

    @Test
    fun coldStartAllocationAvoidsPrefsHeldIds() {
        // Surviving shade entry for sess-84400 still holds the preferred hash; a colliding
        // sibling key must probe after process death (empty in-memory taken).
        val alive = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, "sess-84400")
        val sibling = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, "sess-200064")
        assertEquals(
            CodeAwayFormat.notificationId(alive),
            CodeAwayFormat.notificationId(sibling),
        )
        val aliveId = CodeAwayFormat.notificationId(alive)
        val prefsTaken = CodeAwayFormat.takenFromPrefs(mapOf(alive to aliveId), exceptKey = sibling)
        val id = CodeAwayFormat.allocateNotificationId(sibling, prefsTaken)
        assertNotEquals(aliveId, id)
        assertTrue(CodeAwayFormat.isAwayNotifId(id))
    }

    @Test
    fun postedKeysFromPrefsSkipsNonAwayIds() {
        val keep = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, "alive")
        val other = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.APPROVAL, "s", "r")
        val keepId = CodeAwayFormat.NOTIF_ID_BASE + 0x11
        val otherId = CodeAwayFormat.NOTIF_ID_BASE + 0x22
        val entries = mapOf(
            keep to keepId,
            other to otherId,
            "junk" to 1,
            "also" to "nope",
        )
        assertEquals(setOf(keep, other), CodeAwayFormat.postedKeysFromPrefs(entries))
    }

    @Test
    fun coldStartPostedKeysSuppressShouldPost() {
        // Prefs-held key after process death must still dedup. clearTurnDoneDedup also
        // drops the prefs row so seed cannot re-suppress; memory-only clear is not enough.
        val key = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, "sess")
        val id = CodeAwayFormat.notificationId(key)
        val seeded = CodeAwayFormat.postedKeysFromPrefs(mapOf(key to id))
        assertFalse(CodeAwayFormat.shouldPost(seeded, key))
        val afterClear = seeded.toMutableSet().also { it.remove(key) }
        assertTrue(CodeAwayFormat.shouldPost(afterClear, key))
        assertTrue(
            CodeAwayFormat.shouldPost(
                CodeAwayFormat.postedKeysFromPrefs(emptyMap<String, Any>()),
                key,
            ),
        )
    }

    @Test
    fun requestCodesStayDistinctAcrossAlerts() {
        val ids = listOf(
            CodeAwayFormat.NOTIF_ID_BASE,
            CodeAwayFormat.NOTIF_ID_BASE + 1,
            CodeAwayFormat.NOTIF_ID_BASE + CodeAwayFormat.NOTIF_ID_MASK,
        )
        val codes = HashSet<Int>()
        for (id in ids) {
            assertTrue(codes.add(CodeAwayFormat.contentRequestCode(id)))
            assertTrue(codes.add(CodeAwayFormat.actionRequestCode(id, allow = true)))
            assertTrue(codes.add(CodeAwayFormat.actionRequestCode(id, allow = false)))
        }
        // Same 24-bit hash, different allocated ids: taps must not share a PendingIntent.
        val a = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, "sess-84400")
        val b = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, "sess-200064")
        val idA = CodeAwayFormat.allocateNotificationId(a, emptySet())
        val idB = CodeAwayFormat.allocateNotificationId(b, setOf(idA))
        assertNotEquals(CodeAwayFormat.contentRequestCode(idA), CodeAwayFormat.contentRequestCode(idB))
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
        val agent = ApprovalOption("agent", "Agent", ApprovalOption.Kind.ALLOW_ONCE)
        val plan = ApprovalOption("plan", "Plan", ApprovalOption.Kind.ALLOW_ONCE)
        assertNull(CodeAwayFormat.pickAllow(listOf(agent, plan)))
    }

    @Test
    fun pickDenyPreferOnce() {
        val always = ApprovalOption("ra", "Never", ApprovalOption.Kind.REJECT_ALWAYS)
        val once = ApprovalOption("r", "Deny", ApprovalOption.Kind.REJECT_ONCE)
        assertEquals(once, CodeAwayFormat.pickDeny(listOf(always, once)))
        assertEquals(always, CodeAwayFormat.pickDeny(listOf(always)))
        assertNull(CodeAwayFormat.pickDeny(emptyList()))
        val stop = ApprovalOption("stop", "Reject and stop", ApprovalOption.Kind.REJECT_ONCE)
        assertNull(CodeAwayFormat.pickDeny(listOf(once, stop)))
    }

    @Test
    fun shorterSessionDoesNotOwnLongerApprovalKey() {
        val shorter = "ab"
        val longer = "ab:cd"
        val longerKey = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.APPROVAL, longer, "req")
        val shorterKey = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.APPROVAL, shorter, "req")
        val known = setOf(shorter, longer)
        assertFalse(CodeAwayFormat.keyBelongsToSession(longerKey, shorter, known))
        assertTrue(CodeAwayFormat.keyBelongsToSession(longerKey, longer, known))
        assertTrue(CodeAwayFormat.keyBelongsToSession(shorterKey, shorter, known))
        assertFalse(CodeAwayFormat.keyBelongsToSession(shorterKey, longer, known))
        val turn = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, shorter)
        assertTrue(CodeAwayFormat.keyBelongsToSession(turn, shorter, known))
        assertFalse(CodeAwayFormat.keyBelongsToSession(turn, longer, known))
        assertFalse(CodeAwayFormat.keyBelongsToSession(longerKey, "", known))
    }

    @Test
    fun shouldNotifyTurnDoneSkipsCancelled() {
        // C2: local Stop must not post “Turn finished”.
        assertFalse(CodeAwayFormat.shouldNotifyTurnDone(true, "cancelled"))
        assertFalse(CodeAwayFormat.shouldNotifyTurnDone(false, "end_turn"))
        assertTrue(CodeAwayFormat.shouldNotifyTurnDone(true, "end_turn"))
        assertTrue(CodeAwayFormat.shouldNotifyTurnDone(true, "error"))
    }

    @Test
    fun dismissRequestCodesStayDistinct() {
        val id = CodeAwayFormat.NOTIF_ID_BASE + 0x42
        val codes = setOf(
            CodeAwayFormat.contentRequestCode(id),
            CodeAwayFormat.actionRequestCode(id, allow = true),
            CodeAwayFormat.actionRequestCode(id, allow = false),
            CodeAwayFormat.dismissRequestCode(id),
        )
        assertEquals(4, codes.size)
    }

    @Test
    fun postedKeysSkipHoldPrefix() {
        val key = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, "sess")
        val id = CodeAwayFormat.notificationId(key)
        val entries = mapOf(
            CodeAwayFormat.holdPrefKey(key) to id,
            key to id,
        )
        assertEquals(setOf(key), CodeAwayFormat.postedKeysFromPrefs(entries))
        // The parked id is still taken for a different key, not for this one.
        assertEquals(emptySet<Int>(), CodeAwayFormat.takenFromPrefs(entries, exceptKey = key))
        val other = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, "other")
        assertEquals(setOf(id), CodeAwayFormat.takenFromPrefs(entries, exceptKey = other))
    }

    @Test
    fun sessionIdForDedupKeyPrefersLongest() {
        val sid = "ab:cd"
        val key = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.APPROVAL, sid, "req")
        assertEquals(sid, CodeAwayFormat.sessionIdForDedupKey(key, setOf("ab", sid)))
        assertEquals(
            sid,
            CodeAwayFormat.sessionIdForDedupKey(
                CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, sid),
                setOf("ab", sid),
            ),
        )
        assertNull(CodeAwayFormat.sessionIdForDedupKey(key, emptySet()))
    }

    @Test
    fun channelCanNotifyRejectsImportanceNone() {
        assertFalse(CodeAwayFormat.channelCanNotify(0)) // IMPORTANCE_NONE
        assertTrue(CodeAwayFormat.channelCanNotify(3)) // IMPORTANCE_DEFAULT
        assertTrue(CodeAwayFormat.channelCanNotify(4)) // IMPORTANCE_HIGH
    }

}
