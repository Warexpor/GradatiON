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
        assertEquals("Cursor Agent", opts.first { it.second == HarnessKind.CURSOR_CLI }.first)
    }

    @Test fun cursorAgentLabelReplacesStaleBridgeName() {
        val live = listOf(
            HarnessInfo("cursor-cli", "Cursor CLI", true),
            HarnessInfo("cursor-agent", "cursor-agent", false)
        )
        val opts = AgentPillOptions.resolve(live, HarnessKind.CURSOR_CLI)
        assertEquals(
            listOf(
                "Cursor Agent" to HarnessKind.CURSOR_CLI,
                "Cursor Agent" to HarnessKind.CURSOR_CLI
            ),
            opts
        )
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
