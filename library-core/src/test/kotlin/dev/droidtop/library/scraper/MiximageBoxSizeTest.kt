package dev.droidtop.library.scraper

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The box's rotation and size in a miximage. Expected values are worked
 * by hand from MiximageGenerator.cpp:614-641 at ES-DE's default "medium"
 * box size and resolution multiplier 2: target height 600, 3D-box width
 * cap 620, cover width cap 500, rotation above 1.14:1.
 */
class MiximageBoxSizeTest {

    @Test
    fun `a portrait cover is scaled to the target height`() {
        // 600/1000 = 0.6; 700 * 0.6 = 420, under the 500 cap.
        assertEquals(
            MiximageGenerator.BoxSize(rotated = false, width = 420, height = 600),
            MiximageGenerator.boxSize(700, 1000, is3D = false, rotateHorizontalBoxes = true),
        )
    }

    @Test
    fun `a square cover is capped at the cover width, not the 3D box width`() {
        // 600 wide at full height is over 500, so it is scaled to 500x500.
        assertEquals(
            MiximageGenerator.BoxSize(rotated = false, width = 500, height = 500),
            MiximageGenerator.boxSize(1000, 1000, is3D = false, rotateHorizontalBoxes = true),
        )
        // A 3D box of the same shape is capped at 620, so 600 wide fits.
        assertEquals(
            MiximageGenerator.BoxSize(rotated = false, width = 600, height = 600),
            MiximageGenerator.boxSize(1000, 1000, is3D = true, rotateHorizontalBoxes = true),
        )
    }

    @Test
    fun `a horizontal box is turned on its side and then sized`() {
        // 1400x1000 is 1.4:1, over 1.14, so it becomes 1000x1400;
        // 600/1400 * 1000 = 428.57, truncated to 428.
        assertEquals(
            MiximageGenerator.BoxSize(rotated = true, width = 428, height = 600),
            MiximageGenerator.boxSize(1400, 1000, is3D = true, rotateHorizontalBoxes = true),
        )
    }

    @Test
    fun `the 1_14 threshold itself does not rotate`() {
        // 114/100 is not greater than 1.14.
        assertEquals(false, MiximageGenerator.boxSize(1140, 1000, is3D = true, rotateHorizontalBoxes = true).rotated)
        assertEquals(true, MiximageGenerator.boxSize(1150, 1000, is3D = true, rotateHorizontalBoxes = true).rotated)
    }

    @Test
    fun `with rotation off a horizontal box keeps its orientation and hits the width cap`() {
        // 600/1000 * 1400 = 840 > 620, so scale = 620/1400; 1000 * that = 442.857 -> 442.
        assertEquals(
            MiximageGenerator.BoxSize(rotated = false, width = 620, height = 442),
            MiximageGenerator.boxSize(1400, 1000, is3D = true, rotateHorizontalBoxes = false),
        )
    }
}
