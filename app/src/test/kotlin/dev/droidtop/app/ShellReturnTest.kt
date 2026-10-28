package dev.droidtop.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the Gaming shell's resume is a return to a running game (ShellReturn, Droidtop/tracker#86). */
class ShellReturnTest {
    @Test
    fun `back from a running game is a return`() {
        assertTrue(ShellReturn.returnsToGame(stoppedAtMs = 1_000L, gameRunning = true, otherScreenAtMs = 0L))
    }

    @Test
    fun `no return when the shell did not stop, the game ended, or another droidtop screen came up since`() {
        assertFalse(ShellReturn.returnsToGame(stoppedAtMs = null, gameRunning = true, otherScreenAtMs = 0L))
        assertFalse(ShellReturn.returnsToGame(stoppedAtMs = 1_000L, gameRunning = false, otherScreenAtMs = 0L))
        assertFalse(ShellReturn.returnsToGame(stoppedAtMs = 1_000L, gameRunning = true, otherScreenAtMs = 1_500L))
    }

    @Test
    fun `a droidtop screen seen before the shell stopped does not count`() {
        assertTrue(ShellReturn.returnsToGame(stoppedAtMs = 2_000L, gameRunning = true, otherScreenAtMs = 1_500L))
    }
}
