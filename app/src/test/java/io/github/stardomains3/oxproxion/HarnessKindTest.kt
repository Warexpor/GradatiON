package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.HarnessInfo
import io.github.stardomains3.oxproxion.code.HarnessKind
import org.junit.Assert.assertEquals
import org.junit.Test

class HarnessKindTest {

    @Test fun cursorAgentDisplayNameKeepsWireId() {
        assertEquals("cursor-cli", HarnessKind.CURSOR_CLI.id)
        assertEquals("Cursor Agent", HarnessKind.CURSOR_CLI.displayName)
        assertEquals("Cursor", HarnessKind.CURSOR_CLI.shortName)
    }

    @Test fun fromIdMapsCursorAliases() {
        assertEquals(HarnessKind.CURSOR_CLI, HarnessKind.fromId("cursor-cli"))
        assertEquals(HarnessKind.CURSOR_CLI, HarnessKind.fromId("cursor-agent"))
        assertEquals(HarnessKind.CURSOR_CLI, HarnessKind.fromId("cursor"))
    }

    @Test fun fromIdUnknownAndNullStayCustom() {
        assertEquals(HarnessKind.CUSTOM, HarnessKind.fromId(null))
        assertEquals(HarnessKind.CUSTOM, HarnessKind.fromId(""))
        assertEquals(HarnessKind.CUSTOM, HarnessKind.fromId("not-a-harness"))
        assertEquals(HarnessKind.CUSTOM, HarnessKind.fromId("agent"))
    }

    @Test fun fromIdStillMatchesOtherHarnesses() {
        assertEquals(HarnessKind.CLAUDE_CODE, HarnessKind.fromId("claude-code"))
        assertEquals(HarnessKind.CODEX, HarnessKind.fromId("codex"))
        assertEquals(HarnessKind.OPENCODE, HarnessKind.fromId("opencode"))
        assertEquals(HarnessKind.GROK_BUILD, HarnessKind.fromId("grok-build"))
        assertEquals(HarnessKind.PI, HarnessKind.fromId("pi"))
    }

    @Test fun fromIdFoldsCaseAndUnderscores() {
        assertEquals(HarnessKind.CLAUDE_CODE, HarnessKind.fromId("Claude_Code"))
        assertEquals(HarnessKind.CLAUDE_CODE, HarnessKind.fromId("CLAUDE-CODE"))
        assertEquals(HarnessKind.GROK_BUILD, HarnessKind.fromId("grok_build"))
        assertEquals(HarnessKind.CODEX, HarnessKind.fromId(" Codex "))
        assertEquals(HarnessKind.CURSOR_CLI, HarnessKind.fromId("CURSOR-AGENT"))
        assertEquals(HarnessKind.CURSOR_CLI, HarnessKind.fromId("cursor_cli"))
        // A permission-mode word must not become Cursor.
        assertEquals(HarnessKind.CUSTOM, HarnessKind.fromId("Agent"))
        assertEquals(HarnessKind.CUSTOM, HarnessKind.fromId("open-code"))
    }

    @Test fun harnessInfoKindAndLabelFollowAliases() {
        val stale = HarnessInfo("cursor-cli", "Cursor CLI", available = false)
        assertEquals(HarnessKind.CURSOR_CLI, stale.kind)
        assertEquals("Cursor Agent", stale.label)
        assertEquals("Cursor Agent", HarnessInfo("cursor-agent", "cursor-agent", true).label)
        assertEquals("Cursor Agent", HarnessInfo("cursor", "Cursor", true).label)
        assertEquals("OpenCode", HarnessInfo("opencode", "OpenCode", true).label)
        assertEquals("my-agent", HarnessInfo("my-agent", "my-agent", true).label)
    }
}
