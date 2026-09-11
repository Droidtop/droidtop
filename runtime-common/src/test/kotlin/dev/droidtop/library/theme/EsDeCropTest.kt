package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** See [esDeCropBias] for the real source. */
class EsDeCropTest {
    @Test
    fun `the real default of one half is the centred crop`() {
        assertEquals(0f, esDeCropBias(0.5f), 0f)
    }

    @Test
    fun `zero keeps the left or top edge and one keeps the right or bottom`() {
        assertEquals(-1f, esDeCropBias(0f), 0f)
        assertEquals(1f, esDeCropBias(1f), 0f)
        assertEquals(-0.5f, esDeCropBias(0.25f), 0f)
    }

    @Test
    fun `values outside the real range clamp rather than overshoot`() {
        assertEquals(-1f, esDeCropBias(-3f), 0f)
        assertEquals(1f, esDeCropBias(7f), 0f)
    }
}
