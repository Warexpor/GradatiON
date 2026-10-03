package io.github.stardomains3.oxproxion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import io.github.stardomains3.oxproxion.code.ApprovalOption
import io.github.stardomains3.oxproxion.code.CodeAwayFormat
import io.github.stardomains3.oxproxion.code.CodeAwayNotifier
import io.github.stardomains3.oxproxion.code.CodeEvent
import io.github.stardomains3.oxproxion.code.CodeUpdate
import io.github.stardomains3.oxproxion.code.ToolKind
import io.github.stardomains3.oxproxion.code.store.CodeStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Code away notifier: swipe-dismiss dedup, blocked channel, answer chrome prefs.
 * Run: ./gradlew :app:testDebugUnitTest --tests '*CodeAwayNotifierTest*' --tests '*ForegroundServiceNotifTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class CodeAwayNotifierTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var store: CodeStore
    private lateinit var nm: NotificationManager

    @Before
    fun setUp() {
        ctx.getSharedPreferences(CodeStore.PREFS_NAME, 0).edit().clear().commit()
        ctx.getSharedPreferences("code_away_open_tokens", 0).edit().clear().commit()
        ctx.getSharedPreferences("code_away_notif_ids", 0).edit().clear().commit()
        ctx.getSharedPreferences("code_away_shade_hold", 0).edit().clear().commit()
        store = CodeStore(ctx)
        store.notifyWhenAway = true
        nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancelAll()
        // API 33+: grant POST_NOTIFICATIONS for canPost.
        Shadows.shadowOf(ctx).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        nm.cancelAll()
    }

    private fun approval(requestId: String = "r1"): CodeEvent.Approval =
        CodeEvent.Approval(
            key = "a-$requestId",
            at = 1L,
            requestId = requestId,
            callId = null,
            title = "Edit foo.kt",
            options = listOf(
                ApprovalOption("allow", "Allow", ApprovalOption.Kind.ALLOW_ONCE),
                ApprovalOption("deny", "Deny", ApprovalOption.Kind.REJECT_ONCE),
            ),
            kind = ToolKind.EDIT,
        )

    private fun notifier(connected: Boolean = true) =
        CodeAwayNotifier(ctx, store) { connected }.also { it.setBackgrounded(true) }

    @Test
    fun userDismissClearsDedupSoApprovalCanRepost() {
        val n = notifier()
        val session = "sess-dismiss"
        n.onUpdate(session, "host", "S", CodeUpdate.Upsert(approval()), sessionWasRunning = true)
        val key = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.APPROVAL, session, "r1")
        assertEquals(1, nm.activeNotifications.size)
        // Simulate shade swipe: cancel + DeleteIntent handler.
        nm.cancelAll()
        n.onUserDismissed(key)
        // Still-pending approval must be allowed to re-alert.
        n.onUpdate(session, "host", "S", CodeUpdate.Upsert(approval()), sessionWasRunning = true)
        assertEquals(1, nm.activeNotifications.size)
        assertTrue(CodeAwayFormat.isAwayNotifId(nm.activeNotifications[0].id))
    }

    @Test
    fun blockedChannelSkipsPost() {
        val channel = NotificationChannel(
            CodeAwayNotifier.CHANNEL_ID,
            "Code away",
            NotificationManager.IMPORTANCE_NONE,
        )
        nm.createNotificationChannel(channel)
        val n = notifier()
        n.onUpdate("s", "host", "S", CodeUpdate.Upsert(approval()), sessionWasRunning = true)
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test
    fun clearTurnDoneDedupDropsPrefsSoColdStartCanRepost() {
        val n = notifier()
        val session = "sess-turn-clear"
        n.onUpdate(session, "host", "S", CodeUpdate.TurnDone("end_turn"), sessionWasRunning = true)
        val key = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, session)
        val idPrefs = ctx.getSharedPreferences("code_away_notif_ids", 0)
        assertTrue(idPrefs.contains(key))
        assertEquals(1, nm.activeNotifications.size)
        // New user turn: allow a later TurnDone. Must drop prefs so process-death seed
        // cannot re-suppress the next finished turn.
        n.clearTurnDoneDedup(session)
        assertFalse(idPrefs.contains(key))
        // Simulate process death: new notifier seeds from prefs (empty for this key).
        nm.cancelAll()
        val cold = notifier()
        cold.onUpdate(session, "host", "S", CodeUpdate.TurnDone("end_turn"), sessionWasRunning = true)
        assertEquals(1, nm.activeNotifications.size)
        assertTrue(CodeAwayFormat.isAwayNotifId(nm.activeNotifications[0].id))
    }

    @Test
    fun cancelSessionAfterClearTurnDoneDedupClearsShade() {
        val n = notifier()
        val session = "sess-cancel-after-clear"
        n.onUpdate(session, "host", "S", CodeUpdate.TurnDone("end_turn"), sessionWasRunning = true)
        val key = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, session)
        val idPrefs = ctx.getSharedPreferences("code_away_notif_ids", 0)
        assertEquals(1, nm.activeNotifications.size)
        assertTrue(idPrefs.contains(key))
        // New user turn: clearTurnDoneDedup drops posted + prefs, keeps in-memory id.
        n.clearTurnDoneDedup(session)
        assertFalse(idPrefs.contains(key))
        assertEquals("shade stays until cancel/open/next TurnDone", 1, nm.activeNotifications.size)
        // Forget / open-in-app must still find the allocation via keyToId.
        n.cancelSession(session)
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test
    fun coldCancelAfterClearTurnDoneDedupClearsSurvivingShade() {
        val n = notifier()
        val session = "sess-cold-cancel"
        n.onUpdate(session, "host", "S", CodeUpdate.TurnDone("end_turn"), sessionWasRunning = true)
        val id = nm.activeNotifications.single().id
        n.clearTurnDoneDedup(session)
        val idPrefs = ctx.getSharedPreferences("code_away_notif_ids", 0)
        val key = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, session)
        val holdKey = CodeAwayFormat.holdPrefKey(key)
        // One commit: live dedup row gone, shade id parked beside it.
        assertFalse(idPrefs.contains(key))
        assertEquals(id, idPrefs.getInt(holdKey, Int.MIN_VALUE))
        assertFalse(ctx.getSharedPreferences("code_away_shade_hold", 0).contains(key))
        // Process death: memory maps gone, dedup row empty, shade still up.
        val cold = notifier()
        cold.cancelSession(session)
        assertEquals(0, nm.activeNotifications.size)
        assertFalse(idPrefs.contains(holdKey))
    }

    @Test
    fun coldTurnDoneAfterClearReusesHeldShadeId() {
        val n = notifier()
        val session = "sess-cold-reuse"
        n.onUpdate(session, "host", "S", CodeUpdate.TurnDone("end_turn"), sessionWasRunning = true)
        val id = nm.activeNotifications.single().id
        n.clearTurnDoneDedup(session)
        val cold = notifier()
        cold.onUpdate(session, "host", "S", CodeUpdate.TurnDone("end_turn"), sessionWasRunning = true)
        assertEquals(1, nm.activeNotifications.size)
        assertEquals(id, nm.activeNotifications.single().id)
        val key = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, session)
        val idPrefs = ctx.getSharedPreferences("code_away_notif_ids", 0)
        assertFalse(idPrefs.contains(CodeAwayFormat.holdPrefKey(key)))
        assertTrue(idPrefs.contains(key))
        assertFalse(ctx.getSharedPreferences("code_away_shade_hold", 0).contains(key))
    }

    @Test
    fun legacyHoldPlusLiveRowStillRepostsSameId() {
        // Kill between the old two commits: shade-hold file written, dedup row still present.
        // Seeding must not swallow the next finished turn.
        val session = "sess-legacy-split"
        val key = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, session)
        val id = CodeAwayFormat.NOTIF_ID_BASE + 0x44
        ctx.getSharedPreferences("code_away_notif_ids", 0).edit().putInt(key, id).commit()
        ctx.getSharedPreferences("code_away_shade_hold", 0).edit().putInt(key, id).commit()
        nm.cancelAll()
        val cold = notifier()
        cold.onUpdate(session, "host", "After", CodeUpdate.TurnDone("end_turn"), sessionWasRunning = true)
        assertEquals(1, nm.activeNotifications.size)
        assertEquals(id, nm.activeNotifications.single().id)
    }

    @Test
    fun userDismissClearsOpenTokenWhenNothingRemains() {
        val n = notifier()
        val session = "sess-token"
        n.onUpdate(session, "host", "S", CodeUpdate.Upsert(approval()), sessionWasRunning = true)
        val key = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.APPROVAL, session, "r1")
        val token = n.issueOpenToken(session)
        nm.cancelAll()
        n.onUserDismissed(key)
        assertFalse(n.consumeOpenToken(session, token))
    }

    @Test
    fun userDismissKeepsTokenWhileAnotherApprovalRemains() {
        val n = notifier()
        val session = "sess:with:colon"
        n.onUpdate(session, "host", "S", CodeUpdate.Upsert(approval("r1")), sessionWasRunning = true)
        n.onUpdate(session, "host", "S", CodeUpdate.Upsert(approval("r2")), sessionWasRunning = true)
        val key = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.APPROVAL, session, "r1")
        val token = n.issueOpenToken(session)
        assertEquals(2, nm.activeNotifications.size)
        // Swipe removes only that shade. cancelAll would also drop the one that should stay.
        val id = nm.activeNotifications.first { sbn ->
            Shadows.shadowOf(sbn.notification.deleteIntent).savedIntent
                .getStringExtra(CodeAwayNotifier.EXTRA_DEDUP_KEY) == key
        }.id
        nm.cancel(id)
        n.onUserDismissed(key)
        assertTrue(n.consumeOpenToken(session, token))
    }

    @Test
    fun shorterSessionTurnDoesNotDropLongerApproval() {
        val n = notifier()
        n.onUpdate("ab", "host", "A", CodeUpdate.Upsert(approval("r1")), sessionWasRunning = true)
        n.onUpdate("ab:cd", "host", "B", CodeUpdate.Upsert(approval("r2")), sessionWasRunning = true)
        assertEquals(setOf("ab", "ab:cd"), showingSessions())
        n.onUpdate("ab", "host", "A", CodeUpdate.TurnDone("end_turn"), sessionWasRunning = true)
        assertTrue("longer approval must stay", "ab:cd" in showingSessions())
        assertTrue("shorter turn still alerts", "ab" in showingSessions())
        n.cancelSession("ab")
        assertEquals(setOf("ab:cd"), showingSessions())
    }

    @Test
    fun swipeShorterSessionClearsItsTokenWhenLongerRemains() {
        val n = notifier()
        n.onUpdate("ab", "host", "A", CodeUpdate.Upsert(approval("r1")), sessionWasRunning = true)
        n.onUpdate("ab:cd", "host", "B", CodeUpdate.Upsert(approval("r2")), sessionWasRunning = true)
        val tokenAb = n.issueOpenToken("ab")
        val tokenCd = n.issueOpenToken("ab:cd")
        val key = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.APPROVAL, "ab", "r1")
        n.onUserDismissed(key)
        assertFalse(n.consumeOpenToken("ab", tokenAb))
        assertTrue(n.consumeOpenToken("ab:cd", tokenCd))
    }

    @Test
    fun evictingOldestShadeClearsItsOpenToken() {
        val n = notifier()
        val limit = CodeAwayNotifier.AWAY_SHADE_LIMIT
        n.onUpdate("s0", "host", "S", CodeUpdate.TurnDone("end_turn"), sessionWasRunning = true)
        val token0 = n.issueOpenToken("s0")
        for (i in 1..limit) {
            n.onUpdate("s$i", "host", "S", CodeUpdate.TurnDone("end_turn"), sessionWasRunning = true)
        }
        assertEquals(limit, nm.activeNotifications.size)
        assertFalse(n.consumeOpenToken("s0", token0))
        assertTrue(n.consumeOpenToken("s1", n.issueOpenToken("s1")))
    }

    @Test
    fun cancelApprovalClearsOpenTokenWhenNothingRemains() {
        val n = notifier()
        val session = "sess-allow"
        n.onUpdate(session, "host", "S", CodeUpdate.Upsert(approval()), sessionWasRunning = true)
        val token = n.issueOpenToken(session)
        n.cancelApproval(session, "r1")
        assertEquals(0, nm.activeNotifications.size)
        assertFalse(n.consumeOpenToken(session, token))
    }

    @Test
    fun cancelApprovalKeepsTokenWhileAnotherApprovalRemains() {
        val n = notifier()
        val session = "sess-two-approvals"
        n.onUpdate(session, "host", "S", CodeUpdate.Upsert(approval("r1")), sessionWasRunning = true)
        n.onUpdate(session, "host", "S", CodeUpdate.Upsert(approval("r2")), sessionWasRunning = true)
        val token = n.issueOpenToken(session)
        n.cancelApproval(session, "r1")
        assertEquals(1, nm.activeNotifications.size)
        assertTrue(n.consumeOpenToken(session, token))
    }

    @Test
    fun cancelledTurnClearsTokenWhenApprovalWasTheOnlyShade() {
        val n = notifier()
        val session = "sess-cancelled"
        n.onUpdate(session, "host", "S", CodeUpdate.Upsert(approval()), sessionWasRunning = true)
        val token = n.issueOpenToken(session)
        n.onUpdate(session, "host", "S", CodeUpdate.TurnDone("cancelled"), sessionWasRunning = true)
        assertEquals(0, nm.activeNotifications.size)
        assertFalse(n.consumeOpenToken(session, token))
    }

    @Test
    fun finishedTurnKeepsTokenOnTheReplacementShade() {
        val n = notifier()
        val session = "sess-finish"
        n.onUpdate(session, "host", "S", CodeUpdate.Upsert(approval()), sessionWasRunning = true)
        n.onUpdate(session, "host", "S", CodeUpdate.TurnDone("end_turn"), sessionWasRunning = true)
        assertEquals(1, nm.activeNotifications.size)
        val token = Shadows.shadowOf(nm.activeNotifications.single().notification.contentIntent)
            .savedIntent
            .getStringExtra(CodeAwayNotifier.EXTRA_OPEN_TOKEN)
        assertTrue(n.consumeOpenToken(session, token))
    }

    @Test
    fun cancelShorterApprovalDoesNotClearLongerToken() {
        val n = notifier()
        n.onUpdate("ab", "host", "A", CodeUpdate.Upsert(approval("r1")), sessionWasRunning = true)
        n.onUpdate("ab:cd", "host", "B", CodeUpdate.Upsert(approval("r2")), sessionWasRunning = true)
        val tokenAb = n.issueOpenToken("ab")
        val tokenCd = n.issueOpenToken("ab:cd")
        n.cancelApproval("ab", "r1")
        assertFalse(n.consumeOpenToken("ab", tokenAb))
        assertTrue(n.consumeOpenToken("ab:cd", tokenCd))
        assertEquals(setOf("ab:cd"), showingSessions())
    }

    @Test
    fun dedupClearedShadesStillCountTowardCap() {
        val n = notifier()
        val limit = CodeAwayNotifier.AWAY_SHADE_LIMIT
        n.onUpdate("s0", "host", "S", CodeUpdate.TurnDone("end_turn"), sessionWasRunning = true)
        val token0 = n.issueOpenToken("s0")
        for (i in 1 until limit) {
            n.onUpdate("s$i", "host", "S", CodeUpdate.TurnDone("end_turn"), sessionWasRunning = true)
        }
        assertEquals(limit, nm.activeNotifications.size)
        for (i in 0 until limit) n.clearTurnDoneDedup("s$i")
        assertEquals("next prompt leaves the shade up", limit, nm.activeNotifications.size)
        n.onUpdate("s$limit", "host", "S", CodeUpdate.TurnDone("end_turn"), sessionWasRunning = true)
        assertEquals(limit, nm.activeNotifications.size)
        assertFalse("oldest parked shade is evicted", "s0" in showingSessions())
        assertTrue("s$limit" in showingSessions())
        assertFalse(n.consumeOpenToken("s0", token0))
        assertTrue(n.consumeOpenToken("s1", n.issueOpenToken("s1")))
    }

    @Test
    fun cancelWithoutDismissLetsTheSameApprovalAlertAgain() {
        val n = notifier()
        val session = "sess-no-swipe"
        n.onUpdate(session, "host", "S", CodeUpdate.Upsert(approval()), sessionWasRunning = true)
        val token = n.issueOpenToken(session)
        val id = nm.activeNotifications.single().id
        // System cancel does not run the swipe handler. The prefs row used to suppress the next alert.
        nm.cancel(id)
        n.onUpdate(session, "host", "S", CodeUpdate.Upsert(approval()), sessionWasRunning = true)
        assertEquals(1, nm.activeNotifications.size)
        assertEquals(id, nm.activeNotifications.single().id)
        assertTrue(n.consumeOpenToken(session, token))
    }

    @Test
    fun coldStartDropsTokenForAShadeThatIsAlreadyGone() {
        val n = notifier()
        n.onUpdate("live", "host", "L", CodeUpdate.Upsert(approval("r-live")), sessionWasRunning = true)
        n.onUpdate("dead", "host", "D", CodeUpdate.Upsert(approval("r-dead")), sessionWasRunning = true)
        val liveToken = n.issueOpenToken("live")
        val deadToken = n.issueOpenToken("dead")
        val deadId = nm.activeNotifications.first { sbn ->
            Shadows.shadowOf(sbn.notification.contentIntent).savedIntent
                .getStringExtra(CodeAwayNotifier.EXTRA_SESSION_ID) == "dead"
        }.id
        nm.cancel(deadId)
        val cold = notifier()
        cold.onUpdate("fresh", "host", "F", CodeUpdate.TurnDone("end_turn"), sessionWasRunning = true)
        assertFalse(cold.consumeOpenToken("dead", deadToken))
        assertTrue(cold.consumeOpenToken("live", liveToken))
        assertTrue("live" in showingSessions())
        cold.onUpdate("dead", "host", "D", CodeUpdate.Upsert(approval("r-dead")), sessionWasRunning = true)
        assertTrue("dead" in showingSessions())
        val reposted = nm.activeNotifications.first { sbn ->
            Shadows.shadowOf(sbn.notification.contentIntent).savedIntent
                .getStringExtra(CodeAwayNotifier.EXTRA_SESSION_ID) == "dead"
        }
        assertEquals(deadId, reposted.id)
        val freshToken = Shadows.shadowOf(reposted.notification.contentIntent).savedIntent
            .getStringExtra(CodeAwayNotifier.EXTRA_OPEN_TOKEN)
        assertFalse(freshToken == deadToken)
        assertTrue(cold.consumeOpenToken("dead", freshToken))
    }

    @Test
    fun coldStartKeepsTokenWhileShadeIsUp() {
        val n = notifier()
        val session = "sess-still-up"
        n.onUpdate(session, "host", "S", CodeUpdate.Upsert(approval()), sessionWasRunning = true)
        val token = n.issueOpenToken(session)
        val cold = notifier()
        cold.onUpdate(session, "host", "S", CodeUpdate.Upsert(approval()), sessionWasRunning = true)
        assertEquals(1, nm.activeNotifications.size)
        assertTrue(cold.consumeOpenToken(session, token))
    }

    @Test
    fun coldStartEvictsOldestEvenWhenPrefsIterationChanges() {
        val n = notifier()
        val limit = CodeAwayNotifier.AWAY_SHADE_LIMIT
        val last = limit - 1
        for (i in 0 until limit) {
            n.onUpdate("s$i", "host", "S", CodeUpdate.TurnDone("end_turn"), sessionWasRunning = true)
            n.clearTurnDoneDedup("s$i")
        }
        val idPrefs = ctx.getSharedPreferences("code_away_notif_ids", 0)
        val oldest = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, "s0")
        val newer = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, "s$last")
        val order0 = idPrefs.getInt(CodeAwayFormat.orderPrefKey(oldest), -1)
        val orderLast = idPrefs.getInt(CodeAwayFormat.orderPrefKey(newer), -1)
        assertTrue(order0 > 0 && orderLast > order0)
        rewritePrefsReversed("code_away_notif_ids")
        val cold = notifier()
        cold.onUpdate("s$limit", "host", "S", CodeUpdate.TurnDone("end_turn"), sessionWasRunning = true)
        assertEquals(limit, nm.activeNotifications.size)
        assertFalse("oldest parked shade is the one that goes", "s0" in showingSessions())
        assertTrue("s$last" in showingSessions())
        assertTrue("s$limit" in showingSessions())
    }

    @Test
    fun approvalRequestContainingColonDoesNotReplaceTheLongerSession() {
        val n = notifier()
        n.onUpdate("ab", "host", "A", CodeUpdate.Upsert(approval("cd:r2")), sessionWasRunning = true)
        n.onUpdate("ab:cd", "host", "B", CodeUpdate.Upsert(approval("r2")), sessionWasRunning = true)
        assertEquals(2, nm.activeNotifications.size)
        val tokenAb = n.issueOpenToken("ab")
        val tokenCd = n.issueOpenToken("ab:cd")
        n.cancelApproval("ab", "cd:r2")
        assertEquals(setOf("ab:cd"), showingSessions())
        assertFalse(n.consumeOpenToken("ab", tokenAb))
        assertTrue(n.consumeOpenToken("ab:cd", tokenCd))
    }

    @Test
    fun shadeClearedWithoutASwipeLosesItsTokenWhenAnotherSessionAlerts() {
        val n = notifier()
        n.onUpdate("dead", "host", "D", CodeUpdate.Upsert(approval("r-dead")), sessionWasRunning = true)
        n.onUpdate("live", "host", "L", CodeUpdate.Upsert(approval("r-live")), sessionWasRunning = true)
        val deadToken = n.issueOpenToken("dead")
        val liveToken = n.issueOpenToken("live")
        val deadId = nm.activeNotifications.first { sbn ->
            Shadows.shadowOf(sbn.notification.contentIntent).savedIntent
                .getStringExtra(CodeAwayNotifier.EXTRA_SESSION_ID) == "dead"
        }.id
        nm.cancel(deadId)
        n.onUpdate("other", "host", "O", CodeUpdate.TurnDone("end_turn"), sessionWasRunning = true)
        assertFalse(n.consumeOpenToken("dead", deadToken))
        assertTrue(n.consumeOpenToken("live", liveToken))
        assertTrue("live" in showingSessions())
    }

    @Test
    fun oneDeadShadeKeepsTheTokenWhileItsSiblingIsUp() {
        val n = notifier()
        val session = "sess-sibling"
        n.onUpdate(session, "host", "S", CodeUpdate.Upsert(approval("r1")), sessionWasRunning = true)
        n.onUpdate(session, "host", "S", CodeUpdate.Upsert(approval("r2")), sessionWasRunning = true)
        val token = n.issueOpenToken(session)
        nm.cancel(nm.activeNotifications.first().id)
        n.onUpdate("other", "host", "O", CodeUpdate.TurnDone("end_turn"), sessionWasRunning = true)
        assertTrue(n.consumeOpenToken(session, token))
    }

    @Test
    fun coldStartCountsParkedShadesTowardCap() {
        val n = notifier()
        val limit = CodeAwayNotifier.AWAY_SHADE_LIMIT
        for (i in 0 until limit) {
            n.onUpdate("c$i", "host", "S", CodeUpdate.TurnDone("end_turn"), sessionWasRunning = true)
            n.clearTurnDoneDedup("c$i")
        }
        assertEquals(limit, nm.activeNotifications.size)
        val cold = notifier()
        cold.onUpdate("c$limit", "host", "S", CodeUpdate.TurnDone("end_turn"), sessionWasRunning = true)
        assertEquals(limit, nm.activeNotifications.size)
        assertFalse("c0" in showingSessions())
        assertTrue("c$limit" in showingSessions())
    }

    @Test
    fun cancellingAnUnpostedApprovalDoesNotRemoveACollidingShade() {
        val n = notifier()
        n.onUpdate("live", "host", "L", CodeUpdate.Upsert(approval("r-live")), sessionWasRunning = true)
        val liveId = nm.activeNotifications.single().id
        val token = n.issueOpenToken("live")
        // approval:ghost:40447469 hashes to the same 24-bit id as approval:live:r-live.
        val requestId = "40447469"
        val ghostKey = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.APPROVAL, "ghost", requestId)
        val liveKey = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.APPROVAL, "live", "r-live")
        assertEquals(CodeAwayFormat.notificationId(liveKey), CodeAwayFormat.notificationId(ghostKey))
        assertEquals(liveId, CodeAwayFormat.notificationId(liveKey))
        // Never posted. Cancel still used to nm.cancel the 24-bit hash, which is this shade's id.
        n.cancelApproval("ghost", requestId)
        assertEquals(1, nm.activeNotifications.size)
        assertEquals(liveId, nm.activeNotifications.single().id)
        assertTrue(n.consumeOpenToken("live", token))
    }

    @Test
    fun answerShadeKeepsASlotWhenAwayFillsThePackage() {
        nm.notify(
            CodeAwayNotifier.ANSWER_READY_NOTIF_ID,
            NotificationCompat.Builder(ctx, "ForegroundServiceChannel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Demo")
                .setContentText("Your answer is ready.")
                .build(),
        )
        val n = notifier()
        val limit = CodeAwayNotifier.AWAY_SHADE_LIMIT
        for (i in 0 until limit) {
            n.onUpdate("a$i", "host", "S", CodeUpdate.TurnDone("end_turn"), sessionWasRunning = true)
        }
        assertEquals(CodeAwayNotifier.SYSTEM_PACKAGE_NOTIF_LIMIT, nm.activeNotifications.size)
        n.onUpdate("overflow", "host", "S", CodeUpdate.TurnDone("end_turn"), sessionWasRunning = true)
        assertEquals(CodeAwayNotifier.SYSTEM_PACKAGE_NOTIF_LIMIT, nm.activeNotifications.size)
        assertTrue(nm.activeNotifications.any { it.id == CodeAwayNotifier.ANSWER_READY_NOTIF_ID })
        assertFalse("a0" in showingSessions())
        assertTrue("overflow" in showingSessions())
    }

    /** Rewrite the id prefs newest-key-first so a cold start cannot trust map order. */
    private fun rewritePrefsReversed(name: String) {
        val prefs = ctx.getSharedPreferences(name, 0)
        val all = prefs.all.entries.sortedByDescending { it.key }
        prefs.edit().clear().commit()
        val edit = prefs.edit()
        for ((k, v) in all) {
            when (v) {
                is Int -> edit.putInt(k, v)
                is Boolean -> edit.putBoolean(k, v)
                is String -> edit.putString(k, v)
                is Long -> edit.putLong(k, v)
            }
        }
        edit.commit()
    }

    private fun showingSessions(): Set<String> =
        nm.activeNotifications.mapNotNull { sbn ->
            val content = sbn.notification.contentIntent ?: return@mapNotNull null
            Shadows.shadowOf(content).savedIntent
                .getStringExtra(CodeAwayNotifier.EXTRA_SESSION_ID)
        }.toSet()

}
