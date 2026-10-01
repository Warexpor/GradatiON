package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.CodeSessionSummary
import io.github.stardomains3.oxproxion.code.HarnessKind
import io.github.stardomains3.oxproxion.code.PermissionMode
import io.github.stardomains3.oxproxion.code.mergeListSessionsSummary
import io.github.stardomains3.oxproxion.code.revertPermissionModeIfCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure merge used by CodeHub.refreshSessions (review #14 Y1) + F2 CAS helper. */
class CodeHubMergeTest {

    private fun summary(
        id: String = "s1",
        model: String? = null,
        lastSeq: Long? = null,
        title: String = "Title",
        preview: String = "prev",
        permissionMode: PermissionMode = PermissionMode.ASK,
    ) = CodeSessionSummary(
        id = id,
        hostId = "h1",
        harness = HarnessKind.CLAUDE_CODE,
        workspace = "/w",
        title = title,
        createdAt = 1L,
        updatedAt = 2L,
        permissionMode = permissionMode,
        model = model,
        preview = preview,
        lastSeq = lastSeq,
    )

    @Test
    fun keepsLocalModelWhenRemoteOmits() {
        val local = summary(model = "claude-sonnet-4", lastSeq = 7L)
        val remote = summary(model = null, lastSeq = null, title = "", preview = "")
        val merged = mergeListSessionsSummary(remote, local)
        assertEquals("claude-sonnet-4", merged.model)
        assertEquals(7L, merged.lastSeq)
        assertEquals("Title", merged.title)
        assertEquals("prev", merged.preview)
    }

    @Test
    fun prefersRemoteModelWhenPresent() {
        val local = summary(model = "claude-sonnet-4")
        val remote = summary(model = "claude-opus-4")
        assertEquals("claude-opus-4", mergeListSessionsSummary(remote, local).model)
    }

    @Test
    fun bothNullModelStaysNull() {
        assertNull(mergeListSessionsSummary(summary(model = null), summary(model = null)).model)
    }

    @Test
    fun preservesNonAskPermissionWhenRemoteIsAsk() {
        val local = summary(permissionMode = PermissionMode.AUTO_EDIT)
        val remote = summary(permissionMode = PermissionMode.ASK)
        assertEquals(
            PermissionMode.AUTO_EDIT,
            mergeListSessionsSummary(remote, local).permissionMode,
        )
    }

    @Test
    fun takesRemoteNonAskPermission() {
        val local = summary(permissionMode = PermissionMode.ASK)
        val remote = summary(permissionMode = PermissionMode.FULL_AUTO)
        assertEquals(
            PermissionMode.FULL_AUTO,
            mergeListSessionsSummary(remote, local).permissionMode,
        )
    }

    @Test
    fun revertPermissionCasWhenStillOptimistic() {
        assertEquals(
            PermissionMode.ASK,
            revertPermissionModeIfCurrent(
                current = PermissionMode.AUTO_EDIT,
                attempted = PermissionMode.AUTO_EDIT,
                previous = PermissionMode.ASK,
            ),
        )
    }

    @Test
    fun revertPermissionCasSkipsWhenNewerToggleWon() {
        // Ask→Auto fails late after Auto→Full already succeeded: do not clobber Full back to Ask.
        assertNull(
            revertPermissionModeIfCurrent(
                current = PermissionMode.FULL_AUTO,
                attempted = PermissionMode.AUTO_EDIT,
                previous = PermissionMode.ASK,
            ),
        )
    }

    @Test
    fun keepsLocalTitleWhenRemoteHasTitle() {
        // G2: phone-local rename must not be clobbered by listSessions refresh.
        val local = summary(title = "My rename")
        val remote = summary(title = "Bridge first line")
        assertEquals("My rename", mergeListSessionsSummary(remote, local).title)
    }

    @Test
    fun takesRemoteTitleWhenLocalBlank() {
        val local = summary(title = "")
        val remote = summary(title = "From bridge")
        assertEquals("From bridge", mergeListSessionsSummary(remote, local).title)
    }

    @Test
    fun takesRemoteTitleWhenThePhoneHasNotRenamed() {
        val local = summary(title = "First line of the prompt")
        val remote = summary(title = "Fix the footer")
        assertEquals(
            "Fix the footer",
            mergeListSessionsSummary(remote, local, keepLocalTitle = false).title,
        )
    }

    @Test
    fun unpinnedKeepsLocalTitleWhenRemoteIsBlank() {
        val local = summary(title = "First line")
        val remote = summary(title = "")
        assertEquals(
            "First line",
            mergeListSessionsSummary(remote, local, keepLocalTitle = false).title,
        )
    }
}
