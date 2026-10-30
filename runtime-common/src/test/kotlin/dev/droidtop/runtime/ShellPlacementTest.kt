package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One shell start per Home press (Droidtop/tracker#824): where Home starts the shell, and that the placement then moves nothing. */
class ShellPlacementTest {

    @Test
    fun `a fresh process starts the shell on the second screen it was last on, and the placement moves nothing`() {
        val on = ShellPlacement.launchDisplay(liveShell = null, remembered = 2, present = setOf(0, 2), pressedOn = 0)
        assertEquals(2, on)
        // The placement's pass with the shell already there: no second start.
        assertEquals(
            DualScreenOrchestration.ShellMove.NONE,
            DualScreenOrchestration.shellMove(
                shellDisplayId = on!!,
                secondDisplayId = 2,
                shellModeEligible = true,
                mainScreenWantsSecond = true,
                secondParked = false,
                relocationFailed = false,
            ),
        )
    }

    @Test
    fun `before this change the press display was used and the placement started the shell again`() {
        assertEquals(
            DualScreenOrchestration.ShellMove.TO_SECOND,
            DualScreenOrchestration.shellMove(0, 2, true, true, false, false),
        )
    }

    @Test
    fun `the shell running now wins over the remembered display`() {
        assertEquals(3, ShellPlacement.launchDisplay(liveShell = 3, remembered = 2, present = setOf(0, 2, 3), pressedOn = 0))
    }

    @Test
    fun `a display no longer connected or the pressed one needs no option`() {
        assertNull(ShellPlacement.launchDisplay(liveShell = null, remembered = 2, present = setOf(0), pressedOn = 0))
        assertNull(ShellPlacement.launchDisplay(liveShell = null, remembered = 0, present = setOf(0, 2), pressedOn = 0))
        assertNull(ShellPlacement.launchDisplay(liveShell = null, remembered = null, present = setOf(0, 2), pressedOn = 0))
    }
}
