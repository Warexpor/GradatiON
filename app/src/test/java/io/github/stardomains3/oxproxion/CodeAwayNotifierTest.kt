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
}
