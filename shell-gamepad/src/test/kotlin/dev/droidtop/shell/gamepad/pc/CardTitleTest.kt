package dev.droidtop.shell.gamepad.pc

import org.junit.Assert.assertEquals
import org.junit.Test

/** A card title breaks between run-together words, never inside one (Droidtop/tracker#942). */
class CardTitleTest {
    private val zw = '​'

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
    }

    @Test
    fun aSingleUnbreakableWordUsesOneLine() {
        assertEquals(1, cardTitleLines("Supercalifragilistic"))
        assertEquals(2, cardTitleLines("HeartOfTheMachine"))
        assertEquals(2, cardTitleLines("Hollow Knight"))
    }
}
