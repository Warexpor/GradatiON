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
