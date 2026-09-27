package io.github.stardomains3.oxproxion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpLoreTest {

    private val book = """
        Greyhaven sits on a cliff.

        [keys: docks, pier]
        The docks flood at high tide.

        [keys: locket]
        The locket opens only for Mira.

        [keys: grey haven]
        Grey Haven was the old name of the city.
    """.trimIndent()

    @Test
    fun plainBookStaysWhole() {
        val text = "A city with no keyed entries."
        assertEquals(text, RpLore.select(text, ""))
    }

    @Test
    fun constantStaysWhenNothingMatches() {
        val out = RpLore.select(book, "They talk about the weather.")
        assertTrue(out.contains("Greyhaven sits on a cliff."))
        assertFalse(out.contains("flood"))
        assertFalse(out.contains("locket"))
    }

    @Test
    fun keyPullsItsBlock() {
        val out = RpLore.select(book, "Meet me at the docks.")
        assertTrue(out.contains("flood at high tide"))
        assertFalse(out.contains("locket"))
    }

    @Test
    fun shortKeyDoesNotMatchInsideALongerWord() {
        assertFalse(RpLore.keyHits("dock", "the docks"))
        assertTrue(RpLore.keyHits("docks", "the docks"))
        assertTrue(RpLore.keyHits("Mira", "Mira's locket"))
    }

    @Test
    fun phraseAndCase() {
        val out = RpLore.select(book, "People still say GREY HAVEN.")
        assertTrue(out.contains("old name"))
    }

    @Test
    fun entryCanPullAnother() {
        val linked = """
            [keys: map]
            The map marks the vault.

            [keys: vault]
            The vault key is under the pier.
        """.trimIndent()
        val out = RpLore.select(linked, "She unfolds the map.")
        assertTrue(out.contains("marks the vault"))
        assertTrue(out.contains("under the pier"))
    }

    @Test
    fun budgetDropsLaterEntries() {
        val fat = """
            Always.

            [keys: alpha]
            ${"A".repeat(50)}

            [keys: beta]
            ${"B".repeat(50)}
        """.trimIndent()
        val out = RpLore.select(fat, "alpha beta", maxChars = 60)
        assertTrue(out.contains("Always."))
        assertTrue(out.contains("A"))
        assertFalse(out.contains("B"))
    }

    @Test
    fun scanKeepsTheNewestPart() {
        val old = "docks ".repeat(2_000)
        val scan = RpLore.scanOf(listOf(old, "only the locket remains"))
        assertTrue(scan.endsWith("only the locket remains"))
        assertTrue(scan.length <= 4_000)
    }
}
