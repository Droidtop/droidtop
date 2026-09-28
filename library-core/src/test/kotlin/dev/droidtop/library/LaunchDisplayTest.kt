package dev.droidtop.library

import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [LaunchDisplay.clearRunning] (docs/SPEC.md, Droidtop/tracker#82): the
 * Quick Menu's Game tab and the dual-screen "don't cover a running game"
 * orchestration both key off [LaunchDisplay.parkedDisplayId] and
 * [LaunchDisplay.runningGame] staying in lock step -- proven here since
 * neither field needs an Android framework to touch (unlike
 * [LaunchDisplay.start]/`startOn`, which do).
 */
class LaunchDisplayTest {

    @After
    fun tearDown() {
        // These are @Volatile process-wide fields, not per-instance state
        // -- a test that sets them and doesn't clean up leaks into
        // whichever test runs next in the same process.
        LaunchDisplay.parkedDisplayId = null
        LaunchDisplay.runningGame = null
    }

    @Test
    fun `clearRunning nulls both the parked display and the running game together`() {
        LaunchDisplay.parkedDisplayId = 3
        LaunchDisplay.runningGame = LaunchContext(gameId = "game-1", systemId = "snes")

        LaunchDisplay.clearRunning()

        assertNull(LaunchDisplay.parkedDisplayId)
        assertNull(LaunchDisplay.runningGame)
    }

    @Test
    fun `clearRunning is safe to call when nothing is running`() {
        LaunchDisplay.clearRunning()

        assertNull(LaunchDisplay.parkedDisplayId)
        assertNull(LaunchDisplay.runningGame)
    }

    @Test
    fun `runningGame and parkedDisplayId are independent fields until cleared together`() {
        LaunchDisplay.parkedDisplayId = 1
        LaunchDisplay.runningGame = LaunchContext(gameId = "game-2", systemId = null)

        assertEquals(1, LaunchDisplay.parkedDisplayId)
        assertEquals("game-2", LaunchDisplay.runningGame?.gameId)
    }
}
