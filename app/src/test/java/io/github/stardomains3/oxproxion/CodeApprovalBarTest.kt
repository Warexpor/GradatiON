package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.ApprovalOption
import io.github.stardomains3.oxproxion.code.CodeApprovalBar
import io.github.stardomains3.oxproxion.code.CodeEvent
import io.github.stardomains3.oxproxion.code.ToolKind
import io.github.stardomains3.oxproxion.code.TranscriptRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeApprovalBarTest {

    private fun approval(
        requestId: String,
        chosen: ApprovalOption.Kind? = null,
        title: String = "Edit SettingsRepository.kt",
    ) = CodeEvent.Approval(
        key = "approval:$requestId",
        at = 1L,
        requestId = requestId,
        callId = null,
        title = title,
        kind = ToolKind.EDIT,
        options = listOf(
            ApprovalOption("allow", "Allow", ApprovalOption.Kind.ALLOW_ONCE),
            ApprovalOption("always", "Always allow edits", ApprovalOption.Kind.ALLOW_ALWAYS),
        ),
        chosen = chosen,
    )

    // Clear viewport: paddingTop=0 .. height-paddingBottom=800 (dock occupies 800..)
    private val clearTop = 0
    private val clearBottom = 800

    @Test fun intersectsClearWhenOverlapping() {
        assertTrue(CodeApprovalBar.intersectsClear(100, 200, clearTop, clearBottom))
        assertTrue(CodeApprovalBar.intersectsClear(700, 900, clearTop, clearBottom)) // straddles dock
        assertTrue(CodeApprovalBar.intersectsClear(-50, 50, clearTop, clearBottom))
    }

    @Test fun noIntersectWhenEntirelyUnderDock() {
        assertFalse(CodeApprovalBar.intersectsClear(810, 950, clearTop, clearBottom))
        assertFalse(CodeApprovalBar.intersectsClear(800, 950, clearTop, clearBottom)) // edge: top == clearBottom
    }

    @Test fun noIntersectWhenEntirelyAboveClear() {
        assertFalse(CodeApprovalBar.intersectsClear(-200, -10, clearTop, clearBottom))
        assertFalse(CodeApprovalBar.intersectsClear(-50, 0, clearTop, clearBottom)) // edge: bottom == clearTop
    }

    @Test fun shouldShowBarWhenPendingOffScreenAbove() {
        assertTrue(
            CodeApprovalBar.shouldShowBar(
                pendingIndex = 2,
                itemTop = -200,
                itemBottom = -40,
                clearTop = clearTop,
                clearBottom = clearBottom,
            )
        )
    }

    @Test fun shouldShowBarWhenPendingOffScreenBelow() {
        assertTrue(
            CodeApprovalBar.shouldShowBar(
                pendingIndex = 20,
                itemTop = 1200,
                itemBottom = 1400,
                clearTop = clearTop,
                clearBottom = clearBottom,
            )
        )
    }

    @Test fun showBarWhenPendingUnderDock() {
        // clipToPadding=false: row laid out in paddingBottom band — adapter-visible but not clear.
        assertTrue(
            CodeApprovalBar.shouldShowBar(
                pendingIndex = 12,
                itemTop = 810,
                itemBottom = 980,
                clearTop = clearTop,
                clearBottom = clearBottom,
            )
        )
    }

    @Test fun hideBarWhenPendingOnScreenInClear() {
        assertFalse(
            CodeApprovalBar.shouldShowBar(
                pendingIndex = 7,
                itemTop = 100,
                itemBottom = 280,
                clearTop = clearTop,
                clearBottom = clearBottom,
            )
        )
        // Straddles dock edge but still intersects clear → hide.
        assertFalse(
            CodeApprovalBar.shouldShowBar(
                pendingIndex = 12,
                itemTop = 700,
                itemBottom = 950,
                clearTop = clearTop,
                clearBottom = clearBottom,
            )
        )
    }

    @Test fun hideBarWhenNoPending() {
        assertFalse(
            CodeApprovalBar.shouldShowBar(
                pendingIndex = -1,
                itemTop = 100,
                itemBottom = 200,
                clearTop = clearTop,
                clearBottom = clearBottom,
            )
        )
    }

    @Test fun showBarWhenListNotLaidOutYet() {
        assertTrue(
            CodeApprovalBar.shouldShowBar(
                pendingIndex = 3,
                itemTop = null,
                itemBottom = null,
                clearTop = 0,
                clearBottom = 0,
            )
        )
        assertTrue(
            CodeApprovalBar.shouldShowBar(
                pendingIndex = 3,
                itemTop = null,
                itemBottom = null,
                clearTop = clearTop,
                clearBottom = clearBottom,
            )
        )
    }

    @Test fun findPendingSkipsAnswered() {
        val events = listOf(
            approval("a", chosen = ApprovalOption.Kind.ALLOW_ONCE),
            approval("b"),
            approval("c"),
        )
        assertEquals("b", CodeApprovalBar.findPending(events)?.requestId)
    }

    @Test fun findPendingNone() {
        assertNull(CodeApprovalBar.findPending(emptyList()))
        assertNull(
            CodeApprovalBar.findPending(
                listOf(approval("a", chosen = ApprovalOption.Kind.REJECT_ONCE))
            )
        )
    }

    @Test fun findPinnedPendingPinsFirstOffClear() {
        val events = listOf(
            approval("a", chosen = ApprovalOption.Kind.ALLOW_ONCE),
            approval("b"),
            approval("c"),
        )
        val rows = events.map { TranscriptRow.Event(it) }
        // b on-screen in clear, c under dock / off-screen → pin c
        val off = setOf(2) // adapter index of c
        val pinned = CodeApprovalBar.findPinnedPending(events, rows) { it in off }
        assertEquals("c", pinned?.requestId)
    }

    @Test fun findPinnedPendingNullWhenAllOnClear() {
        val events = listOf(approval("b"), approval("c"))
        val rows = events.map { TranscriptRow.Event(it) }
        assertNull(CodeApprovalBar.findPinnedPending(events, rows) { false })
    }

    @Test fun findPinnedPendingPinsFirstWhenOffClear() {
        val events = listOf(approval("b"), approval("c"))
        val rows = events.map { TranscriptRow.Event(it) }
        val pinned = CodeApprovalBar.findPinnedPending(events, rows) { true }
        assertEquals("b", pinned?.requestId)
    }

    @Test fun findPinnedPendingWhenMissingFromAdapter() {
        val events = listOf(approval("ghost"))
        val rows = emptyList<TranscriptRow>()
        val pinned = CodeApprovalBar.findPinnedPending(events, rows) { false }
        assertEquals("ghost", pinned?.requestId)
    }

    @Test fun indexOfApprovalInRows() {
        val rows = listOf(
            TranscriptRow.Event(CodeEvent.AgentText("t", 1L, "hi")),
            TranscriptRow.Event(approval("req-9")),
            TranscriptRow.Working,
        )
        assertEquals(1, CodeApprovalBar.indexOfApproval(rows, "req-9"))
        assertEquals(-1, CodeApprovalBar.indexOfApproval(rows, "missing"))
    }
}
