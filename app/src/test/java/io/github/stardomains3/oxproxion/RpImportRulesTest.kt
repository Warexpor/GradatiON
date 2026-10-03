package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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

    @Test
    fun greetingChanged_keylessFileUsesTheLastCopyOfThatName() {
        val earlier = RpCharacterExport(name = "Ada", greeting = "Hi")
        val later = RpCharacterExport(name = " Ada ", greeting = "Hello")
        val library = listOf("Ada" to "ada")
        assertFalse(RpImportRules.greetingChanged(
            "Hello", "ada", listOf(earlier, later),
            characterName = "Ada",
            namedKeysNewestFirst = library,
        ))
        assertTrue(RpImportRules.greetingChanged(
            "Hello", "ada", listOf(later, earlier),
            characterName = "Ada",
            namedKeysNewestFirst = library,
        ))
        // A blank row that repeats the key is not the greeting that lands.
        assertFalse(RpImportRules.greetingChanged(
            "Hello",
            "ada",
            listOf(
                RpCharacterExport(name = "Ada", exportKey = "ada", greeting = "Hello"),
                RpCharacterExport(name = "  ", exportKey = "ada", greeting = "Hi"),
            ),
            characterName = "Ada",
            namedKeysNewestFirst = library,
        ))
        // The keyed row is this character. The keyless row is someone else.
        assertFalse(RpImportRules.greetingChanged(
            "Hello",
            "ada",
            listOf(
                RpCharacterExport(name = "Ada", exportKey = "ada", greeting = "Hello"),
                RpCharacterExport(name = "Ada", greeting = "Welcome"),
            ),
            characterName = "Ada",
            namedKeysNewestFirst = library,
        ))
        // Two locals share the name. Only the newest opening follows the file.
        val shared = listOf("Ada" to "new", "Ada" to "old")
        val incoming = listOf(RpCharacterExport(name = "Ada", greeting = "Welcome"))
        assertFalse(RpImportRules.greetingChanged(
            "Hello", "old", incoming,
            characterName = "Ada",
            namedKeysNewestFirst = shared,
        ))
        assertTrue(RpImportRules.greetingChanged(
            "Hello", "new", incoming,
            characterName = "Ada",
            namedKeysNewestFirst = shared,
        ))
        // Without the library, a keyless row is not treated as this character.
        assertFalse(RpImportRules.greetingChanged(
            "Hello", "ada", incoming, characterName = "Ada",
        ))
    }

    @Test
    fun loreNamesMatchWhenThePhoneTreatsCapitalIAsADifferentLetter() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale("tr", "TR"))
        try {
            val incoming = listOf(
                RpLorebookExport(name = "History"),
                RpLorebookExport(name = "history"),
                RpLorebookExport(name = "Notes"),
            )
            assertEquals(2, RpImportRules.loreFileCount(incoming))
            assertEquals(1, RpImportRules.loreOverwriteCount(incoming, listOf("HISTORY")))
            assertTrue(RpImportRules.sameLoreName("History", "history"))
            val exported = RpLoreBackup.exports(listOf(
                RpLorebook(name = "History", content = "old", updatedAt = 1),
                RpLorebook(name = "history", content = "new", updatedAt = 2),
            ))
            assertEquals(listOf("history"), exported.map { it.name })
            assertEquals("new", exported.single().content)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun oneCharacterKeepsTheUsualPictureCaps() {
        var calls = 0
        var portrait = 0
        var wallpaper = 0
        val exports = RpCharacterBackupFit.withinImportCap { portraitCap, wallpaperCap ->
            calls++
            portrait = portraitCap
            wallpaper = wallpaperCap
            listOf(RpCharacterExport(name = "Ada", exportKey = "ada", personality = "calm"))
        }
        assertEquals(1, calls)
        assertEquals(RpAvatarStorage.EXPORT_MAX_BYTES, portrait)
        assertEquals(RpWallpaperBackup.MAX_BYTES, wallpaper)
        assertEquals("Ada", exports.single().name)
    }

    @Test
    fun aLibraryOfLargePicturesStaysUnderTheImportCap() {
        val caps = ArrayList<Pair<Int, Int>>()
        val exports = RpCharacterBackupFit.withinImportCap { portraitCap, wallpaperCap ->
            caps += portraitCap to wallpaperCap
            listOf(
                RpCharacterExport(
                    name = "Torn",
                    exportKey = "torn",
                    avatarBase64 = null,
                    wallpaperBase64 = "",
                ),
            ) + List(6) { index ->
                RpCharacterExport(
                    name = "C$index",
                    exportKey = "k$index",
                    avatarBase64 = "A".repeat(portraitCap * 4 / 3),
                    wallpaperBase64 = "B".repeat(wallpaperCap * 4 / 3),
                )
            }
        }
        assertTrue(caps.size >= 2)
        assertTrue(caps.last().first < RpAvatarStorage.EXPORT_MAX_BYTES)
        assertTrue(caps.last().second < RpWallpaperBackup.MAX_BYTES)
        assertTrue(RpCharacterBackupFit.utf8Size(exports) <= ImportBounds.MAX_RP_BYTES)
        assertNull(exports.first().avatarBase64)
        assertEquals("", exports.first().wallpaperBase64)
        assertTrue(exports.drop(1).all { !it.avatarBase64.isNullOrBlank() && !it.wallpaperBase64.isNullOrBlank() })
    }

    @Test
    fun backupSizeCountsAnEmojiInTheNameAsFourBytes() {
        val plain = RpCharacterBackupFit.utf8Size(listOf(RpCharacterExport(name = "A", exportKey = "k")))
        val emoji = RpCharacterBackupFit.utf8Size(
            listOf(RpCharacterExport(name = "A\uD83D\uDE00", exportKey = "k")),
        )
        assertEquals(4L, emoji - plain)
    }
}
