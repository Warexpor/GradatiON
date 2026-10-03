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

    @Test
    fun inAppStopAfterServiceGoneFlipsShadeBackToSpeak() {
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
        assertTrue(prefs.getBoolean("speaking", false))
        controller.destroy()
        assertTrue(
            "service death leaves the Stop flag for a cold shade tap",
            prefs.getBoolean("speaking", false),
        )
        ForegroundService.stopTtsSpeaking()
        assertFalse(prefs.getBoolean("speaking", true))
        val after = nm.activeNotifications.first { it.id == 2 }.notification
        assertEquals(
            ctx.getString(R.string.notif_action_speak),
            after.actions[0].title.toString(),
        )
    }

    @Test
    fun inAppStopAfterServiceGoneDropsShadeInForeground() {
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
        controller.destroy()
        val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.RunningAppProcessInfo().apply {
            processName = ctx.packageName
            importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            pid = android.os.Process.myPid()
        }
        Shadows.shadowOf(am).setProcesses(listOf(info))
        ForegroundService.stopTtsSpeaking()
        assertFalse(prefs.getBoolean("speaking", true))
        assertTrue(nm.activeNotifications.none { it.id == 2 })
    }

    @Test
    fun copyThenUtteranceEndDoesNotRepostShade() {
        val prefs = ctx.getSharedPreferences("ForegroundServiceAnswer", 0)
        prefs.edit()
            .putString("title", "Demo Model")
            .putString("text", "Your answer is ready.")
            .commit()
        nm.notify(
            2,
            NotificationCompat.Builder(ctx, "ForegroundServiceChannel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Demo Model")
                .setContentText("Your answer is ready.")
                .build(),
        )
        val copy = Intent(ctx, ForegroundService::class.java).setAction("COPY_CHANNEL_2")
        val controller = Robolectric.buildService(ForegroundService::class.java, copy)
            .create()
            .startCommand(0, 1)
        val service = controller.get()
        assertTrue(nm.activeNotifications.none { it.id == 2 })
        // The utterance callback can still be in flight after Copy cancelled the shade.
        service.shadeUtteranceId = "fg_tts"
        service.onShadeUtteranceFinished("fg_tts")
        assertTrue(nm.activeNotifications.none { it.id == 2 })
        assertFalse(prefs.getBoolean("speaking", false))
        controller.destroy()
    }

    @Test
    fun interruptedShadeSpeechReturnsToSpeak() {
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
                .addAction(0, ctx.getString(R.string.notif_action_stop), null)
                .build(),
        )
        val intent = Intent(ctx, ForegroundService::class.java)
        val controller = Robolectric.buildService(ForegroundService::class.java, intent)
            .create()
            .startCommand(0, 1)
        val service = controller.get()
        service.shadeUtteranceId = "fg_tts"
        service.onShadeUtteranceFinished("fg_tts")
        assertFalse(prefs.getBoolean("speaking", true))
        val after = nm.activeNotifications.first { it.id == 2 }.notification
        assertEquals(
            ctx.getString(R.string.notif_action_speak),
            after.actions[0].title.toString(),
        )
        controller.destroy()
    }

    @Test
    fun lateUtteranceEndDoesNotClearTheNextReading() {
        val prefs = ctx.getSharedPreferences("ForegroundServiceAnswer", 0)
        prefs.edit()
            .putString("title", "Demo Model")
            .putString("text", "Your answer is ready.")
            .commit()
        ctx.getSharedPreferences("MainAppPrefs", 0).edit()
            .putString("last_ai_response_channel_2", "Your answer is ready.")
            .commit()
        val intent = Intent(ctx, ForegroundService::class.java).setAction("TOGGLE_TTS_CHANNEL_2")
        val controller = Robolectric.buildService(ForegroundService::class.java, intent)
            .create()
            .startCommand(0, 1)
        val service = controller.get()
        // onInit is what assigns the utterance id. The shadow then posts completion;
        // idling here would deliver that end and clear the id before the next Speak.
        service.onInit(android.speech.tts.TextToSpeech.SUCCESS)
        val firstId = service.shadeUtteranceId
        assertEquals("fg_tts_1", firstId)
        ForegroundService.stopTtsSpeaking()
        service.onStartCommand(intent, 0, 2)
        val secondId = service.shadeUtteranceId
        assertEquals("fg_tts_2", secondId)
        // The first reading's end can still arrive after Speak has started again.
        service.onShadeUtteranceFinished(firstId)
        assertTrue(prefs.getBoolean("speaking", false))
        val after = nm.activeNotifications.first { it.id == 2 }.notification
        assertEquals(
            ctx.getString(R.string.notif_action_stop),
            after.actions[0].title.toString(),
        )
        controller.destroy()
    }

    @Test
    fun refusedSpeakDoesNotLeaveTheShadeOnStop() {
        val prefs = ctx.getSharedPreferences("ForegroundServiceAnswer", 0)
        prefs.edit()
            .putString("title", "Demo Model")
            .putString("text", "Your answer is ready.")
            .commit()
        ctx.getSharedPreferences("MainAppPrefs", 0).edit()
            .putString("last_ai_response_channel_2", "The file is updated.")
            .commit()
        val intent = Intent(ctx, ForegroundService::class.java).setAction("TOGGLE_TTS_CHANNEL_2")
        val controller = Robolectric.buildService(ForegroundService::class.java, intent).create()
        val service = controller.get()
        service.speakCallForTest = { _, _ -> android.speech.tts.TextToSpeech.ERROR }
        controller.startCommand(0, 1)
        service.onInit(android.speech.tts.TextToSpeech.SUCCESS)
        assertFalse(prefs.getBoolean("speaking", true))
        val after = nm.activeNotifications.first { it.id == 2 }.notification
        assertEquals(
            ctx.getString(R.string.notif_action_speak),
            after.actions[0].title.toString(),
        )
        controller.destroy()
    }

    @Test
    fun blankMarkdownDoesNotLeaveTheShadeOnStop() {
        val prefs = ctx.getSharedPreferences("ForegroundServiceAnswer", 0)
        prefs.edit()
            .putString("title", "Demo Model")
            .putString("text", "Your answer is ready.")
            .commit()
        ctx.getSharedPreferences("MainAppPrefs", 0).edit()
            .putString("last_ai_response_channel_2", " \n\n ")
            .commit()
        val intent = Intent(ctx, ForegroundService::class.java).setAction("TOGGLE_TTS_CHANNEL_2")
        val controller = Robolectric.buildService(ForegroundService::class.java, intent)
            .create()
            .startCommand(0, 1)
        // Pending Speak shows Stop until the engine is ready. Blank text must put Speak back.
        controller.get().onInit(android.speech.tts.TextToSpeech.SUCCESS)
        assertFalse(prefs.getBoolean("speaking", true))
        val after = nm.activeNotifications.first { it.id == 2 }.notification
        assertEquals(
            ctx.getString(R.string.notif_action_speak),
            after.actions[0].title.toString(),
        )
        controller.destroy()
    }

    @Test
    fun lastAiResponseIsOnDiskBeforeReturn() {
        val helper = SharedPreferencesHelper(ctx)
        helper.mainPrefs.edit().clear().commit()
        helper.saveLastAiResponseForChannel(2, "final answer")
        val file = java.io.File(ctx.applicationInfo.dataDir, "shared_prefs/MainAppPrefs.xml")
        assertTrue("speak text must be committed, not only applied", file.isFile)
        assertTrue(file.readText().contains("final answer"))
    }

}
