package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** See [EsDeHorizontalAlignment]'s own doc comment for the real source of every default here. */
class EsDeTextAlignmentTest {
    @Test
    fun `horizontal alignment defaults to left`() {
        assertEquals(EsDeHorizontalAlignment.LEFT, esDeHorizontalAlignment(null))
    }

    @Test
    fun `vertical alignment defaults to center, not top`() {
        assertEquals(EsDeVerticalAlignment.CENTER, esDeVerticalAlignment(null))
    }

    @Test
    fun `every accepted literal maps`() {
        assertEquals(EsDeHorizontalAlignment.LEFT, esDeHorizontalAlignment("left"))
        assertEquals(EsDeHorizontalAlignment.CENTER, esDeHorizontalAlignment("center"))
        assertEquals(EsDeHorizontalAlignment.RIGHT, esDeHorizontalAlignment("right"))
        assertEquals(EsDeVerticalAlignment.TOP, esDeVerticalAlignment("top"))
        assertEquals(EsDeVerticalAlignment.CENTER, esDeVerticalAlignment("center"))
        assertEquals(EsDeVerticalAlignment.BOTTOM, esDeVerticalAlignment("bottom"))
    }

    @Test
    fun `an unrecognized value falls back to the default, as ES-DE leaves the value in place`() {
        assertEquals(EsDeHorizontalAlignment.LEFT, esDeHorizontalAlignment("middle"))
        assertEquals(EsDeVerticalAlignment.CENTER, esDeVerticalAlignment("middle"))
    }
}
