package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The naming and version logic ported from Pythia ([GameNaming]), against
 * the folder names it was ported for: the real corpus a rig listed out of
 * the user's own library on 2026-09-16
 * (`src/test/resources/adult-folder-names-2026-09-16.txt`, the same file
 * as `coordination/research/game-naming/`'s copy) plus the real nested
 * shapes that corpus' own folders have on disk.
 */
class GameNamingTest {

    private fun derive(path: String) = GameNaming.derive(path)

    // --- what one folder name says --------------------------------------

    @Test
    fun `a dotted or v-prefixed number is a version, and a bare one is part of the name`() {
        assertEquals("Eternum" to "0.9.5", derive("/g/Eternum-0.9.5-pc").let { it.name to it.version })
        assertEquals("AnotherChance" to "1.51", derive("/g/AnotherChance-v1.51-pc").let { it.name to it.version })
        assertEquals("love_of_magic_book1" to "1.3.3", derive("/g/love_of_magic_book1_v1_3_3_win64").let { it.name to it.version })
        // Pythia's own 2026-08-02 finding: a bare trailing number is a
        // title number far more often than a version, and reading it as a
        // version merges unrelated games.
        assertEquals("Far Cry 5" to "", derive("/g/Far Cry 5").let { it.name to it.version })
        assertEquals("Cyberpunk 2077" to "", derive("/g/Cyberpunk 2077").let { it.name to it.version })
        assertEquals("Anomalous_Coffee_Machine_2" to "1.2", derive("/g/Anomalous_Coffee_Machine_2_v1.2-deluxe_windows").let { it.name to it.version })
    }

    @Test
    fun `what is left after the name and version is a language or a mod`() {
        val derived = derive("/g/Harem_Hotel-v0.20_Pre-Alpha-pc")
        assertEquals("Harem_Hotel", derived.name)
        assertEquals("0.20", derived.version)
        assertEquals(listOf("Pre", "Alpha", "pc"), derived.mods)
        assertNull(derived.language)
        assertEquals("en", derive("/g/SomeVN-1.0-eng").language)
        assertEquals("ja", derive("/g/SomeVN-1.0-jpn").language)
        assertEquals("mtl", derive("/g/SomeVN-1.0-mtl").language)
    }

    // --- segments -------------------------------------------------------

    @Test
    fun `a folder that is only a part marker is a segment of the game above it`() {
        val week = derive("/g/adult/renpy/Fetish Locator/Week 2")
        assertEquals("Fetish Locator", week.name)
        assertEquals("Week 2", week.segment?.label)
        assertEquals(2, week.segment?.order)
        // ...and it is a segment, not a version: "2" is which part it is.
        assertEquals("", week.version)
    }

    @Test
    fun `a title that ends in a part marker is that part of the game the rest names`() {
        // The real Thief of Hearts folder set: two bare markers and one
        // folder that says which game it is in its own name.
        val third = derive("/g/adult/renpy/Thief of Hearts/ThiefofHeartsPart3-0.0.9-pc")
        assertEquals("ThiefofHearts", third.name)
        assertEquals("Part 3", third.segment?.label)
        assertEquals("0.0.9", third.version)
        assertTrue(GameNaming.sameGame(third.name, "Thief of Hearts"))
    }

    @Test
    fun `a game whose name merely starts with a sequence word keeps its name`() {
        assertEquals("Part Time Job", derive("/g/renpy/Part Time Job").name)
        assertNull(derive("/g/renpy/Part Time Job").segment)
        assertNull(derive("/g/renpy/Seasons").segment)
        // `book` is not one of the sequence words, so the Love of Magic
        // books stay three games rather than becoming three segments.
        assertNull(derive("/g/love_of_magic_book2_1_0_8b_win64").segment)
    }

    @Test
    fun `a bare version below a chapter folder is a version of the game, in that chapter`() {
        val derived = derive("/g/adult/renpy/BeingADik/Chap3+/10.0-sancho")
        assertEquals("BeingADik", derived.name)
        assertEquals("10.0", derived.version)
        assertEquals("Chap3+", derived.segment?.label)
        assertEquals(3, derived.segment?.order)
    }

    // --- one game, two spellings ----------------------------------------

    @Test
    fun `names that differ only in punctuation and case are one game`() {
        assertTrue(GameNaming.sameGame("GoodbyeEternity", "Goodbye Eternity"))
        assertTrue(GameNaming.sameGame("Anomalous_Coffee_Machine_2", "Anomalous Coffee Machine 2"))
        org.junit.Assert.assertFalse(GameNaming.sameGame("love_of_magic_book1", "love_of_magic_book2"))
        org.junit.Assert.assertFalse(GameNaming.sameGame("Lust Academy", "Lust Theory"))
    }

    // --- the similarity measure ------------------------------------------

    @Test
    fun `similarity is difflib's ratio, to the digit`() {
        // Checked against CPython's own difflib.SequenceMatcher(None, a,
        // b).ratio() -- the measure Pythia's 0.6 threshold was chosen
        // against, so a different measure would put the threshold
        // somewhere else.
        assertEquals(0.9474, GameNaming.similarity("love_of_magic_book1", "love_of_magic_book2"), 0.0001)
        assertEquals(0.7778, GameNaming.similarity("fruit of grisaia", "labyrinth of grisaia"), 0.0001)
        assertEquals(0.6087, GameNaming.similarity("lust academy", "lust theory"), 0.0001)
        assertEquals(0.6000, GameNaming.similarity("artemis", "rts"), 0.0001)
        assertEquals(0.9677, GameNaming.similarity("goodbye eternity", "goodbyeeternity"), 0.0001)
        assertEquals(1.0, GameNaming.similarity("same", "same"), 0.0)
        assertEquals(0.0, GameNaming.similarity("abc", "xyz"), 0.0)
    }
}
