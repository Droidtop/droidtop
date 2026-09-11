package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** See [esDeTilePhaseOffset] for the ES-DE source of every rule here. */
class EsDeTilePhaseTest {

    @Test
    fun `the default alignment never shifts the pattern`() {
        assertEquals(0f, esDeTilePhaseOffset(250f, 64f, alignToFarEdge = false), 0f)
        assertEquals(0f, esDeTilePhaseOffset(256f, 64f, alignToFarEdge = false), 0f)
    }

    @Test
    fun `an exact fit is the same picture under either alignment`() {
        assertEquals(0f, esDeTilePhaseOffset(256f, 64f, alignToFarEdge = true), 0f)
    }

    @Test
    fun `the far edge moves the partial tile to the near one`() {
        // 250 = three whole 64px tiles plus a 58px strip; shifting back by
        // 64 - 58 puts that strip at the start instead of the end.
        assertEquals(-6f, esDeTilePhaseOffset(250f, 64f, alignToFarEdge = true), 0.001f)
        // 100 = three whole 32px tiles plus a 4px strip, so the shift is 4 - 32.
        assertEquals(-28f, esDeTilePhaseOffset(100f, 32f, alignToFarEdge = true), 0.001f)
    }

    @Test
    fun `a degenerate tile or box shifts nothing rather than dividing by zero`() {
        assertEquals(0f, esDeTilePhaseOffset(250f, 0f, alignToFarEdge = true), 0f)
        assertEquals(0f, esDeTilePhaseOffset(0f, 64f, alignToFarEdge = true), 0f)
    }
}
