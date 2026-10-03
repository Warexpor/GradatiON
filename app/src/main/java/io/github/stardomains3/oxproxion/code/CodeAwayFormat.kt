package io.github.stardomains3.oxproxion.code

/**
 * Pure helpers for Code-mode "away" local notifications (§5.6 local slice).
 * No Android types — unit-tested for format and dedup keys.
 */
object CodeAwayFormat {

    enum class Kind { APPROVAL, TURN_DONE }

    /** Stable dedup key: approval by requestId; turn-done by session. */
    fun dedupKey(kind: Kind, sessionId: String, requestId: String? = null): String = when (kind) {
        Kind.APPROVAL -> "approval:$sessionId:${requestId.orEmpty()}"
        Kind.TURN_DONE -> "turn:$sessionId"
    }

    /**
     * Preferred NotificationManager id hint from a 24-bit hash of [dedupKey].
     * May collide across keys (AWAY-03); callers that post/cancel must use
     * [allocateNotificationId] (or a persisted key→id map) instead of this alone.
     * Stays clear of answer-ready (2) and legacy sticky FGS (1).
     */
    fun notificationId(dedupKey: String): Int {
        val h = dedupKey.hashCode() and NOTIF_ID_MASK
        return NOTIF_ID_BASE + h
    }

    /**
     * Collision-free id for [dedupKey] within the away notif space.
     *
     * Reuses [existing] when it is still free (not in [taken]). Otherwise starts
     * at [notificationId] and probes sequentially (wrapping the 24-bit range)
     * until an id not in [taken] is found.
     */
    fun allocateNotificationId(
        dedupKey: String,
        taken: Set<Int>,
        existing: Int? = null,
    ): Int {
        if (existing != null && isAwayNotifId(existing) && existing !in taken) {
            return existing
        }
        val preferred = notificationId(dedupKey)
        if (preferred !in taken) return preferred
        var steps = 1
        while (steps <= NOTIF_ID_MASK) {
            val id = NOTIF_ID_BASE + ((preferred - NOTIF_ID_BASE + steps) and NOTIF_ID_MASK)
            if (id !in taken) return id
            steps++
        }
        // Exhausted (pathological); still return preferred so callers can post.
        return preferred
    }

    fun isAwayNotifId(id: Int): Boolean =
        id >= NOTIF_ID_BASE && id <= NOTIF_ID_BASE + NOTIF_ID_MASK

    /**
     * Ids already allocated in persisted key→id prefs, excluding [exceptKey].
     * After process death [CodeAwayNotifier]'s in-memory maps are empty; allocation
     * must still treat shade entries (and their prefs rows) as taken, or a new key
     * can reuse an id that is still posted.
     */
    fun takenFromPrefs(entries: Map<String, *>, exceptKey: String? = null): Set<Int> {
        val out = LinkedHashSet<Int>()
        for ((k, v) in entries) {
            if (k.startsWith(ORDER_PREFIX)) continue
            // hold:turn:s is the same logical key as turn:s (parked shade id).
            if (exceptKey != null && logicalDedupKey(k) == exceptKey) continue
            val id = when (v) {
                is Int -> v
                is Number -> v.toInt()
                else -> continue
            }
            if (isAwayNotifId(id)) out += id
        }
        return out
    }

    /**
     * Dedup keys already allocated in persisted key→id prefs.
     * After process death [CodeAwayNotifier]'s in-memory [posted] set is empty; seeding
     * from prefs keeps reconnect from re-alerting a shade entry that survived the kill.
     * [CodeAwayNotifier.clearTurnDoneDedup] removes the turn-done prefs row so a later
     * finished turn is not re-suppressed after process death.
     */
    fun postedKeysFromPrefs(entries: Map<String, *>): Set<String> {
        val out = LinkedHashSet<String>()
        for ((k, v) in entries) {
            // Parked shade ids are not a dedup seed. clearTurnDoneDedup writes them
            // in the same prefs commit that drops the live row.
            // Order rows live in the same file; they are not allocations.
            if (k.startsWith(HOLD_PREFIX) || k.startsWith(ORDER_PREFIX)) continue
            val id = when (v) {
                is Int -> v
                is Number -> v.toInt()
                else -> continue
            }
            if (isAwayNotifId(id)) out += k
        }
        return out
    }

    /**
     * Shades still allocated, including a [HOLD_PREFIX] row. [postedKeysFromPrefs] skips
     * that prefix so the next finish can alert; the cap still has to count the notification.
     */
    fun visibleKeysFromPrefs(entries: Map<String, *>): Set<String> {
        val out = LinkedHashSet<String>()
        for ((k, v) in entries) {
            if (k.startsWith(ORDER_PREFIX)) continue
            val id = when (v) {
                is Int -> v
                is Number -> v.toInt()
                else -> continue
            }
            if (isAwayNotifId(id)) out += logicalDedupKey(k)
        }
        return out
    }

    /**
     * Prefs key for a shade id parked by clearTurnDoneDedup. Lives in the same
     * file as the live allocation so the drop and the park are one commit.
     */
    fun holdPrefKey(dedupKey: String): String = HOLD_PREFIX + dedupKey

    /** Strip [HOLD_PREFIX] once. Live dedup keys are unchanged. */
    fun logicalDedupKey(prefKey: String): String =
        if (prefKey.startsWith(HOLD_PREFIX)) prefKey.removePrefix(HOLD_PREFIX) else prefKey

    /**
     * Prefs key for the shade's age. SharedPreferences iteration order is not post order,
     * so a cold start cannot use the map itself to decide which shade is oldest.
     */
    fun orderPrefKey(dedupKey: String): String = ORDER_PREFIX + dedupKey

    /**
     * Oldest shade first. [entries] may be in any iteration order; the stored seq decides.
     * Keys with no seq are omitted so the caller can treat them as older than these.
     */
    fun orderedVisibleKeys(entries: Map<String, *>): List<String> {
        val pairs = ArrayList<Pair<Int, String>>(entries.size)
        for ((k, v) in entries) {
            if (!k.startsWith(ORDER_PREFIX)) continue
            val seq = when (v) {
                is Int -> v
                is Number -> v.toInt()
                else -> continue
            }
            if (seq <= 0) continue
            pairs += seq to k.removePrefix(ORDER_PREFIX)
        }
        pairs.sortBy { it.first }
        return pairs.map { it.second }
    }

    /**
     * Session that owns [dedupKey], preferring the longest id.
     * Session ids may contain ':'; a shorter id must not steal `approval:ab:cd:req`.
     */
    fun sessionIdForDedupKey(dedupKey: String, sessionIds: Collection<String>): String? =
        sessionIds.filter { sid ->
            sid.isNotEmpty() && (
                dedupKey == dedupKey(Kind.TURN_DONE, sid) ||
                    dedupKey.startsWith("approval:$sid:")
                )
        }.maxByOrNull { it.length }

    /**
     * True when [dedupKey] is [sessionId]'s alert, not a longer id that shares its
     * prefix. `approval:ab:cd:req` starts with `approval:ab:`; the longest known id wins.
     * [sessionId] is always a candidate so a session whose token was just cleared still
     * matches its own keys.
     */
    fun keyBelongsToSession(
        dedupKey: String,
        sessionId: String,
        knownSessionIds: Collection<String>,
    ): Boolean {
        if (sessionId.isEmpty()) return false
        val logical = logicalDedupKey(dedupKey)
        val candidates = LinkedHashSet<String>(knownSessionIds.size + 1)
        for (id in knownSessionIds) if (id.isNotEmpty()) candidates.add(id)
        candidates.add(sessionId)
        return sessionIdForDedupKey(logical, candidates) == sessionId
    }

    /**
     * Activity PendingIntent request code. One per posted notification.
     * A hash of the session id collides, and FLAG_UPDATE_CURRENT then opens the wrong session.
     */
    fun contentRequestCode(notifId: Int): Int = notifId

    /**
     * Broadcast request code for Allow / Deny. Stays out of the [contentRequestCode] range
     * for every away notification id, so one alert cannot replace another's extras.
     */
    fun actionRequestCode(notifId: Int, allow: Boolean): Int {
        val tag = if (allow) ACTION_ALLOW_TAG else ACTION_DENY_TAG
        return notifId xor tag
    }

    /**
     * Broadcast request code for shade swipe-dismiss. Stays out of content / Allow / Deny
     * ranges so a DeleteIntent cannot replace another alert's extras.
     */
    fun dismissRequestCode(notifId: Int): Int = notifId xor ACTION_DISMISS_TAG

    /**
     * True when a notification channel can still alert.
     * [importanceNone] is [android.app.NotificationManager.IMPORTANCE_NONE] (0).
     */
    fun channelCanNotify(importance: Int, importanceNone: Int = 0): Boolean =
        importance != importanceNone

    fun approvalHeadline(title: String): String {
        val t = title.trim()
        return if (t.isEmpty()) "Approval needed" else "Approval needed · $t"
    }

    fun turnDoneHeadline(title: String): String {
        val t = title.trim()
        return if (t.isEmpty()) "Turn finished" else "Turn finished · $t"
    }

    /**
     * Prefer once-allow; fall back to always-allow for the notification action.
     * Several once-allows are a choice (a question), not one Allow the shade can tap.
     */
    fun pickAllow(options: List<ApprovalOption>): ApprovalOption? {
        val once = options.filter { it.kind == ApprovalOption.Kind.ALLOW_ONCE }
        if (once.size > 1) return null
        return once.firstOrNull() ?: options.firstOrNull { it.kind == ApprovalOption.Kind.ALLOW_ALWAYS }
    }

    /**
     * Prefer once-reject; fall back to always-reject.
     * Several once-rejects are a choice, not one Deny the shade can tap.
     */
    fun pickDeny(options: List<ApprovalOption>): ApprovalOption? {
        val once = options.filter { it.kind == ApprovalOption.Kind.REJECT_ONCE }
        if (once.size > 1) return null
        return once.firstOrNull()
            ?: options.firstOrNull { it.kind == ApprovalOption.Kind.REJECT_ALWAYS }
    }

    /** Whether [posted] already contains this key (skip re-alert). */
    fun shouldPost(posted: Set<String>, key: String): Boolean = key !in posted

    /**
     * C2: post a turn-finished away notif only for a live turn that ended naturally.
     * Local Stop / forget cancel use stopReason "cancelled" — never alert those.
     */
    fun shouldNotifyTurnDone(sessionWasRunning: Boolean, stopReason: String): Boolean =
        sessionWasRunning && stopReason != "cancelled"

    const val NOTIF_ID_BASE = 0x5A00_0000
    const val NOTIF_ID_MASK = 0x00FF_FFFF

    /** Prefix for a parked shade id in the notif-id prefs. Not a dedup seed. */
    const val HOLD_PREFIX = "hold:"

    /** Prefix for a shade's age in the notif-id prefs. Not an allocation. */
    const val ORDER_PREFIX = "ord:"

    /** High bits clear of [NOTIF_ID_BASE] (0x5A…) so Allow, Deny, dismiss, and the tap target never share a code. */
    private const val ACTION_ALLOW_TAG = 0x0100_0000
    private const val ACTION_DENY_TAG = 0x0200_0000
    private const val ACTION_DISMISS_TAG = 0x0300_0000
}
