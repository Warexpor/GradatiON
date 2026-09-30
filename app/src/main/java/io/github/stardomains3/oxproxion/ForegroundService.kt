package io.github.stardomains3.oxproxion

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
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
    private var lastUpdateTitle: String? = null
    private var lastUpdateText: String? = null

    companion object {
        private const val ANSWER_CHANNEL_ID = "ForegroundServiceChannel"
        private const val ANSWER_NOTIFICATION_ID = 2
        private const val LEGACY_FGS_NOTIFICATION_ID = 1

        private var instance: ForegroundService? = null

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
            stopService()
        }

        fun updateNotificationStatus(context: Context, title: String, contentText: String) {
            val app = context.applicationContext
            if (isAppInForeground(app)) return
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
            } else {
                context?.getSystemService(NotificationManager::class.java)
                    ?.cancel(ANSWER_NOTIFICATION_ID)
            }
            // Always drop leftover sticky "Running" chrome; do not stopService (may be TTS)
            context?.getSystemService(NotificationManager::class.java)
                ?.cancel(LEGACY_FGS_NOTIFICATION_ID)
        }

        fun stopTtsSpeaking() {
            instance?.stopTts(false)
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

        private fun postAnswerNotification(
            context: Context,
            title: String,
            contentText: String,
            ttsActive: Boolean,
            silent: Boolean
        ) {
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
        initTTS()
    }

    private fun initTTS() {
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "fg_tts") {
                        handleTtsFinished()
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == "fg_tts") {
                        handleTtsFinished()
                    }
                }
            })
        }
    }

    private fun handleTtsFinished() {
        tts?.stop()
        isTtsActive = false
        if (isAppInForeground()) {
            getSystemService(NotificationManager::class.java).cancel(ANSWER_NOTIFICATION_ID)
        } else {
            lastUpdateTitle?.let { title ->
                lastUpdateText?.let { text ->
                    isTtsUpdate = true
                    updateNotificationWithChannel(title, text)
                    isTtsUpdate = false
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        isTtsActive = false
        isRunningForeground = false
        instance = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                TOGGLE_TTS_ACTION -> {
                    if (isTtsActive) {
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
                    getSystemService(NotificationManager::class.java).cancel(ANSWER_NOTIFICATION_ID)
                    tts?.stop()
                    isTtsActive = false
                    stopSelf()
                    return START_NOT_STICKY
                }
                COPY_ACTION -> {
                    copyLastResponseToClipboard()
                    getSystemService(NotificationManager::class.java).cancel(ANSWER_NOTIFICATION_ID)
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
            if (isTtsActive) {
                tts?.stop()
                isTtsActive = false
            }
            updateNotificationWithChannel(title, contentText)
        }
    }

    private fun dismissIfNotSpeaking() {
        if (!isTtsActive && isNotificationActive(ANSWER_NOTIFICATION_ID)) {
            getSystemService(NotificationManager::class.java).cancel(ANSWER_NOTIFICATION_ID)
        }
    }

    private fun stopTts(updateNotif: Boolean) {
        tts?.stop()
        isTtsActive = false
        if (updateNotif && lastUpdateTitle != null && lastUpdateText != null && isNotificationActive(ANSWER_NOTIFICATION_ID)) {
            isTtsUpdate = true
            updateNotificationWithChannel(lastUpdateTitle!!, lastUpdateText!!)
            isTtsUpdate = false
        }
    }

    private fun startTtsForChannel2() {
        val lastResponse = getLastAiResponseForChannel(2) ?: return
        val cleanText = stripMarkdownWithCommonMark(lastResponse)
        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "fg_tts")
        isTtsActive = true
        if (lastUpdateTitle != null && lastUpdateText != null && isNotificationActive(ANSWER_NOTIFICATION_ID)) {
            isTtsUpdate = true
            updateNotificationWithChannel(lastUpdateTitle!!, lastUpdateText!!)
            isTtsUpdate = false
        } else if (lastUpdateTitle != null && lastUpdateText != null) {
            isTtsUpdate = true
            updateNotificationWithChannel(lastUpdateTitle!!, lastUpdateText!!)
            isTtsUpdate = false
        }
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
        val prefs: SharedPreferences = getSharedPreferences("MainAppPrefs", Context.MODE_PRIVATE)
        return prefs.getString("last_ai_response_channel_$channelId", null)
    }
}
