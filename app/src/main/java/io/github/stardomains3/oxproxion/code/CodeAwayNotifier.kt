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
 * Cold-start allocation also treats prefs-held ids as taken so a new alert cannot reuse
 * a shade entry that survived process death. Cold-start also seeds the dedup set from those
 * prefs so a reconnect cannot re-alert the same shade entry.
 * [clearTurnDoneDedup] drops the turn-done prefs row so a later finished turn is not
 * re-suppressed after process death. The shade id is parked under a hold: prefix in the
 * same prefs file, in that same commit, so a kill cannot leave the live row behind
 * (which would re-seed dedup and swallow the next finished turn). Dedup seeding skips
 * the prefix. A legacy hold file from older builds is still read, and is not a dedup seed.
 * [cancelSession] also cancels keys that remain only in the in-memory allocation map after
 * that clear (posted + prefs no longer list them, but the shade may still be up).
 *
 * Shade swipe-dismiss ([onUserDismissed]) drops dedup and the prefs allocation so a still-pending
 * approval can re-alert (including after a later process death that would otherwise re-seed).
 * When that was the session's last shade entry, the one-shot open token goes too, so a
 * replayed content Intent cannot open the session. Programmatic cancel does not fire the
 * DeleteIntent, so Allow, Deny, an in-app answer, a cancelled turn, and eviction past the
 * shade cap clear that token themselves. A shorter session id must not own a longer
 * one's approval (`ab` vs `ab:cd`); longest known id wins.
 *
 * The cap counts shades that are still up. [clearTurnDoneDedup] drops the dedup key so the
 * next finish can alert, but leaves the notification. Counting only [posted] let a run of
 * new prompts stack past the cap. Age is a seq in the same prefs file: the map's own iteration
 * order is not post order, so a cold start would otherwise drop an arbitrary shade.
 * Android refuses a new notification once the package already has
 * [SYSTEM_PACKAGE_NOTIF_LIMIT]. The oldest away shade is cancelled before that post, and one
 * slot stays free for the answer-ready shade, which shares the same budget.
 *
 * A prefs row whose notification is already gone (cleared without a DeleteIntent) is not a
 * dedup seed, does not count toward the cap, and does not keep the one-shot open token.
 * The id is kept so the next alert for that key updates the same slot.
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

    /**
     * Shades still on screen, oldest first. [posted] drops a turn-done key on the next
     * user prompt; the notification stays until cancel or the next finish updates it.
     */
    private val visible = LinkedHashSet<String>()

    /** Next shade age. Zero until the first post reads the max seq already in prefs. */
    private var orderClock = 0

    /** True once [posted] has been seeded from [idPrefs] after process death. */
    @Volatile
    private var postedSeeded: Boolean = false

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

    /**
     * Legacy turn-done shade ids from before the hold: prefix lived in [idPrefs].
     * Not a dedup seed. Still read so a shade posted by that build can be cancelled
     * and updated. New clears write the hold prefix instead, in one commit with the
     * dedup drop. If both this file and a live id row exist (kill between the old
     * two commits), the hold wins for seeding so the next finished turn is not swallowed.
     */
    private val shadeHold: SharedPreferences =
        appContext.getSharedPreferences(SHADE_HOLD_PREFS, Context.MODE_PRIVATE)

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
        ensurePostedSeeded()
        try {
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
        } finally {
            // A shade removed without a swipe does not run the DeleteIntent. Drop its
            // token once any later update is handled, not only after a kill. A repost
            // in this same update has already put the shade back, so that token stays.
            dropOpenTokensForShadesThatAreGone()
        }
    }

    fun cancelSession(sessionId: String) {
        ensurePostedSeeded()
        // posted + prefs cover the usual paths; keyToId covers the gap after clearTurnDoneDedup
        // (drops posted + prefs, keeps in-memory id so the next TurnDone can update the same shade).
        // Prefix match would also cancel `ab:cd` when forgetting `ab`.
        val known = knownSessionIds()
        allocatedKeys { CodeAwayFormat.keyBelongsToSession(it, sessionId, known) }
            .forEach { cancelKey(it) }
        clearOpenToken(sessionId)
    }

    /** Drops every approval alert of [sessionId] from the shade, keeping its turn-finished one. */
    private fun cancelApprovals(sessionId: String) {
        val known = knownSessionIds()
        allocatedKeys { key ->
            CodeAwayFormat.isApprovalKey(key) &&
                CodeAwayFormat.keyBelongsToSession(key, sessionId, known)
        }.forEach { cancelKey(it) }
    }

    /**
     * Live keys plus hold-prefixed and legacy hold-file rows, mapped back to the
     * logical dedup key so cancel still finds a shade id that was parked.
     */
    private fun allocatedKeys(matches: (String) -> Boolean): Set<String> {
        val keys = LinkedHashSet<String>()
        fun consider(raw: String) {
            // Age rows share this prefs file. They are not shades.
            if (raw.startsWith(CodeAwayFormat.ORDER_PREFIX)) return
            val logical = CodeAwayFormat.logicalDedupKey(raw)
            if (matches(logical)) keys.add(logical)
        }
        posted.forEach(::consider)
        idPrefs.all.keys.forEach(::consider)
        shadeHold.all.keys.forEach(::consider)
        keyToId.keys.forEach(::consider)
        return keys
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
     * Keeps the in-memory key→id so the next post updates the same shade id (AWAY-03).
     * Clears the prefs row and parks the shade id under [CodeAwayFormat.holdPrefKey] in
     * the same commit. Two commits (park, then remove) could die in between and leave
     * the live row for [ensurePostedSeeded] to re-suppress the next finished turn.
     */
    fun clearTurnDoneDedup(sessionId: String) {
        ensurePostedSeeded()
        val key = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, sessionId)
        // Leave any still-visible shade entry until open/auto-cancel / next TurnDone notify.
        val held = keyToId[key]
            ?: idPrefs.getInt(key, Int.MIN_VALUE).takeIf { CodeAwayFormat.isAwayNotifId(it) }
            ?: idPrefs.getInt(CodeAwayFormat.holdPrefKey(key), Int.MIN_VALUE)
                .takeIf { CodeAwayFormat.isAwayNotifId(it) }
            ?: shadeHold.getInt(key, Int.MIN_VALUE).takeIf { CodeAwayFormat.isAwayNotifId(it) }
        posted.remove(key)
        val edit = idPrefs.edit().remove(key)
        if (held != null) edit.putInt(CodeAwayFormat.holdPrefKey(key), held)
        else edit.remove(CodeAwayFormat.holdPrefKey(key))
        edit.commit()
        // Legacy file is not the source of truth anymore. Drop it so a later seed
        // cannot treat a stale hold as "dedup already cleared" over a new live row.
        if (shadeHold.contains(key)) {
            shadeHold.edit().remove(key).commit()
        }
    }

    /**
     * Shade swipe-dismiss (DeleteIntent). Drops dedup and the prefs allocation so a
     * still-pending approval can re-alert — including after process death, when
     * [ensurePostedSeeded] would otherwise re-suppress from a leftover prefs row.
     */
    fun onUserDismissed(dedupKey: String) {
        if (dedupKey.isBlank()) return
        // cancelKey clears the open token when this was the session's last shade.
        cancelKey(dedupKey)
    }

    private fun knownSessionIds(): Set<String> {
        val ids = LinkedHashSet<String>()
        ids.addAll(openTokens.keys)
        ids.addAll(tokenPrefs.all.keys)
        return ids
    }

    private fun sessionHasAllocation(sessionId: String): Boolean {
        val known = knownSessionIds()
        return allocatedKeys { CodeAwayFormat.keyBelongsToSession(it, sessionId, known) }
            .any { shadeStillUp(it) }
    }

    /** Sessions whose shades are all gone must not keep a one-shot open token. */
    private fun dropOpenTokensForShadesThatAreGone() {
        for (sid in knownSessionIds().toList()) {
            if (!sessionHasAllocation(sid)) clearOpenToken(sid)
        }
    }

    /** Ids recorded for [key] (memory, live row, hold row, legacy file). */
    private fun idsRecordedFor(key: String): List<Int> {
        val ids = ArrayList<Int>(4)
        keyToId[key]?.let { if (CodeAwayFormat.isAwayNotifId(it)) ids += it }
        fun add(raw: Int) {
            if (raw != Int.MIN_VALUE && CodeAwayFormat.isAwayNotifId(raw)) ids += raw
        }
        add(idPrefs.getInt(key, Int.MIN_VALUE))
        add(idPrefs.getInt(CodeAwayFormat.holdPrefKey(key), Int.MIN_VALUE))
        add(shadeHold.getInt(key, Int.MIN_VALUE))
        return ids.distinct()
    }

    /**
     * True when one of [key]'s ids is still in the shade.
     * A cancel that does not run the DeleteIntent leaves the prefs row behind; that row
     * must not count as a shade that is still up. If the manager is missing, keep the
     * old "prefs means up" answer rather than dropping a token we cannot check.
     */
    private fun shadeStillUp(key: String): Boolean {
        val ids = idsRecordedFor(key)
        if (ids.isEmpty()) return false
        val manager = nm ?: return true
        val active = manager.activeNotifications.map { it.id }.toSet()
        return ids.any { it in active }
    }

    /** Posted set says skip, unless the shade it remembers is already gone. */
    private fun claimPost(key: String): Boolean {
        if (key !in posted) return true
        if (shadeStillUp(key)) return false
        posted.remove(key)
        return true
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
        if (!claimPost(key)) return
        ensureChannel()
        val headline = awayHeadline(
            R.string.code_away_approval_title,
            R.string.code_away_approval_title_named,
            approval.title.ifBlank { sessionTitle },
        )
        val id = idFor(key)
        val builder = baseBuilder(sessionId, headline, sessionTitle.ifBlank { approval.title }, id, key)
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
        showShade(key, id, builder.build())
    }

    private fun maybePostTurnDone(sessionId: String, hostId: String, sessionTitle: String) {
        if (!canPost(hostId)) return
        val key = CodeAwayFormat.dedupKey(CodeAwayFormat.Kind.TURN_DONE, sessionId)
        if (!claimPost(key)) return
        ensureChannel()
        val headline = awayHeadline(
            R.string.code_away_turn_title,
            R.string.code_away_turn_title_named,
            sessionTitle,
        )
        val id = idFor(key)
        val builder = baseBuilder(sessionId, headline, sessionTitle, id, key)
        showShade(key, id, builder.build())
    }

    /**
     * Post [notification] and remember it. A new id needs a free slot in the package budget
     * before [NotificationManager.notify]: the system drops the call once
     * [SYSTEM_PACKAGE_NOTIF_LIMIT] are already up, and cancelling the oldest afterwards
     * does not bring the refused shade back.
     */
    private fun showShade(key: String, id: Int, notification: android.app.Notification) {
        makeRoomFor(id)
        nm?.notify(id, notification)
        markPosted(key, id)
    }

    /**
     * Drop the oldest away shade until this post can be a new notification.
     * An id that is already showing is an update, which the system still accepts.
     * The answer-ready shade is not an away shade; one slot stays free for it.
     */
    private fun makeRoomFor(id: Int) {
        val manager = nm ?: return
        val active = manager.activeNotifications
        if (active.any { it.id == id }) return
        val answerUp = active.any { it.id == ANSWER_READY_NOTIF_ID }
        val ceiling = if (answerUp) SYSTEM_PACKAGE_NOTIF_LIMIT else AWAY_SHADE_LIMIT
        var spins = 0
        while (manager.activeNotifications.size >= ceiling && spins++ < SYSTEM_PACKAGE_NOTIF_LIMIT) {
            pruneDeadVisible()
            val oldest = visible.firstOrNull() ?: return
            val visibleBefore = visible.size
            val activeBefore = manager.activeNotifications.size
            forgetShade(oldest)
            if (visible.size >= visibleBefore && manager.activeNotifications.size >= activeBefore) return
        }
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
        // User blocked the Code away channel in system settings — do not "post" into silence.
        val channel = nm.getNotificationChannel(CHANNEL_ID)
        if (channel != null && !CodeAwayFormat.channelCanNotify(channel.importance)) {
            return false
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
        dedupKey: String,
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
            .setDeleteIntent(dismissPending(dedupKey, notifId))
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    }

    private fun dismissPending(dedupKey: String, notifId: Int): PendingIntent {
        val intent = Intent(appContext, CodeAwayActionReceiver::class.java).apply {
            action = ACTION_DISMISS
            putExtra(EXTRA_DEDUP_KEY, dedupKey)
        }
        return PendingIntent.getBroadcast(
            appContext,
            CodeAwayFormat.dismissRequestCode(notifId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
     * After process death [posted] is empty while prefs (and often the shade) still hold
     * every key that was allocated. Seed once so reconnect cannot re-alert those entries.
     * [clearTurnDoneDedup] drops the turn-done prefs row so a later finished turn can still
     * post after a kill (memory-only clear would be re-seeded from prefs).
     */
    private fun ensurePostedSeeded() {
        if (postedSeeded) return
        synchronized(posted) {
            if (postedSeeded) return
            // A legacy hold file means an older clear parked the shade and may have
            // died before removing the live row. Do not re-seed that key: the next
            // finished turn must still alert (and will reuse the held id).
            val legacyHeld = shadeHold.all.keys
            // A row whose notification is already gone must not suppress the next alert.
            val seeded = CodeAwayFormat.postedKeysFromPrefs(idPrefs.all).filter { key ->
                key !in legacyHeld && shadeStillUp(key)
            }
            posted.addAll(seeded)
            // Hold rows are not a dedup seed, but a shade that is still up counts.
            // Seq order, not prefs iteration: the oldest one is the one the cap drops.
            if (visible.isEmpty()) {
                val live = LinkedHashSet<String>()
                CodeAwayFormat.visibleKeysFromPrefs(idPrefs.all).filterTo(live) { shadeStillUp(it) }
                CodeAwayFormat.visibleKeysFromPrefs(shadeHold.all).filterTo(live) { shadeStillUp(it) }
                val ordered = CodeAwayFormat.orderedVisibleKeys(idPrefs.all).filter { it in live }
                val orderedSet = ordered.toSet()
                val ranked = LinkedHashSet<String>()
                live.filterTo(ranked) { it !in orderedSet }
                ranked.addAll(ordered)
                visible.addAll(ranked)
            }
            // Nothing left on screen: a replayed content Intent must not still open.
            for (sid in knownSessionIds().toList()) {
                if (!sessionHasAllocation(sid)) clearOpenToken(sid)
            }
            postedSeeded = true
        }
    }

    /**
     * AWAY-03: resolve a stable, collision-free notification id for [key].
     * Reuses in-memory or persisted allocation when still free; otherwise probes.
     */
    private fun idFor(key: String): Int {
        keyToId[key]?.let { return it }
        val savedRaw = idPrefs.getInt(key, Int.MIN_VALUE)
        val holdRaw = idPrefs.getInt(CodeAwayFormat.holdPrefKey(key), Int.MIN_VALUE)
        val legacyRaw = shadeHold.getInt(key, Int.MIN_VALUE)
        val saved = sequenceOf(savedRaw, holdRaw, legacyRaw).firstOrNull {
            it != Int.MIN_VALUE && CodeAwayFormat.isAwayNotifId(it)
        }
        // Usable only if no other live key owns this id.
        val existing = saved?.takeIf { owner -> idToKey[owner] == null || idToKey[owner] == key }
        // After process death idToKey is empty; prefs still hold every posted id. A new
        // key whose preferred hash matches a surviving shade entry must probe, not reuse.
        // Hold-prefix and legacy hold-file ids are taken too: clear dropped the live row.
        val taken = idToKey.keys +
            CodeAwayFormat.takenFromPrefs(idPrefs.all, exceptKey = key) +
            CodeAwayFormat.takenFromPrefs(shadeHold.all, exceptKey = key)
        val id = CodeAwayFormat.allocateNotificationId(key, taken, existing)
        keyToId[key] = id
        idToKey[id] = key
        val holdKey = CodeAwayFormat.holdPrefKey(key)
        if (savedRaw != id || idPrefs.contains(holdKey)) {
            // commit: cancel after a kill must find this id. apply() can still be in flight
            // when the process dies, leaving the shade entry uncancelable.
            // Drop the hold prefix in the same commit so dedup and the live id cannot split.
            idPrefs.edit().putInt(key, id).remove(holdKey).commit()
        }
        return id
    }

    private fun markPosted(key: String, id: Int) {
        posted += key
        keyToId[key] = id
        idToKey[id] = key
        // commit: the shade already shows this id. apply() can still be in flight when the
        // process is killed, and a cold-start cancel would miss the allocation.
        // Age is in the same commit so a kill cannot leave the id without a place in line.
        idPrefs.edit()
            .putInt(key, id)
            .remove(CodeAwayFormat.holdPrefKey(key))
            .putInt(CodeAwayFormat.orderPrefKey(key), nextOrder())
            .commit()
        if (shadeHold.contains(key)) {
            shadeHold.edit().remove(key).commit()
        }
        pruneDeadVisible()
        rememberVisible(key)
        // Backstop if the package budget was not applied (no manager). Dedup-cleared turn
        // shades stay in [visible], so they still count.
        while (visible.size > AWAY_SHADE_LIMIT) {
            val oldest = visible.firstOrNull() ?: return
            if (oldest == key && visible.size == 1) return
            forgetShade(oldest)
        }
    }

    /** Newest post goes to the end so eviction drops the shade that has been up longest. */
    private fun rememberVisible(key: String) {
        visible.remove(key)
        visible.add(key)
    }

    /** Drop shades the user (or the system) already cleared, so they do not fill the cap. */
    private fun pruneDeadVisible() {
        if (nm == null || visible.isEmpty()) return
        val active = nm.activeNotifications.map { it.id }.toSet()
        val it = visible.iterator()
        while (it.hasNext()) {
            val key = it.next()
            val ids = idsRecordedFor(key)
            if (ids.none { id -> id in active }) it.remove()
        }
    }

    private fun nextOrder(): Int {
        if (orderClock == 0) {
            for ((k, v) in idPrefs.all) {
                if (!k.startsWith(CodeAwayFormat.ORDER_PREFIX)) continue
                val n = when (v) {
                    is Int -> v
                    is Number -> v.toInt()
                    else -> continue
                }
                if (n > orderClock) orderClock = n
            }
        }
        orderClock++
        return orderClock
    }

    private fun cancelKey(key: String) = forgetShade(key)

    /**
     * Drop one shade and, when it was the session's last, the one-shot open token.
     * Allow, Deny, and a cancelled turn cancel in code. That does not run the
     * DeleteIntent, so the token would otherwise keep opening the session.
     */
    private fun forgetShade(key: String) {
        val sessionId = CodeAwayFormat.sessionIdForDedupKey(key, knownSessionIds())
        posted.remove(key)
        visible.remove(key)
        val ids = releaseAllocation(key)
        if (ids.isEmpty()) {
            // Last-resort for a shade posted before ids were stored. The 24-bit hash is only
            // a hint: another live shade may already own it, and cancelling that id would
            // drop a shade this key never posted.
            val hashId = CodeAwayFormat.notificationId(key)
            if (!otherShadeOwns(hashId, key)) nm?.cancel(hashId)
        } else {
            ids.forEach { nm?.cancel(it) }
        }
        if (sessionId != null && !sessionHasAllocation(sessionId)) {
            clearOpenToken(sessionId)
        }
    }

    /** Drop key→id maps (memory + prefs). Returns every freed id (live, hold, legacy). */
    private fun releaseAllocation(key: String): List<Int> {
        val fromMem = keyToId.remove(key)
        val fromPrefs = idPrefs.getInt(key, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE && CodeAwayFormat.isAwayNotifId(it) }
        val fromHold = idPrefs.getInt(CodeAwayFormat.holdPrefKey(key), Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE && CodeAwayFormat.isAwayNotifId(it) }
        val fromLegacy = shadeHold.getInt(key, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE && CodeAwayFormat.isAwayNotifId(it) }
        val ids = listOfNotNull(fromMem, fromPrefs, fromHold, fromLegacy).distinct()
        for (id in ids) {
            if (idToKey[id] == key) idToKey.remove(id)
        }
        val orderKey = CodeAwayFormat.orderPrefKey(key)
        if (fromPrefs != null || fromMem != null || fromHold != null ||
            idPrefs.contains(key) || idPrefs.contains(CodeAwayFormat.holdPrefKey(key)) ||
            idPrefs.contains(orderKey)
        ) {
            // commit: a kill after apply() is scheduled could leave a stale id that collides
            // with the next allocation for another key.
            idPrefs.edit()
                .remove(key)
                .remove(CodeAwayFormat.holdPrefKey(key))
                .remove(orderKey)
                .commit()
        }
        if (fromLegacy != null || shadeHold.contains(key)) {
            shadeHold.edit().remove(key).commit()
        }
        return ids
    }

    /** True when a different key already has [id] in memory or in the persisted allocation. */
    private fun otherShadeOwns(id: Int, key: String): Boolean {
        val owner = idToKey[id]
        if (owner != null && owner != key) return true
        if (id in CodeAwayFormat.takenFromPrefs(idPrefs.all, exceptKey = key)) return true
        if (id in CodeAwayFormat.takenFromPrefs(shadeHold.all, exceptKey = key)) return true
        return false
    }

    companion object {
        /**
         * AOSP NotificationManagerService.MAX_PACKAGE_NOTIFICATIONS. A new id past this
         * is not shown. Updates of an id that is already up still go through.
         */
        const val SYSTEM_PACKAGE_NOTIF_LIMIT = 50

        /** Answer-ready notification. It shares [SYSTEM_PACKAGE_NOTIF_LIMIT] with away shades. */
        const val ANSWER_READY_NOTIF_ID = 2

        /**
         * Away shades kept when the answer shade is not up, so a finished chat can still notify.
         * With the answer shade up the package is full at [SYSTEM_PACKAGE_NOTIF_LIMIT]
         * (this many away shades, plus that one).
         */
        const val AWAY_SHADE_LIMIT = SYSTEM_PACKAGE_NOTIF_LIMIT - 1

        const val CHANNEL_ID = "code_away"
        const val EXTRA_SESSION_ID = "code_away_session_id"
        const val EXTRA_FROM_AWAY = "code_away_from_notification"
        const val EXTRA_OPEN_TOKEN = "code_away_open_token"
        const val EXTRA_REQUEST_ID = "code_away_request_id"
        const val EXTRA_OPTION_ID = "code_away_option_id"
        const val EXTRA_OPTION_KIND = "code_away_option_kind"
        const val EXTRA_OPTION_LABEL = "code_away_option_label"
        const val ACTION_ANSWER = "io.github.stardomains3.oxproxion.code.AWAY_ANSWER"
        const val ACTION_DISMISS = "io.github.stardomains3.oxproxion.code.AWAY_DISMISS"
        const val EXTRA_DEDUP_KEY = "code_away_dedup_key"

        private const val REQUEST_ALLOW = 0xA11
        private const val REQUEST_DENY = 0xDE1
        private const val OPEN_TOKEN_PREFS = "code_away_open_tokens"
        private const val NOTIF_ID_PREFS = "code_away_notif_ids"
        private const val SHADE_HOLD_PREFS = "code_away_shade_hold"
    }
}
