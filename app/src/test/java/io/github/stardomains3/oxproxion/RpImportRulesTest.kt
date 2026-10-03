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
    }

    @Test
    fun loreOverwrite_matchesNamesIgnoreCase() {
        val incoming = listOf(
            RpLorebookExport(name = "World"),
            RpLorebookExport(name = "new book"),
            RpLorebookExport(name = "WORLD")
        )
        assertEquals(2, RpImportRules.loreOverwriteCount(incoming, listOf("world")))
        assertEquals(1, RpImportRules.loreOverwriteCount(
            listOf(RpLorebookExport(name = " world "), RpLorebookExport(name = "   ")),
            listOf("World"),
        ))
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
