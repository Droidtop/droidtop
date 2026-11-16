package dev.droidtop.shell.gamepad.pc

import org.junit.Assert.assertEquals
import org.junit.Test

/** A card title breaks between run-together words, never inside one (Droidtop/tracker#942). */
class CardTitleTest {
    private val zw = '\u200B'

    @Test
    fun runTogetherWordsGetABreakWhereEachStarts() {
        assertEquals("Heart${zw}Of${zw}The${zw}Machine", cardTitle("HeartOfTheMachine"))
        assertEquals("XML${zw}Parser", cardTitle("XMLParser"))
        assertEquals("Doom${zw}2", cardTitle("Doom2"))
        assertEquals("Stardew_${zw}Valley", cardTitle("Stardew_Valley"))
    }

    @Test
    fun ordinaryTitlesAreUnchanged() {
        assertEquals("Hollow Knight", cardTitle("Hollow Knight"))
        assertEquals("DOOM", cardTitle("DOOM"))
        assertEquals("a", cardTitle("a"))
        // Spaces already give the breaks; a dot or digit inside a spaced title adds none.
        assertEquals("Mr. Driller", cardTitle("Mr. Driller"))
        assertEquals("Vol.2 Remix", cardTitle("Vol.2 Remix"))
    }

    @Test
    fun aSingleUnbreakableWordUsesOneLine() {
        assertEquals(1, CardTitle.of("Supercalifragilistic").lines)
        assertEquals(2, CardTitle.of("HeartOfTheMachine").lines)
        assertEquals(2, CardTitle.of("Hollow Knight").lines)
    }
}
