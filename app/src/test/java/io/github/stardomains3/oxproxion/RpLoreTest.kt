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
    fun keysInUnspacedScriptsMatchInsideRunningText() {
        assertTrue(RpLore.keyHits("灰港", "他们在灰港的码头见面"))
        assertTrue(RpLore.keyHits("ミラ", "今日はミラのロケットを探す"))
        assertFalse(RpLore.keyHits("灰港", "他们在码头见面"))
        // Spaced scripts keep the word-boundary rule.
        assertTrue(RpLore.keyHits("Гавань", "Мы видели Гавань."))
        assertFalse(RpLore.keyHits("порт", "аэропорт"))
    }

    @Test
    fun phraseAndCase() {
        val out = RpLore.select(book, "People still say GREY HAVEN.")
        assertTrue(out.contains("old name"))
    }

    @Test
    fun semicolonAndIdeographicCommaAreKeySeparators() {
        val book = """
            [keys: docks; pier、lantern]
            The docks flood at high tide.
        """.trimIndent()
        assertTrue(RpLore.select(book, "She lights the lantern.").contains("flood"))
        assertFalse(RpLore.select(book, "She waits.").contains("flood"))
    }

    @Test
    fun barFullwidthCommaAndQuotesAreNotPartOfTheKey() {
        val book = """
            [keys: "locket" | grey haven，pier]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(book, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(book, "People still say grey haven.").contains("dawn"))
        assertFalse(RpLore.select(book, "She waits.").contains("dawn"))
    }

    @Test
    fun aLongConstantBlockStillLeavesRoomForAMatch() {
        val book = "C".repeat(20_000) + "\n\n[keys: locket]\nThe locket opens at dawn."
        val out = RpLore.select(book, "She holds the locket.", maxChars = 12_000)
        assertTrue(out.contains("opens at dawn"))
        assertTrue(out.contains("C"))
        assertTrue(out.length <= 12_000)
    }

    @Test
    fun aShortConstantStaysBesideALongMatch() {
        val book = "Greyhaven sits on a cliff.\n\n[keys: locket]\n" + "L".repeat(20_000)
        val out = RpLore.select(book, "the locket", maxChars = 1_000)
        assertTrue(out.startsWith("Greyhaven sits on a cliff."))
        assertTrue(out.contains("L"))
        assertTrue(out.length <= 1_000)
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
    fun aChainOfThreeStillArrives() {
        val linked = """
            [keys: map]
            The map marks the vault.

            [keys: vault]
            The vault hides the locket.

            [keys: locket]
            The locket opens only at dawn.
        """.trimIndent()
        val out = RpLore.select(linked, "She unfolds the map.")
        assertTrue(out.contains("marks the vault"))
        assertTrue(out.contains("hides the locket"))
        assertTrue(out.contains("only at dawn"))
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
        assertTrue(RpLore.keyHits("locket", scan))
    }

    @Test
    fun scanDoesNotStartMidWord() {
        val head = "x".repeat(30) + "locket"
        val scan = RpLore.scanOf(listOf(head, "docks at dawn"), maxChars = "cket\ndocks at dawn".length)
        assertFalse(scan.contains("cket"))
        assertFalse(RpLore.keyHits("locket", scan))
        assertTrue(RpLore.keyHits("docks", scan))
    }

    @Test
    fun sceneScanKeepsTheNameAndMemoryWhenTheChatIsLong() {
        val recent = listOf("x".repeat(6_000), "only the locket remains")
        val dropped = RpLore.scanOf(listOf("Mira", "The docks flood") + recent)
        assertFalse(RpLore.keyHits("Mira", dropped))
        assertFalse(RpLore.keyHits("docks", dropped))
        val scan = RpLore.sceneScan(listOf("Mira", "The docks flood", "{{user}} keeps the map"), recent)
        assertTrue(RpLore.keyHits("Mira", scan))
        assertTrue(RpLore.keyHits("docks", scan))
        assertTrue(RpLore.keyHits("locket", scan))
        assertTrue(scan.length <= 4_000)
    }

    @Test
    fun clipTailStopsOnALine() {
        val text = "Mira\n" + "word ".repeat(40)
        val out = RpLore.clipTail(text, 30)
        assertTrue(out.startsWith("Mira"))
        assertFalse(out.endsWith("wor"))
    }

    @Test
    fun aLongMemoryDoesNotHideFacts() {
        val scan = RpLore.sceneScan(
            pinned = listOf("Mira", "The docks flood"),
            recent = listOf("x".repeat(6_000)),
            notes = listOf("m".repeat(5_000), "The locket stays shut")
        )
        assertTrue(RpLore.keyHits("Mira", scan))
        assertTrue(RpLore.keyHits("docks", scan))
        assertTrue(RpLore.keyHits("locket", scan))
        assertTrue(scan.length <= 4_000)
    }

    @Test
    fun aShortFactDoesNotCutTheScenario() {
        val scenario = "The docks " + "s".repeat(900)
        val scan = RpLore.sceneScan(
            pinned = listOf("Mira", scenario),
            recent = listOf("x".repeat(6_000)),
            notes = listOf("The locket stays shut")
        )
        assertTrue(scan.contains("s".repeat(900)))
        assertTrue(RpLore.keyHits("locket", scan))
        assertTrue(scan.length <= 4_000)
    }

    @Test
    fun keysWrittenWithCardPlaceholdersMatchThePeople() {
        val book = """
            [keys: {{char}}, {{user}}'s locket]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(book, "Mira keeps it.", charName = "Mira", userName = "Alex").contains("dawn"))
        assertTrue(RpLore.select(book, "Alex's locket is shut.", charName = "Mira", userName = "Alex").contains("dawn"))
        assertFalse(RpLore.select(book, "Nobody mentioned them.", charName = "Mira", userName = "Alex").contains("dawn"))
        assertFalse(RpLore.select(book, "Mirage waits.", charName = "Mira", userName = "Alex").contains("dawn"))
    }

    @Test
    fun aPlaceholderInOneEntryPullsTheNext() {
        val book = """
            [keys: map]
            The map belongs to {{char}}.

            [keys: {{char}}]
            The locket is under the pier.
        """.trimIndent()
        val out = RpLore.select(book, "She unfolds the map.", charName = "Mira", userName = "Alex")
        assertTrue(out.contains("belongs to {{char}}"))
        assertTrue(out.contains("under the pier"))
    }

    @Test
    fun aBlankNameDoesNotEraseThePlaceholderKey() {
        val book = """
            [keys: {{user}}]
            The locket opens at dawn.
        """.trimIndent()
        assertFalse(RpLore.select(book, "Alex waits.", charName = "Mira", userName = "").contains("dawn"))
        assertTrue(RpLore.select(book, "{{user}} waits.", charName = "Mira", userName = "").contains("dawn"))
    }

    @Test
    fun aTrailingPeriodAndAFullwidthHeaderStillMatch() {
        val dotted = """
            [keys: locket.]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(dotted, "She holds the locket.").contains("dawn"))
        val wide = "［keys：locket］\nThe locket opens at dawn."
        assertTrue(RpLore.select(wide, "the locket").contains("dawn"))
    }

    @Test
    fun sceneScanKeepsTheReplyBeingRewritten() {
        val scan = RpLore.sceneScan(
            pinned = listOf("Mira"),
            recent = listOf("z".repeat(6_000)),
            focus = listOf("p".repeat(4_000), "She still has the locket.")
        )
        assertTrue(RpLore.keyHits("Mira", scan))
        assertTrue(RpLore.keyHits("locket", scan))
        assertFalse(scan.contains("p".repeat(4_000)))
        assertTrue(scan.length <= 4_000)
    }

    @Test
    fun cornerBracketsAndArabicTrailersAreNotPartOfTheKey() {
        val book = """
            [keys: 『locket』, docks؟]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(book, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(book, "Meet me at the docks.").contains("dawn"))
        assertFalse(RpLore.select(book, "She waits.").contains("dawn"))
    }

    @Test
    fun tibetanKeysMatchInsideRunningText() {
        assertTrue(RpLore.keyHits("བཀྲ", "དེ་ནི་བཀྲ་ཤིས་ཡིན།"))
        assertFalse(RpLore.keyHits("བཀྲ", "དེ་ནི་ཤིས་ཡིན།"))
    }

    @Test
    fun curlySingleQuotesAndMoreTrailersAreNotPartOfTheKey() {
        val book = """
            [keys: ‘locket’, docks．, pier‽]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(book, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(book, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(book, "The pier is quiet.").contains("dawn"))
        assertFalse(RpLore.select(book, "She waits.").contains("dawn"))
    }

    @Test
    fun angleQuotesAndMoreTrailersAreNotPartOfTheKey() {
        val book = """
            [keys: 《locket》, docks៕, pierฯ]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(book, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(book, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(book, "The pier is quiet.").contains("dawn"))
        assertFalse(RpLore.select(book, "She waits.").contains("dawn"))
    }

    @Test
    fun halfwidthPrimesAndMoreTrailersAreNotPartOfTheKey() {
        val book = """
            [keys: ｢locket｣, docks⸮, pier꓿]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(book, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(book, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(book, "The pier is quiet.").contains("dawn"))
        assertFalse(RpLore.select(book, "She waits.").contains("dawn"))
        val primes = """
            [keys: 〝locket〞, docks｡]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(primes, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(primes, "Meet me at the docks.").contains("dawn"))
    }

    @Test
    fun ornamentalVerticalQuotesAndMoreTrailersAreNotPartOfTheKey() {
        val book = """
            [keys: ❝locket❞, docks፧, pier߹]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(book, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(book, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(book, "The pier is quiet.").contains("dawn"))
        assertFalse(RpLore.select(book, "She waits.").contains("dawn"))
        val vertical = """
            [keys: ﹁locket﹂, docks︒, pier꛳]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(vertical, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(vertical, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(vertical, "The pier is quiet.").contains("dawn"))
        val tortoise = """
            [keys: 〔locket〕, docks﹗]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(tortoise, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(tortoise, "Meet me at the docks.").contains("dawn"))
    }

    @Test
    fun fullwidthHeavyAngleQuotesAndMoreTrailersAreNotPartOfTheKey() {
        val book = """
            [keys: ＂locket＂, docks︖, pier꩝]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(book, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(book, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(book, "The pier is quiet.").contains("dawn"))
        assertFalse(RpLore.select(book, "She waits.").contains("dawn"))
        val heavy = """
            [keys: ❮locket❯, docks᭞, pier᠅]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(heavy, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(heavy, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(heavy, "The pier is quiet.").contains("dawn"))
        val white = """
            [keys: ｟locket｠, docks᰻]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(white, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(white, "Meet me at the docks.").contains("dawn"))
    }

    @Test
    fun whiteTortoiseMathAngleQuotesAndMoreTrailersAreNotPartOfTheKey() {
        val book = """
            [keys: 〘locket〙, docks༎, pier꫰]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(book, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(book, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(book, "The pier is quiet.").contains("dawn"))
        assertFalse(RpLore.select(book, "She waits.").contains("dawn"))
        val math = """
            [keys: ⟨locket⟩, docks꣎, pier꧉]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(math, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(math, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(math, "The pier is quiet.").contains("dawn"))
        val ornament = """
            [keys: ❰locket❱, docks꡶, pier꥟]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(ornament, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(ornament, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(ornament, "The pier is quiet.").contains("dawn"))
    }


    @Test
    fun whiteSquareMathDoubleQuotesAndMoreTrailersAreNotPartOfTheKey() {
        val book = """
            [keys: 〚locket〛, docks᯼, pier᛫]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(book, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(book, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(book, "The pier is quiet.").contains("dawn"))
        assertFalse(RpLore.select(book, "She waits.").contains("dawn"))
        val math = """
            [keys: ⟪locket⟫, docks࡞, pier⵰]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(math, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(math, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(math, "The pier is quiet.").contains("dawn"))
        val tortoise = """
            [keys: ⟬locket⟭, docks࠾, pier꧈]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(tortoise, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(tortoise, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(tortoise, "The pier is quiet.").contains("dawn"))
    }

    @Test
    fun mathWhiteSquareWhiteCurlyQuotesAndMoreTrailersAreNotPartOfTheKey() {
        val book = """
            [keys: ⟦locket⟧, docks᳀, pier᪨]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(book, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(book, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(book, "The pier is quiet.").contains("dawn"))
        assertFalse(RpLore.select(book, "She waits.").contains("dawn"))
        val curly = """
            [keys: ⦃locket⦄, docks᳇, pier᪭]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(curly, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(curly, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(curly, "The pier is quiet.").contains("dawn"))
        val flat = """
            [keys: ❨locket❩, docks꤮, pier꤯]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(flat, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(flat, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(flat, "The pier is quiet.").contains("dawn"))
    }

    @Test
    fun mediumFlattenedMediumAngleQuotesAndMoreTrailersAreNotPartOfTheKey() {
        val book = """
            [keys: ❪locket❫, docks᜵, pier๚]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(book, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(book, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(book, "The pier is quiet.").contains("dawn"))
        assertFalse(RpLore.select(book, "She waits.").contains("dawn"))
        val angle = """
            [keys: ❬locket❭, docks៚, pier༑]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(angle, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(angle, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(angle, "The pier is quiet.").contains("dawn"))
        val tortoise = """
            [keys: ❲locket❳, docks⳺, pier᠉]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(tortoise, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(tortoise, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(tortoise, "The pier is quiet.").contains("dawn"))
    }

    @Test
    fun mediumCurlyWhiteParenQuotesAndMoreTrailersAreNotPartOfTheKey() {
        val book = """
            [keys: ❴locket❵, docks꯫, pier꛲]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(book, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(book, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(book, "The pier is quiet.").contains("dawn"))
        assertFalse(RpLore.select(book, "She waits.").contains("dawn"))
        val paren = """
            [keys: ⦅locket⦆, docks៖, pier᠀]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(paren, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(paren, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(paren, "The pier is quiet.").contains("dawn"))
        val tortoise = """
            [keys: ⦗locket⦘, docks༈, pier꧌]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(tortoise, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(tortoise, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(tortoise, "The pier is quiet.").contains("dawn"))
    }


    @Test
    fun zImageZBindingQuotesAndMoreTrailersAreNotPartOfTheKey() {
        val book = """
            [keys: ⦇locket⦈, docks꠨, pier༉]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(book, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(book, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(book, "The pier is quiet.").contains("dawn"))
        assertFalse(RpLore.select(book, "She waits.").contains("dawn"))
        val binding = """
            [keys: ⦉locket⦊, docks᠂, pier᭜]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(binding, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(binding, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(binding, "The pier is quiet.").contains("dawn"))
        val curled = """
            [keys: ⧼locket⧽, docks⸼, pier༴]
            The locket opens at dawn.
        """.trimIndent()
        assertTrue(RpLore.select(curled, "She holds the locket.").contains("dawn"))
        assertTrue(RpLore.select(curled, "Meet me at the docks.").contains("dawn"))
        assertTrue(RpLore.select(curled, "The pier is quiet.").contains("dawn"))
    }

}
