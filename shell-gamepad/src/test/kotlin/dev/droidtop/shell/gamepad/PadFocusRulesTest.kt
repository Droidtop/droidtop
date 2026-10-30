package dev.droidtop.shell.gamepad

import org.junit.Assert.assertEquals
import org.junit.Test

/** When the focus holder places the focus, and how often it tries (Droidtop/tracker#820). */
class PadFocusRulesTest {
    @Test
    fun `a screen gets its first frames to place its own focus, then the holder tries every few frames`() {
        val tries = (1..30).filter { PadFocusRules.tryOn(it) }
        assertEquals(listOf(6, 12, 18, 24, 30), tries)
    }
}
