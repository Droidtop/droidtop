package dev.droidtop.library.romdetect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomDetectUtilsTest {
    @Test
    fun `extractExtension lowercases the text after the last dot and is empty without one`() {
        assertEquals("zip", extractExtension("Game.ZIP"))
        assertEquals("gz", extractExtension("game.tar.gz"))
        assertEquals("", extractExtension("README"))
    }

    @Test
    fun `kiloBytes and megaBytes count in thousands`() {
        assertEquals(4_000, 4.kiloBytes())
        assertEquals(2_000_000, 2.megaBytes())
    }

    @Test
    fun `startsWithAny is true when the text starts with one of the prefixes`() {
        assertTrue("SLUS-00001".startsWithAny(listOf("SCUS", "SLUS")))
        assertFalse("BASLUS".startsWithAny(listOf("SLUS")))
        assertFalse("anything".startsWithAny(emptyList()))
    }

    @Test
    fun `indexOf finds the first place a byte pattern starts, or minus one`() {
        val data = byteArrayOf(1, 2, 3, 2, 3, 4)
        assertEquals(1, data.indexOf(byteArrayOf(2, 3)))
        assertEquals(4, data.indexOf(byteArrayOf(3, 4)))
        assertEquals(-1, data.indexOf(byteArrayOf(9)))
        assertEquals(0, data.indexOf(byteArrayOf()))
    }
}
