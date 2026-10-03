package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpImportRulesTest {

    @Test
    fun greetingChanged_usesTheLastCopyOfTheCharacter() {
        val earlier = RpCharacterExport(name = "Ada", exportKey = "ada", greeting = "Hi")
        val same = RpCharacterExport(name = "Ada", exportKey = "ada", greeting = "Hello")
        val padded = RpCharacterExport(name = "Ada", exportKey = "ada", greeting = " Hello ")
        assertFalse(RpImportRules.greetingChanged("Hello", "ada", listOf(earlier, same)))
        assertTrue(RpImportRules.greetingChanged("Hello", "ada", listOf(same, earlier)))
        // A trailing space is not a new greeting.
        assertFalse(RpImportRules.greetingChanged("Hello", "ada", listOf(earlier, padded)))
        assertFalse(RpImportRules.greetingChanged("Hello", "ada", listOf(
            RpCharacterExport(name = "Bea", exportKey = "bea", greeting = "Hi"),
        )))
        assertFalse(RpImportRules.greetingChanged("Hello", "", listOf(earlier)))
    }

    @Test
    fun characterOverwrite_countsMatchingExportKeys() {
        val incoming = listOf(
            RpCharacterExport(name = "A", exportKey = "k1"),
            RpCharacterExport(name = "B", exportKey = "k2"),
            RpCharacterExport(name = "C", exportKey = ""),
            RpCharacterExport(name = "D", exportKey = "k3")
        )
        assertEquals(2, RpImportRules.characterOverwriteCount(incoming, setOf("k1", "k3")))
        assertEquals(0, RpImportRules.characterOverwriteCount(
            listOf(RpCharacterExport(name = "   ", exportKey = "k1")),
            setOf("k1"),
        ))
        val twice = listOf(
            RpCharacterExport(name = "Ada", exportKey = "k"),
            RpCharacterExport(name = "Ada", exportKey = "k"),
        )
        assertEquals(1, RpImportRules.characterFileCount(twice))
        assertEquals(1, RpImportRules.characterOverwriteCount(twice, setOf("k")))
        assertEquals(1, RpImportRules.importedCharacterCount(listOf(
            ImportedCharacter(4L, "k", false, null),
            ImportedCharacter(4L, "k", false, null),
        )))
        val keyless = listOf(
            RpCharacterExport(name = "Ada"),
            RpCharacterExport(name = " Ada "),
            RpCharacterExport(name = "Bea"),
        )
        assertEquals(2, RpImportRules.characterFileCount(keyless))
        assertEquals(1, RpImportRules.characterOverwriteCount(
            keyless,
            emptySet(),
            listOf("Ada" to "ada"),
        ))
        // Two locals share the name. The import still updates the newest, so it counts.
        assertEquals(1, RpImportRules.characterOverwriteCount(
            listOf(RpCharacterExport(name = "Ada")),
            emptySet(),
            listOf("Ada" to "old", "Ada" to "new"),
        ))
        // A key in the same file already names Ada. The keyless row is not that update.
        assertEquals(2, RpImportRules.characterFileCount(listOf(
            RpCharacterExport(name = "Ada", exportKey = "k"),
            RpCharacterExport(name = "Ada"),
        )))
        assertEquals(1, RpImportRules.characterOverwriteCount(
            listOf(
                RpCharacterExport(name = "Ada", exportKey = "k"),
                RpCharacterExport(name = "Ada"),
            ),
            setOf("k"),
            listOf("Ada" to "k"),
        ))
    }

    @Test
    fun loreOverwrite_matchesNamesIgnoreCase() {
        val incoming = listOf(
            RpLorebookExport(name = "World"),
            RpLorebookExport(name = "new book"),
            RpLorebookExport(name = "WORLD")
        )
        // World and WORLD are one book. Counting each row said two books would be updated.
        assertEquals(1, RpImportRules.loreOverwriteCount(incoming, listOf("world")))
        assertEquals(2, RpImportRules.loreFileCount(incoming))
        assertEquals(1, RpImportRules.loreOverwriteCount(
            listOf(RpLorebookExport(name = " world "), RpLorebookExport(name = "   ")),
            listOf("World"),
        ))
        assertEquals(0, RpImportRules.loreFileCount(listOf(RpLorebookExport(name = "   "))))
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
