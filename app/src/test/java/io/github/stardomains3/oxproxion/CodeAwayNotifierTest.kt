package io.github.stardomains3.oxproxion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
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
        nm.cancelAll()
        n.onUserDismissed(key)
        assertTrue(n.consumeOpenToken(session, token))
    }

}
