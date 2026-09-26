package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.AgentPillOptions
import io.github.stardomains3.oxproxion.code.HarnessInfo
import io.github.stardomains3.oxproxion.code.HarnessKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPillOptionsTest {

    @Test fun emptyLiveFallsBackToStaticCatalog() {
        val opts = AgentPillOptions.resolve(emptyList(), HarnessKind.CLAUDE_CODE)
        assertTrue(opts.any { it.second == HarnessKind.CLAUDE_CODE })
        assertTrue(opts.none { it.second == HarnessKind.CUSTOM })
        assertEquals(HarnessKind.entries.size - 1, opts.size) // all except CUSTOM
    }

    @Test fun liveListPreferredWhenPresent() {
        val live = listOf(
            HarnessInfo("opencode", "OpenCode", true),
            HarnessInfo("codex", "Codex CLI", true)
        )
        val opts = AgentPillOptions.resolve(live, HarnessKind.OPENCODE)
        assertEquals(listOf("OpenCode" to HarnessKind.OPENCODE, "Codex CLI" to HarnessKind.CODEX), opts)
    }

    @Test fun selectedMissingFromLiveIsPrepended() {
        val live = listOf(HarnessInfo("opencode", "OpenCode", true))
        val opts = AgentPillOptions.resolve(live, HarnessKind.CLAUDE_CODE)
        assertEquals(HarnessKind.CLAUDE_CODE, opts.first().second)
        assertEquals(2, opts.size)
    }
}
