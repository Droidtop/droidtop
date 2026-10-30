package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Updates page's version step, installed first (Droidtop/tracker#792, #639). */
class UpdateStepTest {
    @Test
    fun `the installed version comes first and the available one after the arrow`() {
        assertEquals("v0.37 → v0.38", GameUpdates.step("0.37", "0.38"))
        assertEquals("unknown → v2.1", GameUpdates.step(null, "2.1"))
        assertEquals("v1.0 → Final", GameUpdates.step("1.0", "Final"))
    }

    @Test
    fun `a build with no number says a newer build`() {
        assertEquals("v1.0 → a newer build", GameUpdates.step("1.0", GameUpdates.NEWER_BUILD))
    }
}
