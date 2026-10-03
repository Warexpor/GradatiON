package io.github.stardomains3.oxproxion

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.commonmark.parser.Parser
import org.commonmark.renderer.text.TextContentRenderer

/**
 * Hosts answer-ready notification actions (Speak / Dismiss / Copy / Open).
 * Does **not** keep a sticky "Running" FGS notification — only "Your answer is ready."
 */
class ForegroundService : Service(), TextToSpeech.OnInitListener {

    private val CHANNEL_ID = "ForegroundServiceChannel"

    private val TOGGLE_TTS_ACTION = "TOGGLE_TTS_CHANNEL_2"
    private val DISMISS_ACTION = "DISMISS_CHANNEL_2"
    private val COPY_ACTION = "COPY_CHANNEL_2"

    private var tts: TextToSpeech? = null
    private var isTtsActive = false
    private var isTtsUpdate = false
    /** Engine finished [onInit]; Speak before this must wait. */
    private var ttsReady = false
    /** Speak was tapped before [ttsReady]; run once init succeeds. */
    private var pendingSpeak = false
    private var lastUpdateTitle: String? = null
    private var lastUpdateText: String? = null

    companion object {
        private const val ANSWER_CHANNEL_ID = "ForegroundServiceChannel"
        private const val ANSWER_NOTIFICATION_ID = 2
        private const val LEGACY_FGS_NOTIFICATION_ID = 1

        private var instance: ForegroundService? = null

        /** Survives [onDestroy] so in-chat Stop can still fix a shade whose service is gone. */
        @Volatile
        private var appContext: Context? = null

        fun stopService() {
            instance?.stop()
        }

        /** Drop legacy sticky "Running" FGS chrome if an older build left it up. */
        fun clearLegacyRunningNotification(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.cancel(LEGACY_FGS_NOTIFICATION_ID)
            try {
                nm.deleteNotificationChannel("ForegroundChannel")
            } catch (_: Exception) {
            }
            // Do not stopService(): Speak starts this service for TTS, and onResume/onCreate
            // call here often. Killing it mid-utterance left Stop dead and cut speech short.
        }

        fun updateNotificationStatus(context: Context, title: String, contentText: String) {
            val app = context.applicationContext
            if (isAppInForeground(app)) return
            if (!answerNotificationsAllowed(app)) return
            ensureAnswerChannel(app)
            instance?.let {
                it.updateNotification(title, contentText)
                return
            }
            postAnswerNotification(app, title, contentText, ttsActive = false, silent = false)
        }

        fun dismissNotificationIfNotSpeaking(context: Context? = null) {
            if (instance != null) {
                instance?.dismissIfNotSpeaking()
            } else if (context != null) {
                context.getSystemService(NotificationManager::class.java)
                    ?.cancel(ANSWER_NOTIFICATION_ID)
                // Opening Chat after a kill drops the shade. A leftover Stop flag would make
                // the next cold toggle Stop instead of Speak.
                clearAnswerSpeaking(context)
            }
            // Always drop leftover sticky "Running" chrome; do not stopService (may be TTS)
            context?.getSystemService(NotificationManager::class.java)
                ?.cancel(LEGACY_FGS_NOTIFICATION_ID)
        }

        fun stopTtsSpeaking() {
            // In-chat Speak stops shade TTS. stopTts(false) must not leave the shade
            // saying Stop: the speaking flag is already clear, so the next shade tap
            // would start speech again.
            val live = instance
            if (live != null) {
                live.stopTts(false)
                return
            }
            // The speak service can die while the shade still says Stop. Clearing only
            // the flag would make the next shade tap start speech again.
            val ctx = appContext ?: return
            stopShadeAfterServiceGone(ctx)
        }

        /**
         * In-chat Stop with no live service. Background keeps the shade and puts Speak
         * back. Foreground, or a shade with no saved title, drops it.
         */
        private fun stopShadeAfterServiceGone(context: Context) {
            clearAnswerSpeaking(context)
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.activeNotifications.none { it.id == ANSWER_NOTIFICATION_ID }) return
            val prefs = context.getSharedPreferences(ANSWER_META_PREFS, Context.MODE_PRIVATE)
            val title = prefs.getString(KEY_ANSWER_TITLE, null)
            val text = prefs.getString(KEY_ANSWER_TEXT, null)
            if (isAppInForeground(context) || title == null || text == null) {
                nm.cancel(ANSWER_NOTIFICATION_ID)
                return
            }
            ensureAnswerChannel(context)
            nm.notify(
                ANSWER_NOTIFICATION_ID,
                buildAnswerNotification(context, title, text, ttsActive = false, silent = true),
            )
        }

        @Volatile
        var isRunningForeground: Boolean = false
            private set

        private fun ensureAnswerChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                ANSWER_CHANNEL_ID,
                context.getString(R.string.notif_channel_answers),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_channel_answers_desc)
            }
            nm.createNotificationChannel(channel)
            // Leave legacy Connectivity channel disabled-looking if it already exists;
            // never recreate a sticky FGS notif for it.
        }

        /**
         * User blocked notifications (or the Answers channel) — do not "post" into silence
         * or remember Speak meta for chrome that never appeared.
         */
        private fun answerNotificationsAllowed(context: Context): Boolean {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return false
            if (!nm.areNotificationsEnabled()) return false
            if (Build.VERSION.SDK_INT >= 33) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return false
            }
            val channel = nm.getNotificationChannel(ANSWER_CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return false
            }
            return true
        }

        private fun isAppInForeground(context: Context): Boolean {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val appProcesses = activityManager.runningAppProcesses ?: return false
            for (appProcess in appProcesses) {
                if (appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                    appProcess.processName == context.packageName
                ) {
                    return true
                }
            }
            return false
        }

        private const val ANSWER_META_PREFS = "ForegroundServiceAnswer"
        private const val KEY_ANSWER_TITLE = "title"
        private const val KEY_ANSWER_TEXT = "text"
        private const val KEY_ANSWER_SPEAKING = "speaking"

        /** Survives a cold Speak tap when the answer was posted without a live service instance. */
        private fun rememberAnswerMeta(
            context: Context,
            title: String,
            contentText: String,
            speaking: Boolean,
        ) {
            // commit: Speak often starts this service after a kill; apply() can still be in flight.
            // speaking must survive too: shade can still say Stop after process death, and a cold
            // TOGGLE must Stop rather than start speech again.
            context.getSharedPreferences(ANSWER_META_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ANSWER_TITLE, title)
                .putString(KEY_ANSWER_TEXT, contentText)
                .putBoolean(KEY_ANSWER_SPEAKING, speaking)
                .commit()
        }

        private fun answerSpeakingPref(context: Context): Boolean =
            context.getSharedPreferences(ANSWER_META_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ANSWER_SPEAKING, false)

        private fun clearAnswerSpeaking(context: Context) {
            context.getSharedPreferences(ANSWER_META_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ANSWER_SPEAKING, false)
                .commit()
        }

        private fun postAnswerNotification(
            context: Context,
            title: String,
            contentText: String,
            ttsActive: Boolean,
            silent: Boolean
        ) {
            rememberAnswerMeta(context, title, contentText, speaking = ttsActive)
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.notify(
                ANSWER_NOTIFICATION_ID,
                buildAnswerNotification(context, title, contentText, ttsActive, silent)
            )
        }

        private fun buildAnswerNotification(
            context: Context,
            title: String,
            contentText: String,
            ttsActive: Boolean,
            silent: Boolean
        ): Notification {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("from_notification", true)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent ?: Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("from_notification", true)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val togglePendingIntent = PendingIntent.getService(
                context, 10,
                Intent(context, ForegroundService::class.java).setAction("TOGGLE_TTS_CHANNEL_2"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val dismissPendingIntent = PendingIntent.getService(
                context, 11,
                Intent(context, ForegroundService::class.java).setAction("DISMISS_CHANNEL_2"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val copyPendingIntent = PendingIntent.getService(
                context, 12,
                Intent(context, ForegroundService::class.java).setAction("COPY_CHANNEL_2"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, ANSWER_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(contentText)
                .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcherrobot))
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentIntent(pendingIntent)
                .setOngoing(false)
                .setDeleteIntent(dismissPendingIntent)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)

            if (!ttsActive) {
                builder.addAction(android.R.drawable.ic_media_play, context.getString(R.string.notif_action_speak), togglePendingIntent)
            } else {
                builder.addAction(android.R.drawable.ic_media_pause, context.getString(R.string.notif_action_stop), togglePendingIntent)
            }

            val mainPrefs = context.getSharedPreferences("MainAppPrefs", Context.MODE_PRIVATE)
            val useCopyButton = mainPrefs.getBoolean("use_copy_button", false)
            val useCopyButton2 = mainPrefs.getBoolean("use_copy_button2", false)

            if (useCopyButton2) {
                builder.addAction(android.R.drawable.ic_input_get, context.getString(R.string.action_copy), copyPendingIntent)
            } else {
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.notif_action_dismiss), dismissPendingIntent)
            }

            if (useCopyButton) {
                builder.addAction(android.R.drawable.ic_input_get, context.getString(R.string.action_copy), copyPendingIntent)
            } else {
                builder.addAction(android.R.drawable.ic_menu_info_details, context.getString(R.string.notif_action_open), pendingIntent)
            }

            if (silent) {
                builder.setSilent(true).setOnlyAlertOnce(true)
            }

            return builder.build()
        }
    }

    private fun stop() {
        try {
            stopSelf()
        } catch (_: Exception) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        appContext = applicationContext
        initTTS()
    }

    private fun initTTS() {
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    onShadeUtteranceFinished(utteranceId)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onShadeUtteranceFinished(utteranceId)
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    // Audio focus loss calls onStop, not onDone. Leaving Stop up made the
                    // next tap look like Stop while nothing was speaking.
                    onShadeUtteranceFinished(utteranceId)
                }
            })
            if (pendingSpeak) {
                pendingSpeak = false
                speakNow()
            }
        } else {
            ttsReady = false
            pendingSpeak = false
            isTtsActive = false
            // Speak had already flipped the shade to Stop; put Speak back.
            restoreLastUpdateFromPrefs()
            refreshAnswerChrome(silent = true)
        }
    }

    /** Utterance id the shade is reading. A late end for an older id must not redraw it. */
    internal var shadeUtteranceId: String? = null

    /** Bumps on every Speak. A fixed id let a late end for the previous reading match this one. */
    private var shadeUtteranceSerial = 0

    private fun nextShadeUtteranceId(): String = "fg_tts_${++shadeUtteranceSerial}"

    private var endingUtterance = false

    /**
     * Speech ended, failed, or was interrupted. A shade the user already copied or
     * dismissed stays gone: [tts.stop] can deliver this after that cancel.
     */
    internal fun onShadeUtteranceFinished(utteranceId: String?) {
        if (utteranceId == null || utteranceId != shadeUtteranceId) return
        if (endingUtterance) return
        endingUtterance = true
        try {
            shadeUtteranceId = null
            isTtsActive = false
            pendingSpeak = false
            clearAnswerSpeaking(this)
            if (!isNotificationActive(ANSWER_NOTIFICATION_ID)) return
            tts?.stop()
            restoreLastUpdateFromPrefs()
            if (isAppInForeground()) {
                getSystemService(NotificationManager::class.java).cancel(ANSWER_NOTIFICATION_ID)
            } else {
                refreshAnswerChrome(silent = true)
            }
        } finally {
            endingUtterance = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ignore the stop callback. A kill mid-utterance must keep the speaking flag.
        shadeUtteranceId = null
        tts?.stop()
        tts?.shutdown()
        isTtsActive = false
        ttsReady = false
        pendingSpeak = false
        isRunningForeground = false
        instance = null
        // Leave KEY_ANSWER_SPEAKING alone: a kill mid-utterance must keep speaking=true so a
        // cold Stop tap does not restart TTS. Dismiss/Copy/stopTts/refresh clear it explicitly.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                TOGGLE_TTS_ACTION -> {
                    // Shade may still say Stop after a kill (notif survives; in-memory isTtsActive does not).
                    if (isTtsActive || answerSpeakingPref(this)) {
                        stopTts(true)
                        if (isAppInForeground()) {
                            getSystemService(NotificationManager::class.java).cancel(ANSWER_NOTIFICATION_ID)
                        }
                    } else {
                        startTtsForChannel2()
                    }
                    return START_NOT_STICKY
                }
                DISMISS_ACTION -> {
                    pendingSpeak = false
                    shadeUtteranceId = null
                    clearAnswerSpeaking(this)
                    getSystemService(NotificationManager::class.java).cancel(ANSWER_NOTIFICATION_ID)
                    tts?.stop()
                    isTtsActive = false
                    stopSelf()
                    return START_NOT_STICKY
                }
                COPY_ACTION -> {
                    pendingSpeak = false
                    shadeUtteranceId = null
                    isTtsActive = false
                    clearAnswerSpeaking(this)
                    copyLastResponseToClipboard()
                    // Cancel before stop so a synchronous utterance end cannot post the shade again.
                    getSystemService(NotificationManager::class.java).cancel(ANSWER_NOTIFICATION_ID)
                    tts?.stop()
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }

        // No sticky "Running" notification. Ignore bare starts (answer actions handled above).
        ensureAnswerChannel(this)
        getSystemService(NotificationManager::class.java).cancel(LEGACY_FGS_NOTIFICATION_ID)
        isRunningForeground = false
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    fun updateNotification(title: String, contentText: String) {
        if (!isAppInForeground()) {
            lastUpdateTitle = title
            lastUpdateText = contentText
            if (isTtsActive || pendingSpeak) {
                pendingSpeak = false
                shadeUtteranceId = null
                tts?.stop()
                isTtsActive = false
            }
            updateNotificationWithChannel(title, contentText)
        }
    }

    private fun dismissIfNotSpeaking() {
        if (!isTtsActive && isNotificationActive(ANSWER_NOTIFICATION_ID)) {
            clearAnswerSpeaking(this)
            getSystemService(NotificationManager::class.java).cancel(ANSWER_NOTIFICATION_ID)
        }
    }

    private fun stopTts(updateNotif: Boolean) {
        pendingSpeak = false
        // Drop the id first so stop()'s onStop cannot redraw the shade we are updating.
        shadeUtteranceId = null
        tts?.stop()
        isTtsActive = false
        clearAnswerSpeaking(this)
        restoreLastUpdateFromPrefs()
        if (updateNotif && lastUpdateTitle != null && lastUpdateText != null && isNotificationActive(ANSWER_NOTIFICATION_ID)) {
            isTtsUpdate = true
            updateNotificationWithChannel(lastUpdateTitle!!, lastUpdateText!!)
            isTtsUpdate = false
        } else if (!updateNotif && isNotificationActive(ANSWER_NOTIFICATION_ID)) {
            // Flag is clear. A shade that still says Stop would restart TTS on the next tap.
            if (isAppInForeground() || lastUpdateTitle == null || lastUpdateText == null) {
                getSystemService(NotificationManager::class.java).cancel(ANSWER_NOTIFICATION_ID)
            } else {
                isTtsUpdate = true
                updateNotificationWithChannel(lastUpdateTitle!!, lastUpdateText!!)
                isTtsUpdate = false
            }
        }
    }

    private fun startTtsForChannel2() {
        restoreLastUpdateFromPrefs()
        // Answer chrome is often posted without a live service; Speak starts us cold.
        // TextToSpeech is async — speak before onInit is a no-op, so queue until ready.
        if (!ttsReady) {
            pendingSpeak = true
            isTtsActive = true
            refreshAnswerChrome(silent = true)
            return
        }
        speakNow()
    }

    private fun speakNow() {
        val lastResponse = getLastAiResponseForChannel(2)
        if (lastResponse == null) {
            pendingSpeak = false
            isTtsActive = false
            refreshAnswerChrome(silent = true)
            return
        }
        val cleanText = stripMarkdownWithCommonMark(lastResponse)
        val utteranceId = nextShadeUtteranceId()
        shadeUtteranceId = utteranceId
        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        isTtsActive = true
        refreshAnswerChrome(silent = true)
    }

    private fun refreshAnswerChrome(silent: Boolean) {
        val title = lastUpdateTitle ?: return
        val text = lastUpdateText ?: return
        isTtsUpdate = silent
        updateNotificationWithChannel(title, text)
        isTtsUpdate = false
    }

    private fun restoreLastUpdateFromPrefs() {
        if (lastUpdateTitle != null && lastUpdateText != null) return
        val prefs = getSharedPreferences(ANSWER_META_PREFS, Context.MODE_PRIVATE)
        lastUpdateTitle = prefs.getString(KEY_ANSWER_TITLE, null)
        lastUpdateText = prefs.getString(KEY_ANSWER_TEXT, null)
    }

    private fun stripMarkdownWithCommonMark(text: String): String {
        return try {
            val parser = Parser.builder().build()
            val document = parser.parse(text)
            TextContentRenderer.builder().build().render(document).trim()
        } catch (_: Exception) {
            text.replace(Regex("\\*\\*|__|`|\\[|\\]"), "")
        }
    }

    private fun copyLastResponseToClipboard() {
        val lastResponse = getLastAiResponseForChannel(2) ?: return
        val cleanText = stripMarkdownWithCommonMark(lastResponse)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AI Response", cleanText))
    }

    private fun isAppInForeground(): Boolean = isAppInForeground(this)

    private fun isNotificationActive(notificationId: Int): Boolean {
        val active = getSystemService(NotificationManager::class.java).activeNotifications
        return active.any { it.id == notificationId }
    }

    private fun updateNotificationWithChannel(title: String, contentText: String) {
        lastUpdateTitle = title
        lastUpdateText = contentText
        postAnswerNotification(this, title, contentText, isTtsActive, silent = isTtsUpdate)
    }

    private fun getLastAiResponseForChannel(channelId: Int): String? {
        val prefs: SharedPreferences =
            TolerantPrefs(getSharedPreferences("MainAppPrefs", Context.MODE_PRIVATE))
        return prefs.getString("last_ai_response_channel_$channelId", null)
    }
}
