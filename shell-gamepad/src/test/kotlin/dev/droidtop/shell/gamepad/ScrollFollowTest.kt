package dev.droidtop.shell.gamepad

import dev.droidtop.shell.gamepad.pc.HomeColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A D-pad move keeps the selection on screen (docs/SPEC.md 6e; Droidtop/tracker#616 and the console report of
 * 2026-10-23: on Home and Plugins, the D-pad moved past the visible area without scrolling).
 */
class ScrollFollowTest {
    // A room from 0 to 1000, as the list's viewport less its padding.
    private fun delta(start: Int, size: Int) = inViewDelta(start, size, roomStart = 0, roomEnd = 1000)

    @Test
    fun `an item already on screen does not scroll`() {
        assertNull(delta(start = 100, size = 200))
        assertNull(delta(start = 0, size = 1000))
    }

    @Test
    fun `an item below the room scrolls forward until its end shows`() {
        assertEquals(300f, delta(start = 1100, size = 200))
        assertEquals(50f, delta(start = 850, size = 200))
    }

    @Test
    fun `an item above the room scrolls back until its start shows`() {
        assertEquals(-250f, delta(start = -250, size = 200))
    }

    @Test
    fun `an item longer than the room shows its start and stays put while the cursor moves inside it`() {
        // Home's first row with the strip under it was taller than the screen: it scrolled to its end, then back to
        // its start, on every left or right (#616).
        assertNull(delta(start = 0, size = 1400))
        assertEquals(200f, delta(start = 200, size = 1400))
        assertEquals(-300f, delta(start = -300, size = 1400))
    }

    @Test
    fun `Home's strip has its own place in the column, after the first shelf`() {
        assertEquals(0, HomeColumn.shelf(home = true, shelf = 0))
        assertEquals(1, HomeColumn.strip(shelfCount = 3))
        assertEquals(2, HomeColumn.shelf(home = true, shelf = 1))
        assertEquals(4, HomeColumn.destinations(home = true, shelfCount = 3))
    }

    @Test
    fun `an empty Home has the strip first, and Overview has no strip`() {
        assertEquals(0, HomeColumn.strip(shelfCount = 0))
        assertEquals(1, HomeColumn.destinations(home = true, shelfCount = 0))
        assertEquals(2, HomeColumn.shelf(home = false, shelf = 2))
        assertEquals(3, HomeColumn.destinations(home = false, shelfCount = 3))
    }
}
