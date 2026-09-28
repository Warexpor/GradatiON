package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Test

class RpImportRulesTest {

    @Test
    fun characterOverwrite_countsMatchingExportKeys() {
        val incoming = listOf(
            RpCharacterExport(name = "A", exportKey = "k1"),
            RpCharacterExport(name = "B", exportKey = "k2"),
            RpCharacterExport(name = "C", exportKey = ""),
            RpCharacterExport(name = "D", exportKey = "k3")
        )
        assertEquals(2, RpImportRules.characterOverwriteCount(incoming, setOf("k1", "k3")))
    }

    @Test
    fun loreOverwrite_matchesNamesIgnoreCase() {
        val incoming = listOf(
            RpLorebookExport(name = "World"),
            RpLorebookExport(name = "new book"),
            RpLorebookExport(name = "WORLD")
        )
        assertEquals(2, RpImportRules.loreOverwriteCount(incoming, listOf("world")))
    }

    @Test
    fun matchingCharacters_byKey_orByNameWhenNoKey() {
        val library = listOf(
            RpCharacter(id = 1, exportKey = "k1", name = "Mira"),
            RpCharacter(id = 2, exportKey = "k2", name = "Kestrel"),
            RpCharacter(id = 3, exportKey = "k3", name = "Ondine")
        )
        val incoming = listOf(
            RpCharacterExport(name = "Renamed", exportKey = "k1"),
            RpCharacterExport(name = "kestrel"),              // Tavern card: no key, same name
            RpCharacterExport(name = "Ondine", exportKey = "other"), // has a key that isn't ours: a different character
            RpCharacterExport(name = "Fresh")
        )
        assertEquals(listOf(1L, 2L), RpImportRules.matchingCharacters(incoming, library).map { it.id })
    }

    @Test
    fun uniqueName_numbersCollisionsIgnoringCase() {
        assertEquals("World", RpImportRules.uniqueName("World", listOf("Other")))
        assertEquals("World (2)", RpImportRules.uniqueName("World", listOf("world")))
        assertEquals("World (3)", RpImportRules.uniqueName("World", listOf("World", "World (2)")))
    }

    @Test
    fun overwrite_zeroWhenLibraryEmpty() {
        assertEquals(0, RpImportRules.characterOverwriteCount(
            listOf(RpCharacterExport(name = "A", exportKey = "k1")),
            emptySet()
        ))
        assertEquals(0, RpImportRules.loreOverwriteCount(
            listOf(RpLorebookExport(name = "World")),
            emptyList()
        ))
    }
}
