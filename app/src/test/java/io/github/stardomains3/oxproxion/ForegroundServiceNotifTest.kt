package io.github.stardomains3.oxproxion

import android.app.ActivityManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
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

    @Test
    fun updateNotificationStatusClearsSpeakingFlag() {
        val prefs = ctx.getSharedPreferences("ForegroundServiceAnswer", 0)
        prefs.edit().putBoolean("speaking", true).commit()
        ForegroundService.updateNotificationStatus(ctx, "Demo Model", "Your answer is ready.")
        assertFalse(prefs.getBoolean("speaking", true))
        assertEquals("Demo Model", prefs.getString("title", null))
    }

    @Test
    fun coldToggleWithSpeakingPrefStopsRatherThanRestart() {
        // Process died mid-Speak: shade still says Stop, prefs still say speaking.
        val prefs = ctx.getSharedPreferences("ForegroundServiceAnswer", 0)
        prefs.edit()
            .putString("title", "Demo Model")
            .putString("text", "Your answer is ready.")
            .putBoolean("speaking", true)
            .commit()
        nm.notify(
            2,
            NotificationCompat.Builder(ctx, "ForegroundServiceChannel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Demo Model")
                .setContentText("Your answer is ready.")
                .build(),
        )
        val intent = Intent(ctx, ForegroundService::class.java).setAction("TOGGLE_TTS_CHANNEL_2")
        Robolectric.buildService(ForegroundService::class.java, intent)
            .create()
            .startCommand(0, 1)
            .destroy()
        assertFalse(
            "cold Stop must clear speaking so a second tap Speaks",
            prefs.getBoolean("speaking", true),
        )
        assertTrue(nm.activeNotifications.any { it.id == 2 })
    }

    @Test
    fun dismissWithoutInstanceClearsSpeakingAndShade() {
        val prefs = ctx.getSharedPreferences("ForegroundServiceAnswer", 0)
        prefs.edit().putBoolean("speaking", true).commit()
        nm.notify(
            2,
            NotificationCompat.Builder(ctx, "ForegroundServiceChannel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Demo Model")
                .build(),
        )
        ForegroundService.dismissNotificationIfNotSpeaking(ctx)
        assertFalse(prefs.getBoolean("speaking", true))
        assertTrue(nm.activeNotifications.none { it.id == 2 })
    }

    @Test
    fun inAppStopFlipsShadeStopBackToSpeak() {
        val prefs = ctx.getSharedPreferences("ForegroundServiceAnswer", 0)
        prefs.edit()
            .putString("title", "Demo Model")
            .putString("text", "Your answer is ready.")
            .commit()
        val intent = Intent(ctx, ForegroundService::class.java).setAction("TOGGLE_TTS_CHANNEL_2")
        val controller = Robolectric.buildService(ForegroundService::class.java, intent)
            .create()
            .startCommand(0, 1)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue("Speak must flip the shade to Stop", prefs.getBoolean("speaking", false))
        val posted = nm.activeNotifications.first { it.id == 2 }.notification
        assertEquals(
            ctx.getString(R.string.notif_action_stop),
            posted.actions[0].title.toString(),
        )
        // In-chat Speak stops shade TTS without going through the shade Stop action.
        ForegroundService.stopTtsSpeaking()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertFalse(prefs.getBoolean("speaking", true))
        val after = nm.activeNotifications.first { it.id == 2 }.notification
        assertEquals(
            "shade must say Speak once the flag is clear",
            ctx.getString(R.string.notif_action_speak),
            after.actions[0].title.toString(),
        )
        controller.destroy()
    }

}
