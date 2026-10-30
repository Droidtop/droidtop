package dev.droidtop.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the companion's one slider row says its value is (Droidtop/tracker#786 S2a). */
class CompanionSliderTest {
    @Test
    fun `a value is a rounded percentage of its range`() {
        assertEquals("0%", percentOf(0f, 0f..15f))
        assertEquals("47%", percentOf(7f, 0f..15f))
        assertEquals("100%", percentOf(255f, 0f..255f))
        assertEquals("50%", percentOf(0.55f, 0.1f..1f))
    }

    @Test
    fun `a catalog slider says its number, and a percentage only on a 0 to 100 scale`() {
        assertEquals("5", catalogSliderLabel(5f, 2, 8))
        assertEquals("40%", catalogSliderLabel(40f, 0, 100))
        assertEquals("7", catalogSliderLabel(7f, 0, 15))
    }

    @Test
    fun `an empty range reads as nothing rather than failing`() {
        assertEquals("0%", percentOf(3f, 3f..3f))
    }
}
