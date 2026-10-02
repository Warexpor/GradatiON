package io.github.stardomains3.oxproxion.code

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.stardomains3.oxproxion.MainActivity
import io.github.stardomains3.oxproxion.R
import io.github.stardomains3.oxproxion.code.store.CodeStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Local notifications when Code mode is away (§5.6 local slice).
 *
 * Posts ordinary (non-ongoing, non-FGS) notifications while the process is backgrounded,
 * the opt-in flag is on, and the session's host WebSocket is still CONNECTED.
 * No sticky "Code running" chrome — follow [io.github.stardomains3.oxproxion.ForegroundService].
 *
 * Content intents carry a one-shot opaque [EXTRA_OPEN_TOKEN] minted here; [MainActivity]
 * must present a matching token before opening a session (exported-Activity extras are forgeable).
 *
 * Notification ids are allocated collision-free (AWAY-03): key→id is persisted and
 * cancel/eviction use that allocation rather than recomputing the lossy 24-bit hash alone.
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

    /** dedupKey → allocated notificationId (AWAY-03). */
    private val keyToId = HashMap<String, Int>()

    /** notificationId → dedupKey for cancel-by-session / eviction. */
    private val idToKey = HashMap<Int, String>()

    /** sessionId → one-shot open nonce baked into content PendingIntents (A1). */
    private val openTokens = ConcurrentHashMap<String, String>()

    /** Survives process death so a cold-start notification tap still authenticates (A1). */
    private val tokenPrefs: SharedPreferences =
        appContext.getSharedPreferences(OPEN_TOKEN_PREFS, Context.MODE_PRIVATE)

    /** Survives process death so cancel uses the same id that was posted (AWAY-03). */
    private val idPrefs: SharedPreferences =
        appContext.getSharedPreferences(NOTIF_ID_PREFS, Context.MODE_PRIVATE)

    fun setBackgrounded(backgrounded: Boolean) {
        this.backgrounded = backgrounded
    }

    fun isBackgrounded(): Boolean = backgrounded

    /**
     * Inspect one session update. Posts approval / turn-finished while away+connected;
     * always cancels on [CodeUpdate.ApprovalAnswered].
     *
     * @param sessionWasRunning pre-fold running flag — TurnDone only notifies when the
     *   session was already live (suppresses historical attach/seed replays; A4).
     */
    fun onUpdate(
        sessionId: String,
        hostId: String,
        sessionTitle: String,
        update: CodeUpdate,
        sessionWasRunning: Boolean = true,
    ) {
        when (update) {
            is CodeUpdate.ApprovalAnswered -> cancelApproval(sessionId, update.requestId)
            is CodeUpdate.Upsert -> {
                // A3: a new user turn must allow a later TurnDone to re-alert.
                if (update.event is CodeEvent.UserPrompt) {
                    clearTurnDoneDedup(sessionId)
                    return
                }
                val approval = update.event as? CodeEvent.Approval ?: return
                if (!approval.pending) {
                    cancelApproval(sessionId, approval.requestId)
                    return
                }
                maybePostApproval(sessionId, hostId, sessionTitle, approval)
            }
            is CodeUpdate.TurnDone -> {
                // The turn is over, so an approval it still had out can't be answered any more.
                cancelApprovals(sessionId)
                if (!CodeAwayFormat.shouldNotifyTurnDone(sessionWasRunning, update.stopReason)) return
                maybePostTurnDone(sessionId, hostId, sessionTitle)
            }
            else -> Unit
        }
    }

    fun cancelSession(sessionId: String) {
        val toRemove = posted.filter {
            it == CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, sessionId) ||
                it.startsWith("approval:$sessionId:")
        }.toList()
        // Also cancel allocated keys that may only live in prefs after process death.
        val prefKeys = idPrefs.all.keys.filter {
            it == CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, sessionId) ||
                it.startsWith("approval:$sessionId:")
        }
        (toRemove + prefKeys).toSet().forEach { key -> cancelKey(key) }
        clearOpenToken(sessionId)
    }

    /** Drops every approval alert of [sessionId] from the shade, keeping its turn-finished one. */
    private fun cancelApprovals(sessionId: String) {
        val prefix = "approval:$sessionId:"
        val keys = posted.filter { it.startsWith(prefix) } + idPrefs.all.keys.filter { it.startsWith(prefix) }
        keys.toSet().forEach { cancelKey(it) }
    }

    fun cancelApproval(sessionId: String, requestId: String) {
        cancelKey(CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.APPROVAL, sessionId, requestId))
    }

    /**
     * A1: mint (or refresh) the one-shot open nonce for [sessionId].
     * Only [consumeOpenToken] with the matching value authorizes a deep open.
     */
    fun issueOpenToken(sessionId: String): String {
        // One nonce per session until it is consumed. Rotating on every post left the
        // earlier notification (a second approval, say) holding a token that no longer matches.
        openTokens[sessionId]?.let { return it }
        tokenPrefs.getString(sessionId, null)?.let { saved ->
            openTokens[sessionId] = saved
            return saved
        }
        val token = UUID.randomUUID().toString()
        openTokens[sessionId] = token
        // commit: the PendingIntent already carries this nonce. apply() can still be in flight
        // when the process is killed, and a cold-start tap would reject the shade entry.
        tokenPrefs.edit().putString(sessionId, token).commit()
        return token
    }

    /**
     * A1: one-shot check. Returns true only when [token] matches the nonce issued for
     * [sessionId], then clears it so a forged/replayed Intent cannot reopen.
     * Checks in-memory first, then persisted prefs (cold start after process death).
     */
    fun consumeOpenToken(sessionId: String, token: String?): Boolean {
        if (sessionId.isBlank() || token.isNullOrBlank()) return false
        val expected = openTokens[sessionId] ?: tokenPrefs.getString(sessionId, null) ?: return false
        if (expected != token) return false
        clearOpenToken(sessionId)
        return true
    }

    private fun clearOpenToken(sessionId: String) {
        openTokens.remove(sessionId)
        // commit: a kill after apply() is scheduled could leave the nonce for a replayed Intent.
        tokenPrefs.edit().remove(sessionId).commit()
    }

    /**
     * Drop turn-done dedup so a subsequent finished turn can notify again (A3).
     * Keeps the key→id allocation so the next post updates the same shade id (AWAY-03).
     */
    fun clearTurnDoneDedup(sessionId: String) {
        val key = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, sessionId)
        // Remove memory only — leave any still-visible shade entry until open/auto-cancel;
        // the next TurnDone will post (updating the allocated id).
        posted.remove(key)
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
        val headline = awayHeadline(
            R.string.code_away_approval_title,
            R.string.code_away_approval_title_named,
            approval.title.ifBlank { sessionTitle },
        )
        val id = idFor(key)
        val builder = baseBuilder(sessionId, headline, sessionTitle.ifBlank { approval.title }, id)
        // Optional Allow / Deny when wire options are present (answer without opening UI).
        val allow = CodeAwayFormat.pickAllow(approval.options)
        val deny = CodeAwayFormat.pickDeny(approval.options)
        if (allow != null) {
            builder.addAction(
                0,
                appContext.getString(R.string.code_away_action_allow),
                actionPending(sessionId, approval.requestId, allow, REQUEST_ALLOW, id),
            )
        }
        if (deny != null) {
            builder.addAction(
                0,
                appContext.getString(R.string.code_away_action_deny),
                actionPending(sessionId, approval.requestId, deny, REQUEST_DENY, id),
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
        val headline = awayHeadline(
            R.string.code_away_turn_title,
            R.string.code_away_turn_title_named,
            sessionTitle,
        )
        val id = idFor(key)
        val builder = baseBuilder(sessionId, headline, sessionTitle, id)
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

    private fun awayHeadline(bare: Int, named: Int, title: String): String {
        val t = title.trim()
        return if (t.isEmpty()) appContext.getString(bare) else appContext.getString(named, t)
    }

    private fun baseBuilder(
        sessionId: String,
        title: String,
        contentText: String,
        notifId: Int,
    ): NotificationCompat.Builder {
        val token = issueOpenToken(sessionId)
        val open = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_FROM_AWAY, true)
            putExtra(EXTRA_OPEN_TOKEN, token)
        }
        val contentPi = PendingIntent.getActivity(
            appContext,
            CodeAwayFormat.contentRequestCode(notifId),
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
        notifId: Int,
    ): PendingIntent {
        val intent = Intent(appContext, CodeAwayActionReceiver::class.java).apply {
            action = ACTION_ANSWER
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_REQUEST_ID, requestId)
            putExtra(EXTRA_OPTION_ID, option.id)
            putExtra(EXTRA_OPTION_KIND, option.kind.name)
            putExtra(EXTRA_OPTION_LABEL, option.label)
        }
        val req = CodeAwayFormat.actionRequestCode(notifId, allow = requestCodeSalt == REQUEST_ALLOW)
        return PendingIntent.getBroadcast(
            appContext,
            req,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * AWAY-03: resolve a stable, collision-free notification id for [key].
     * Reuses in-memory or persisted allocation when still free; otherwise probes.
     */
    private fun idFor(key: String): Int {
        keyToId[key]?.let { return it }
        val savedRaw = idPrefs.getInt(key, Int.MIN_VALUE)
        val saved = savedRaw.takeIf { it != Int.MIN_VALUE && CodeAwayFormat.isAwayNotifId(it) }
        // Usable only if no other live key owns this id.
        val existing = saved?.takeIf { owner -> idToKey[owner] == null || idToKey[owner] == key }
        val taken = idToKey.keys
        val id = CodeAwayFormat.allocateNotificationId(key, taken, existing)
        keyToId[key] = id
        idToKey[id] = key
        if (savedRaw != id) {
            idPrefs.edit().putInt(key, id).apply()
        }
        return id
    }

    private fun markPosted(key: String, id: Int) {
        posted += key
        keyToId[key] = id
        idToKey[id] = key
        idPrefs.edit().putInt(key, id).apply()
        // Bound memory if many sessions notify while away; cancel shade on eviction (A6).
        while (posted.size > 64) {
            val oldest = posted.first()
            posted.remove(oldest)
            val evictId = releaseAllocation(oldest)
                ?: CodeAwayFormat.notificationId(oldest)
            nm?.cancel(evictId)
        }
    }

    private fun cancelKey(key: String) {
        posted.remove(key)
        val id = releaseAllocation(key)
            ?: CodeAwayFormat.notificationId(key) // last-resort for pre-allocation leftovers
        nm?.cancel(id)
    }

    /** Drop key→id maps (memory + prefs). Returns the freed id when known. */
    private fun releaseAllocation(key: String): Int? {
        val fromMem = keyToId.remove(key)
        val fromPrefs = idPrefs.getInt(key, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE && CodeAwayFormat.isAwayNotifId(it) }
        val id = fromMem ?: fromPrefs
        if (id != null) {
            if (idToKey[id] == key) idToKey.remove(id)
        }
        if (fromPrefs != null || fromMem != null) {
            idPrefs.edit().remove(key).apply()
        }
        return id
    }

    companion object {
        const val CHANNEL_ID = "code_away"
        const val EXTRA_SESSION_ID = "code_away_session_id"
        const val EXTRA_FROM_AWAY = "code_away_from_notification"
        const val EXTRA_OPEN_TOKEN = "code_away_open_token"
        const val EXTRA_REQUEST_ID = "code_away_request_id"
        const val EXTRA_OPTION_ID = "code_away_option_id"
        const val EXTRA_OPTION_KIND = "code_away_option_kind"
        const val EXTRA_OPTION_LABEL = "code_away_option_label"
        const val ACTION_ANSWER = "io.github.stardomains3.oxproxion.code.AWAY_ANSWER"

        private const val REQUEST_ALLOW = 0xA11
        private const val REQUEST_DENY = 0xDE1
        private const val OPEN_TOKEN_PREFS = "code_away_open_tokens"
        private const val NOTIF_ID_PREFS = "code_away_notif_ids"
    }
}
