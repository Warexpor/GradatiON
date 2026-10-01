package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.CodeSessionFilter
import io.github.stardomains3.oxproxion.code.CodeSessionState
import io.github.stardomains3.oxproxion.code.CodeSessionSummary
import io.github.stardomains3.oxproxion.code.HarnessKind
import io.github.stardomains3.oxproxion.code.PermissionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class CodeSessionFilterTest {

    private fun session(
        id: String,
        title: String,
        preview: String = "",
        workspace: String = "~/code",
        branch: String? = null,
        harness: HarnessKind = HarnessKind.CLAUDE_CODE,
        model: String? = null,
    ) = CodeSessionState(
        CodeSessionSummary(
            id = id,
            hostId = "host",
            harness = harness,
            workspace = workspace,
            title = title,
            createdAt = 1L,
            updatedAt = 2L,
            permissionMode = PermissionMode.ASK,
            preview = preview,
            model = model,
            branch = branch,
        )
    )

    private val list = listOf(
        session("abc-111", "Dark mode toggle", "Switched prefs"),
        session("xyz-222", "Fix login crash", "NullPointerException"),
        session("sid-333", "Refactor Hub", "persistSessions"),
    )

    @Test fun blankQueryReturnsAll() {
        assertEquals(list, CodeSessionFilter.filterSessions("", list))
        assertEquals(list, CodeSessionFilter.filterSessions("   ", list))
    }

    @Test fun matchesTitleCaseInsensitive() {
        val hit = CodeSessionFilter.filterSessions("dark MODE", list)
        assertEquals(listOf(list[0]), hit)
    }

    @Test fun matchesPreview() {
        val hit = CodeSessionFilter.filterSessions("NullPointer", list)
        assertEquals(listOf(list[1]), hit)
    }

    @Test fun matchesIdSubstring() {
        val hit = CodeSessionFilter.filterSessions("xyz-2", list)
        assertEquals(listOf(list[1]), hit)
    }

    @Test fun matchesFolderBranchHarnessAndModel() {
        val claude = session("a", "Theme", workspace = "/home/me/GradatiON", branch = "liquid-glass", model = "claude-sonnet")
        val cursor = session("b", "Other", workspace = "/srv/api", branch = "main", harness = HarnessKind.CURSOR_CLI)
        val both = listOf(claude, cursor)
        assertEquals(listOf(claude), CodeSessionFilter.filterSessions("gradation", both))
        assertEquals(listOf(claude), CodeSessionFilter.filterSessions("liquid", both))
        assertEquals(listOf(claude), CodeSessionFilter.filterSessions("sonnet", both))
        assertEquals(listOf(cursor), CodeSessionFilter.filterSessions("cursor", both))
        assertEquals(listOf(claude), CodeSessionFilter.filterSessions("claude", both))
    }

    @Test fun noMatchIsEmpty() {
        assertEquals(emptyList<CodeSessionState>(), CodeSessionFilter.filterSessions("zzzz", list))
    }

    @Test fun emptySourceStaysEmpty() {
        assertEquals(emptyList<CodeSessionState>(), CodeSessionFilter.filterSessions("x", emptyList()))
    }
}
