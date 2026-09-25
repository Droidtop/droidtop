package dev.droidtop.shell.gamepad.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [shortestCamOffsetStep] is real ES-DE's wraparound "shortest path"
 * logic (`CarouselComponent::onCursorChanged`'s `posMax` handling):
 * stepping off either end of a looping carousel should animate one step
 * further in the direction of travel, not dart back across the whole
 * list to the wrapped index (owner, on the RP5 console, 2026-09-25: "it
 * cycles back to the beginning, but by darting back, not continuing to
 * go forward").
 */
class ShortestCamOffsetStepTest {

    @Test
    fun `an ordinary forward step in the middle of the list is plus one`() {
        assertEquals(4f, shortestCamOffsetStep(from = 3f, toIndex = 4, entryCount = 10), 0f)
    }

    @Test
    fun `an ordinary backward step in the middle of the list is minus one`() {
        assertEquals(2f, shortestCamOffsetStep(from = 3f, toIndex = 2, entryCount = 10), 0f)
    }

    @Test
    fun `stepping forward off the last item continues forward past entryCount`() {
        // 10 items, cursor was on index 9, steps forward and wraps to
        // index 0: the old code would have animated 9f down to 0f (a
        // 9-item dart backward). The real step is +1, into 10, which the
        // render layer reduces to index 0 by modulo.
        assertEquals(10f, shortestCamOffsetStep(from = 9f, toIndex = 0, entryCount = 10), 0f)
    }

    @Test
    fun `stepping backward off the first item continues backward past zero`() {
        assertEquals(-1f, shortestCamOffsetStep(from = 0f, toIndex = 9, entryCount = 10), 0f)
    }

    @Test
    fun `camOffset keeps growing across repeated forward wraps`() {
        // Three full laps forward, one step at a time, on a 4-item list.
        var pos = 0f
        var index = 0
        repeat(12) {
            index = (index + 1) % 4
            pos = shortestCamOffsetStep(from = pos, toIndex = index, entryCount = 4)
        }
        assertEquals(12f, pos, 0f)
    }

    @Test
    fun `camOffset keeps shrinking across repeated backward wraps`() {
        var pos = 0f
        var index = 0
        repeat(12) {
            index = (index - 1 + 4) % 4
            pos = shortestCamOffsetStep(from = pos, toIndex = index, entryCount = 4)
        }
        assertEquals(-12f, pos, 0f)
    }

    @Test
    fun `a single item never moves`() {
        assertEquals(5f, shortestCamOffsetStep(from = 5f, toIndex = 0, entryCount = 1), 0f)
    }

    @Test
    fun `an empty list never moves`() {
        assertEquals(2f, shortestCamOffsetStep(from = 2f, toIndex = 0, entryCount = 0), 0f)
    }

    @Test
    fun `two items step by exactly one, either direction`() {
        // Both directions reach the same on-screen index (0 and 2 are
        // both "index 0" modulo 2); either is a correct shortest step,
        // and this fixes the tie-break so it stays deterministic.
        assertEquals(1f, shortestCamOffsetStep(from = 0f, toIndex = 1, entryCount = 2), 0f)
        assertEquals(2f, shortestCamOffsetStep(from = 1f, toIndex = 0, entryCount = 2), 0f)
    }
}
