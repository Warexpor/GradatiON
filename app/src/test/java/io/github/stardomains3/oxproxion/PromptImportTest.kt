package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Prompt Import used a case-sensitive title check; the editor rejects ignore-case duplicates,
 * so a backup with "foo" next to a saved "Foo" used to create a second row.
 */
class PromptImportTest {

    @Test
    fun matchingTitleIgnoreCaseIsSkipped() {
        val current = listOf(Prompt("Foo", "old body"))
        val imported = listOf(
            Prompt("foo", "new body"),
            Prompt("Bar", "fresh"),
        )
        val plan = planPromptImport(imported, current)
        assertEquals(listOf("Foo", "Bar"), plan.map { it.title })
        assertEquals("old body", plan[0].prompt)
        assertEquals("fresh", plan[1].prompt)
    }

    @Test
    fun newTitlesAreAppended() {
        val plan = planPromptImport(
            listOf(Prompt("Terse", "Be brief.")),
            emptyList(),
        )
        assertEquals(listOf(Prompt("Terse", "Be brief.")), plan)
    }
}
