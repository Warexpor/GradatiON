package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelNamesTest {

    @Test fun stripsMatchingProviderPrefix() {
        assertEquals(
            "Claude Sonnet 4",
            ModelNames.withoutProvider("Anthropic: Claude Sonnet 4", "anthropic/claude-sonnet-4"),
        )
        assertEquals(
            "Grok 4",
            ModelNames.withoutProvider("xAI: Grok 4", "x-ai/grok-4"),
        )
        assertEquals(
            "Llama 3.3 70B",
            ModelNames.withoutProvider("Meta: Llama 3.3 70B", "meta-llama/llama-3.3-70b"),
        )
    }

    @Test fun keepsNameWhenPrefixIsNotTheProvider() {
        assertEquals(
            "GPT-4: turbo",
            ModelNames.withoutProvider("GPT-4: turbo", "openai/gpt-4"),
        )
        assertEquals("Local model", ModelNames.withoutProvider("Local model", "local-model"))
    }

    @Test fun idDropsOneProviderSegment() {
        assertEquals("claude-sonnet-4", ModelNames.idWithoutProvider("anthropic/claude-sonnet-4"))
        assertEquals("claude/sonnet", ModelNames.idWithoutProvider("anthropic/claude/sonnet"))
        assertEquals("opus", ModelNames.idWithoutProvider("provider:opus"))
        assertEquals("claude-sonnet-4", ModelNames.idWithoutProvider("claude-sonnet-4"))
        assertEquals("anthropic", ModelNames.providerOf("anthropic/claude-sonnet-4"))
        assertNull(ModelNames.providerOf("claude-sonnet-4"))
    }
}
