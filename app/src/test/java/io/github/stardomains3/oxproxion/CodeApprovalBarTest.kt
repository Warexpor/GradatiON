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

    @Test fun shouldShowBarWhenPendingOffScreenAbove() {
        assertTrue(CodeApprovalBar.shouldShowBar(pendingIndex = 2, firstVisible = 5, lastVisible = 12))
    }

    @Test fun shouldShowBarWhenPendingOffScreenBelow() {
        assertTrue(CodeApprovalBar.shouldShowBar(pendingIndex = 20, firstVisible = 5, lastVisible = 12))
    }

    @Test fun hideBarWhenPendingOnScreen() {
        assertFalse(CodeApprovalBar.shouldShowBar(pendingIndex = 7, firstVisible = 5, lastVisible = 12))
        assertFalse(CodeApprovalBar.shouldShowBar(pendingIndex = 5, firstVisible = 5, lastVisible = 12))
        assertFalse(CodeApprovalBar.shouldShowBar(pendingIndex = 12, firstVisible = 5, lastVisible = 12))
    }

    @Test fun hideBarWhenNoPending() {
        assertFalse(CodeApprovalBar.shouldShowBar(pendingIndex = -1, firstVisible = 0, lastVisible = 10))
    }

    @Test fun showBarWhenListNotLaidOutYet() {
        assertTrue(CodeApprovalBar.shouldShowBar(pendingIndex = 3, firstVisible = -1, lastVisible = -1))
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
