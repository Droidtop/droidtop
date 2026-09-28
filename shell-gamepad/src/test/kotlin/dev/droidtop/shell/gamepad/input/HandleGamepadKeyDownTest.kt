package dev.droidtop.shell.gamepad.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared DOWN-only navigation contract (Droidtop/tracker#1, owner on
 * the console with a real gamepad, 2026-09-28/29): "a held button
 * repeats, that's fine. On release, it fires one more time" -- every
 * `onKeyEvent` in the shell used to act on KeyUp, so a held press
 * correctly stepped on every repeat KeyDown AND then stepped once more
 * on the terminal KeyUp. [handleGamepadKeyDown] is the one place that
 * contract lives now: act on DOWN (repeats included, since each is its
 * own DOWN), never on UP.
 */
class HandleGamepadKeyDownTest {

    @Test
    fun `one press -- a down then its matching up -- moves exactly one step`() {
        var steps = 0
        val down = handleGamepadKeyDown(isDown = true, isUp = false, owns = true) { steps++ }
        val up = handleGamepadKeyDown(isDown = false, isUp = true, owns = true) { steps++ }

        assertTrue("the down edge is consumed", down)
        assertTrue("the up edge is consumed too (so it does not leak elsewhere)", up)
        assertEquals("exactly one step for one press", 1, steps)
    }

    @Test
    fun `a held press -- several repeat downs -- steps once per repeat, nothing extra on release`() {
        var steps = 0
        // Android resends KeyDown with an increasing repeatCount for as
        // long as a hardware key stays held; each one is its own DOWN
        // call here, same as a synthetic hold-repeat (GamepadAxisNav).
        repeat(4) { handleGamepadKeyDown(isDown = true, isUp = false, owns = true) { steps++ } }
        val releaseHandled = handleGamepadKeyDown(isDown = false, isUp = true, owns = true) { steps++ }

        assertEquals("one step per repeat, four repeats", 4, steps)
        assertTrue("the release is still consumed so it does not leak", releaseHandled)
    }

    @Test
    fun `not owned -- never acts, never consumes, on either edge`() {
        var steps = 0
        val down = handleGamepadKeyDown(isDown = true, isUp = false, owns = false) { steps++ }
        val up = handleGamepadKeyDown(isDown = false, isUp = true, owns = false) { steps++ }

        assertFalse("an unowned down bubbles", down)
        assertFalse("an unowned up bubbles", up)
        assertEquals(0, steps)
    }

    @Test
    fun `neither edge -- some other KeyEventType -- is never consumed and never acts`() {
        var steps = 0
        val result = handleGamepadKeyDown(isDown = false, isUp = false, owns = true) { steps++ }

        assertFalse(result)
        assertEquals(0, steps)
    }
}
