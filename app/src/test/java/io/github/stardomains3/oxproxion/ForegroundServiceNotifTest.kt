package io.github.stardomains3.oxproxion

import android.app.ActivityManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
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
 * Answer-ready notification helpers: legacy clear must not wipe the answer shade.
 * Run: ./gradlew :app:testDebugUnitTest --tests '*ForegroundServiceNotifTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class ForegroundServiceNotifTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var nm: NotificationManager

    @Before
    fun setUp() {
        nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancelAll()
        // Drop leftover channel state across tests (importance is sticky once created).
        runCatching { nm.deleteNotificationChannel("ForegroundServiceChannel") }
        nm.createNotificationChannel(
            NotificationChannel("ForegroundServiceChannel", "Answers", NotificationManager.IMPORTANCE_DEFAULT),
        )
        ctx.getSharedPreferences("ForegroundServiceAnswer", 0).edit().clear().commit()
        // updateNotificationStatus no-ops while foreground; force background for away posts.
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.RunningAppProcessInfo().apply {
            processName = ctx.packageName
            importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND
            pid = android.os.Process.myPid()
        }
        Shadows.shadowOf(am).setProcesses(listOf(info))
        Shadows.shadowOf(ctx).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun clearLegacyLeavesAnswerNotification() {
        val legacy = NotificationCompat.Builder(ctx, "ForegroundServiceChannel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Running")
            .build()
        val answer = NotificationCompat.Builder(ctx, "ForegroundServiceChannel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Model")
            .setContentText("Your answer is ready.")
            .build()
        nm.notify(1, legacy)
        nm.notify(2, answer)
        ForegroundService.clearLegacyRunningNotification(ctx)
        val ids = nm.activeNotifications.map { it.id }.toSet()
        assertFalse("legacy sticky id must go", 1 in ids)
        assertTrue("answer-ready id must stay", 2 in ids)
    }

    @Test
    fun updateNotificationStatusRemembersTitleForColdSpeak() {
        ForegroundService.updateNotificationStatus(ctx, "Demo Model", "Your answer is ready.")
        val prefs = ctx.getSharedPreferences("ForegroundServiceAnswer", 0)
        assertEquals("Demo Model", prefs.getString("title", null))
        assertEquals("Your answer is ready.", prefs.getString("text", null))
        assertTrue(nm.activeNotifications.any { it.id == 2 })
    }

    @Test
    fun blockedAnswerChannelSkipsPost() {
        nm.deleteNotificationChannel("ForegroundServiceChannel")
        nm.createNotificationChannel(
            NotificationChannel(
                "ForegroundServiceChannel",
                "Answers",
                NotificationManager.IMPORTANCE_NONE,
            ),
        )
        ForegroundService.updateNotificationStatus(ctx, "Demo Model", "Your answer is ready.")
        assertTrue(nm.activeNotifications.none { it.id == 2 })
        val prefs = ctx.getSharedPreferences("ForegroundServiceAnswer", 0)
        assertEquals(null, prefs.getString("title", null))
    }
}
