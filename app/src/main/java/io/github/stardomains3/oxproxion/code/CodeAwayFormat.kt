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
     * NotificationManager id derived from [dedupKey]. Uses 24-bit entropy under
     * [NOTIF_ID_BASE] so collisions across sessions are rare; stays clear of
     * answer-ready (2) and legacy sticky FGS (1).
     */
    fun notificationId(dedupKey: String): Int {
        val h = dedupKey.hashCode() and 0x00FF_FFFF
        return NOTIF_ID_BASE + h
    }

    fun approvalHeadline(title: String): String {
        val t = title.trim()
        return if (t.isEmpty()) "Approval needed" else "Approval needed · $t"
    }

    fun turnDoneHeadline(title: String): String {
        val t = title.trim()
        return if (t.isEmpty()) "Turn finished" else "Turn finished · $t"
    }

    /** Prefer once-allow; fall back to always-allow for notification action. */
    fun pickAllow(options: List<ApprovalOption>): ApprovalOption? =
        options.firstOrNull { it.kind == ApprovalOption.Kind.ALLOW_ONCE }
            ?: options.firstOrNull { it.kind == ApprovalOption.Kind.ALLOW_ALWAYS }

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
}
