package io.github.stardomains3.oxproxion.code

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.stardomains3.oxproxion.MainActivity
import io.github.stardomains3.oxproxion.R
import io.github.stardomains3.oxproxion.code.store.CodeStore

/**
 * Local notifications when Code mode is away (§5.6 local slice).
 *
 * Posts ordinary (non-ongoing, non-FGS) notifications while the process is backgrounded,
 * the opt-in flag is on, and the session's host WebSocket is still CONNECTED.
 * No sticky "Code running" chrome — follow [io.github.stardomains3.oxproxion.ForegroundService].
 */
class CodeAwayNotifier(
    context: Context,
    private val store: CodeStore,
    private val isHostConnected: (hostId: String) -> Boolean,
) {
    private val appContext = context.applicationContext
    private val nm = appContext.getSystemService(NotificationManager::class.java)

    @Volatile
    private var backgrounded: Boolean = false

    /** Dedup keys currently showing (or suppressed after post). */
    private val posted = LinkedHashSet<String>()

    /** notificationId → dedupKey for cancel-by-session. */
    private val idToKey = HashMap<Int, String>()

    fun setBackgrounded(backgrounded: Boolean) {
        this.backgrounded = backgrounded
    }

    fun isBackgrounded(): Boolean = backgrounded

    /**
     * Inspect one session update. Posts approval / turn-finished while away+connected;
     * always cancels on [CodeUpdate.ApprovalAnswered].
     */
    fun onUpdate(
        sessionId: String,
        hostId: String,
        sessionTitle: String,
        update: CodeUpdate,
    ) {
        when (update) {
            is CodeUpdate.ApprovalAnswered -> cancelApproval(sessionId, update.requestId)
            is CodeUpdate.Upsert -> {
                val approval = update.event as? CodeEvent.Approval ?: return
                if (approval.chosen != null) {
                    cancelApproval(sessionId, approval.requestId)
                    return
                }
                maybePostApproval(sessionId, hostId, sessionTitle, approval)
            }
            is CodeUpdate.TurnDone -> maybePostTurnDone(sessionId, hostId, sessionTitle)
            else -> Unit
        }
    }

    fun cancelSession(sessionId: String) {
        val toRemove = posted.filter {
            it == CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, sessionId) ||
                it.startsWith("approval:$sessionId:")
        }
        toRemove.forEach { key -> cancelKey(key) }
    }

    fun cancelApproval(sessionId: String, requestId: String) {
        cancelKey(CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.APPROVAL, sessionId, requestId))
    }

    private fun maybePostApproval(
        sessionId: String,
        hostId: String,
        sessionTitle: String,
        approval: CodeEvent.Approval,
    ) {
        if (!canPost(hostId)) return
        val key = CodeAwayFormat.dedupKey(
            CodeAwayFormat.Kind.APPROVAL,
            sessionId,
            approval.requestId,
        )
        if (!CodeAwayFormat.shouldPost(posted, key)) return
        ensureChannel()
        val headline = CodeAwayFormat.approvalHeadline(
            approval.title.ifBlank { sessionTitle },
        )
        val id = CodeAwayFormat.notificationId(key)
        val builder = baseBuilder(sessionId, headline, sessionTitle.ifBlank { approval.title })
        // Optional Allow / Deny when wire options are present (answer without opening UI).
        val allow = CodeAwayFormat.pickAllow(approval.options)
        val deny = CodeAwayFormat.pickDeny(approval.options)
        if (allow != null) {
            builder.addAction(
                0,
                appContext.getString(R.string.code_away_action_allow),
                actionPending(sessionId, approval.requestId, allow, REQUEST_ALLOW),
            )
        }
        if (deny != null) {
            builder.addAction(
                0,
                appContext.getString(R.string.code_away_action_deny),
                actionPending(sessionId, approval.requestId, deny, REQUEST_DENY),
            )
        }
        nm?.notify(id, builder.build())
        markPosted(key, id)
    }

    private fun maybePostTurnDone(sessionId: String, hostId: String, sessionTitle: String) {
        if (!canPost(hostId)) return
        val key = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, sessionId)
        if (!CodeAwayFormat.shouldPost(posted, key)) return
        ensureChannel()
        val headline = CodeAwayFormat.turnDoneHeadline(sessionTitle)
        val id = CodeAwayFormat.notificationId(key)
        val builder = baseBuilder(sessionId, headline, sessionTitle)
        nm?.notify(id, builder.build())
        markPosted(key, id)
    }

    private fun canPost(hostId: String): Boolean {
        if (!store.notifyWhenAway || !backgrounded) return false
        if (!isHostConnected(hostId)) return false
        if (!notificationsAllowed()) return false
        return true
    }

    private fun notificationsAllowed(): Boolean {
        if (nm == null || !nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return true
    }

    private fun ensureChannel() {
        val manager = nm ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.code_away_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = appContext.getString(R.string.code_away_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    private fun baseBuilder(
        sessionId: String,
        title: String,
        contentText: String,
    ): NotificationCompat.Builder {
        val open = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_FROM_AWAY, true)
        }
        val contentPi = PendingIntent.getActivity(
            appContext,
            CodeAwayFormat.notificationId("open:$sessionId"),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(title)
            .setContentText(contentText.ifBlank { title })
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    }

    private fun actionPending(
        sessionId: String,
        requestId: String,
        option: ApprovalOption,
        requestCodeSalt: Int,
    ): PendingIntent {
        val intent = Intent(appContext, CodeAwayActionReceiver::class.java).apply {
            action = ACTION_ANSWER
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_REQUEST_ID, requestId)
            putExtra(EXTRA_OPTION_ID, option.id)
            putExtra(EXTRA_OPTION_KIND, option.kind.name)
            putExtra(EXTRA_OPTION_LABEL, option.label)
        }
        val req = requestCodeSalt xor CodeAwayFormat.notificationId(
            CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.APPROVAL, sessionId, requestId),
        )
        return PendingIntent.getBroadcast(
            appContext,
            req,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun markPosted(key: String, id: Int) {
        posted += key
        idToKey[id] = key
        // Bound memory if many sessions notify while away.
        while (posted.size > 64) {
            val oldest = posted.first()
            posted.remove(oldest)
            idToKey.entries.removeAll { it.value == oldest }
        }
    }

    private fun cancelKey(key: String) {
        if (!posted.remove(key) && idToKey.values.none { it == key }) {
            // Still cancel by derived id in case process restarted with a leftover shade entry.
        }
        val id = CodeAwayFormat.notificationId(key)
        idToKey.remove(id)
        nm?.cancel(id)
    }

    companion object {
        const val CHANNEL_ID = "code_away"
        const val EXTRA_SESSION_ID = "code_away_session_id"
        const val EXTRA_FROM_AWAY = "code_away_from_notification"
        const val EXTRA_REQUEST_ID = "code_away_request_id"
        const val EXTRA_OPTION_ID = "code_away_option_id"
        const val EXTRA_OPTION_KIND = "code_away_option_kind"
        const val EXTRA_OPTION_LABEL = "code_away_option_label"
        const val ACTION_ANSWER = "io.github.stardomains3.oxproxion.code.AWAY_ANSWER"

        private const val REQUEST_ALLOW = 0xA11
        private const val REQUEST_DENY = 0xDE1
    }
}
