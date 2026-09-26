package io.github.stardomains3.oxproxion.code

/**
 * Pure helpers for the Code-mode pinned approval bar (plan §5.5): show a compact glass strip
 * above the composer while a pending approval card is off-screen in the transcript.
 */
object CodeApprovalBar {

    /**
     * Whether the pinned bar should be visible.
     *
     * @param pendingIndex adapter position of the pending approval, or -1 if none
     * @param firstVisible [LinearLayoutManager.findFirstVisibleItemPosition]
     * @param lastVisible [LinearLayoutManager.findLastVisibleItemPosition]
     */
    fun shouldShowBar(pendingIndex: Int, firstVisible: Int, lastVisible: Int): Boolean {
        if (pendingIndex < 0) return false
        // List not laid out yet — treat as not on-screen so the bar can appear until scroll settles.
        if (firstVisible < 0 || lastVisible < 0) return true
        return pendingIndex < firstVisible || pendingIndex > lastVisible
    }

    /** First unanswered approval in transcript order, or null. */
    fun findPending(events: List<CodeEvent>): CodeEvent.Approval? =
        events.firstOrNull { it is CodeEvent.Approval && it.chosen == null } as? CodeEvent.Approval

    /** Adapter index of [requestId], or -1. */
    fun indexOfApproval(rows: List<TranscriptRow>, requestId: String): Int =
        rows.indexOfFirst {
            it is TranscriptRow.Event &&
                (it.event as? CodeEvent.Approval)?.requestId == requestId
        }
}
