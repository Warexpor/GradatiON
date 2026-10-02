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
            if (exceptKey != null && k == exceptKey) continue
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
     * [clearTurnDoneDedup] still clears memory only so a later finished turn can post again.
     */
    fun postedKeysFromPrefs(entries: Map<String, *>): Set<String> {
        val out = LinkedHashSet<String>()
        for ((k, v) in entries) {
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

    /** Prefer once-reject; fall back to always-reject. */
    fun pickDeny(options: List<ApprovalOption>): ApprovalOption? =
        options.firstOrNull { it.kind == ApprovalOption.Kind.REJECT_ONCE }
            ?: options.firstOrNull { it.kind == ApprovalOption.Kind.REJECT_ALWAYS }

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

    /** High bits clear of [NOTIF_ID_BASE] (0x5A…) so Allow, Deny, and the tap target never share a code. */
    private const val ACTION_ALLOW_TAG = 0x0100_0000
    private const val ACTION_DENY_TAG = 0x0200_0000
}
