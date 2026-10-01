package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.code.CodeModelSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodeModelSelectionTest {

    @Test fun defaultModelFirstOrNull() {
        assertNull(CodeModelSelection.defaultModel(emptyList()))
        assertEquals("a", CodeModelSelection.defaultModel(listOf("a", "b")))
    }

    @Test fun resolveKeepsCurrentWhenStillOffered() {
        assertEquals(
            "b",
            CodeModelSelection.resolveSelection(listOf("a", "b", "c"), "b"),
        )
    }

    @Test fun resolveFallsBackWhenCurrentMissing() {
        assertEquals(
            "a",
            CodeModelSelection.resolveSelection(listOf("a", "b"), "gone"),
        )
        assertEquals(
            "a",
            CodeModelSelection.resolveSelection(listOf("a", "b"), null),
        )
    }

    @Test fun resolveEmptyAlwaysNull() {
        assertNull(CodeModelSelection.resolveSelection(emptyList(), "x"))
        assertNull(CodeModelSelection.resolveSelection(emptyList(), null))
    }

    @Test fun listLabelDropsProviderOnly() {
        assertEquals("claude-sonnet-4", CodeModelSelection.listLabel("anthropic/claude-sonnet-4"))
        assertEquals("claude/sonnet", CodeModelSelection.listLabel("anthropic/claude/sonnet"))
        assertEquals("opus", CodeModelSelection.listLabel("provider:opus"))
        assertEquals("claude-sonnet-4", CodeModelSelection.listLabel("claude-sonnet-4"))
        assertEquals("anthropic", CodeModelSelection.listProvider("anthropic/claude-sonnet-4"))
        assertNull(CodeModelSelection.listProvider("claude-sonnet-4"))
    }

    @Test fun pillLabelShortensPathAndProvider() {
        assertEquals("claude-sonnet-4", CodeModelSelection.pillLabel("claude-sonnet-4"))
        assertEquals("sonnet", CodeModelSelection.pillLabel("anthropic/claude/sonnet"))
        assertEquals("opus", CodeModelSelection.pillLabel("provider:opus"))
        val long = "very-long-model-identifier-that-exceeds"
        assertEquals(
            "very-long-model-ident…",
            CodeModelSelection.pillLabel(long, maxLen = 22),
        )
    }
}
