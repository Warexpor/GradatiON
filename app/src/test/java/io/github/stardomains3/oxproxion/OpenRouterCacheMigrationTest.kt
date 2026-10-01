package io.github.stardomains3.oxproxion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterCacheMigrationTest {

    private fun model(reasoning: Boolean) = LlmModel(
        displayName = "m",
        apiIdentifier = "m",
        isVisionCapable = false,
        isReasoningCapable = reasoning,
    )

    @Test fun anOldCacheWhereNothingIsReasoningRefetchesOnce() {
        assertTrue(openRouterCacheMissingReasoning(alreadyMigrated = false, models = listOf(model(false), model(false))))
    }

    @Test fun aListThatAlreadyHasAReasoningModelStays() {
        assertFalse(openRouterCacheMissingReasoning(alreadyMigrated = false, models = listOf(model(false), model(true))))
    }

    @Test fun theFirstModelNotBeingReasoningDoesNotWipeAMigratedList() {
        assertFalse(openRouterCacheMissingReasoning(alreadyMigrated = true, models = listOf(model(false))))
    }

    @Test fun anEmptyListIsNotAStaleCache() {
        assertFalse(openRouterCacheMissingReasoning(alreadyMigrated = false, models = emptyList()))
    }
}
