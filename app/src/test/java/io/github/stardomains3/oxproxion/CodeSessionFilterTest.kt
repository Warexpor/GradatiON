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
    ) = CodeSessionState(
        CodeSessionSummary(
            id = id,
            hostId = "host",
            harness = HarnessKind.CLAUDE_CODE,
            workspace = "~/code",
            title = title,
            createdAt = 1L,
            updatedAt = 2L,
            permissionMode = PermissionMode.ASK,
            preview = preview,
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

    @Test fun noMatchIsEmpty() {
        assertEquals(emptyList<CodeSessionState>(), CodeSessionFilter.filterSessions("zzzz", list))
    }

    @Test fun emptySourceStaysEmpty() {
        assertEquals(emptyList<CodeSessionState>(), CodeSessionFilter.filterSessions("x", emptyList()))
    }
}
