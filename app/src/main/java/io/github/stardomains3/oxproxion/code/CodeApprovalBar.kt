package io.github.stardomains3.oxproxion.code

/**
 * Pure helpers for the Code-mode pinned approval bar (plan §5.5): show a compact glass strip
 * above the composer while a pending approval card is outside the **clear** transcript
 * viewport (paddingTop..<height-paddingBottom), matching chat’s dock-aware follow math.
 */
object CodeApprovalBar {

    /**
     * Whether decorated item bounds intersect the clear viewport.
     * Intervals are half-open: [itemTop, itemBottom) ∩ [clearTop, clearBottom).
     */
    fun intersectsClear(
        itemTop: Int,
        itemBottom: Int,
        clearTop: Int,
        clearBottom: Int,
    ): Boolean = itemTop < clearBottom && itemBottom > clearTop

    /**
     * Whether the pinned bar should be visible for a pending approval at [pendingIndex].
     *
     * “On-screen” means the row’s decorated bounds intersect the clear rect
     * ([clearTop]..<[clearBottom]), typically `paddingTop`..<`height - paddingBottom`.
     * Null bounds (view not attached) or an empty/invalid clear range (list not laid out)
     * count as off-screen so the bar can appear until scroll settles.
     *
     * @param pendingIndex adapter position of the pending approval, or -1 if none
     * @param itemTop decorated top from [LinearLayoutManager.getDecoratedTop], or null
     * @param itemBottom decorated bottom, or null
     */
    fun shouldShowBar(
        pendingIndex: Int,
        itemTop: Int?,
        itemBottom: Int?,
        clearTop: Int,
        clearBottom: Int,
    ): Boolean {
        if (pendingIndex < 0) return false
        if (clearBottom <= clearTop) return true
        if (itemTop == null || itemBottom == null) return true
        return !intersectsClear(itemTop, itemBottom, clearTop, clearBottom)
    }

    /** First unanswered approval in transcript order, or null. */
    fun findPending(events: List<CodeEvent>): CodeEvent.Approval? =
        events.firstOrNull { it is CodeEvent.Approval && it.chosen == null } as? CodeEvent.Approval

    /**
     * Approval to pin in the dock bar: the first unanswered whose row is outside the clear
     * viewport (or not yet in the adapter). Returns null when every pending intersects clear
     * (or there is no pending).
     *
     * @param isOffClearViewport given an adapter index, true when that row should show the bar
     *   (outside clear / not attached / list not laid out)
     */
    fun findPinnedPending(
        events: List<CodeEvent>,
        rows: List<TranscriptRow>,
        isOffClearViewport: (adapterIndex: Int) -> Boolean,
    ): CodeEvent.Approval? {
        for (event in events) {
            val pending = event as? CodeEvent.Approval ?: continue
            if (pending.chosen != null) continue
            val index = indexOfApproval(rows, pending.requestId)
            // Not in the adapter list yet → treat as off-screen.
            if (index < 0 || isOffClearViewport(index)) return pending
        }
        return null
    }

    /** Adapter index of [requestId], or -1. */
    fun indexOfApproval(rows: List<TranscriptRow>, requestId: String): Int =
        rows.indexOfFirst {
            it is TranscriptRow.Event &&
                (it.event as? CodeEvent.Approval)?.requestId == requestId
        }
}
