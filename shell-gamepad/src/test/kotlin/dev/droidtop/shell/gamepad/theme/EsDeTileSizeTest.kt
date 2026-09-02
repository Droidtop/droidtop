package dev.droidtop.shell.gamepad.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Checks for the real ES-DE `tile`/`tileSize` rules. Expected values are
 * derived by hand from `ImageComponent::applyTheme()` (lines 653-673) and
 * `ImageComponent::resize()` (lines 838-850) in real ES-DE's own source,
 * cited per test.
 */
class EsDeTileSizeTest {

    /** ImageComponent.cpp:838-842 -- no tileSize means the texture's own pixel size. */
    @Test
    fun `no declared tile size uses the source's intrinsic size`() {
        val tile = esDeTileSize(null, null, 32f, 16f)
        assertEquals(32f, tile!!.first, 0.001f)
        assertEquals(16f, tile.second, 0.001f)
    }

    /** ImageComponent.cpp:657-663 -- an explicit 0 0 turns tiling off entirely. */
    @Test
    fun `a zero-by-zero tile size disables tiling`() {
        assertNull(esDeTileSize(0f, 0f, 32f, 16f))
    }

    /** ImageComponent.cpp:845-848 -- a zero WIDTH is derived from the height via the source aspect ratio. */
    @Test
    fun `zero width is derived from the height`() {
        val tile = esDeTileSize(0f, 40f, 32f, 16f)
        // ratio 32/16 = 2 -> round(40 * 2) = 80.
        assertEquals(80f, tile!!.first, 0.001f)
        assertEquals(40f, tile.second, 0.001f)
    }

    /** ImageComponent.cpp:849-850 -- and a zero HEIGHT from the width. */
    @Test
    fun `zero height is derived from the width`() {
        val tile = esDeTileSize(80f, 0f, 32f, 16f)
        assertEquals(80f, tile!!.first, 0.001f)
        assertEquals(40f, tile.second, 0.001f)
    }

    /** Both components declared: used verbatim, no aspect correction. */
    @Test
    fun `both components declared are used as given`() {
        val tile = esDeTileSize(50f, 90f, 32f, 16f)
        assertEquals(50f, tile!!.first, 0.001f)
        assertEquals(90f, tile.second, 0.001f)
    }

    /** An undecodable or degenerate source can't be tiled at all. */
    @Test
    fun `a degenerate source size is rejected`() {
        assertNull(esDeTileSize(10f, 10f, 0f, 0f))
    }
}
