package dev.droidtop.shell.gamepad

import androidx.compose.ui.focus.FocusDirection
import dev.droidtop.shell.gamepad.input.GamepadAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [gridPadTarget]'s own edge math (Droidtop/tracker#1, owner on the
 * console: "Up and Left in a game list are simply INERT... trace where
 * Up and Left go in the game-list path... write unit tests covering
 * previous-direction navigation from the middle of a long list and from
 * the first item"). A 4-column, 20-card grid (5 full rows) is the fixture
 * throughout: index 6 is a real middle position (row 1, column 2 -- not
 * against any edge in any direction), index 0 is the very first card.
 */
class GridPadTargetTest {
    private val cols = 4
    private val count = 20

    @Test
    fun `Up from the middle of a long list moves to the previous row, same column`() {
        // Row 1, column 2 (index 6) -- Up lands on row 0, column 2 (index 2).
        assertEquals(2, gridPadTarget(at = 6, count = count, cols = cols, direction = FocusDirection.Up))
    }

    @Test
    fun `Left from the middle of a long list moves to the previous card`() {
        assertEquals(5, gridPadTarget(at = 6, count = count, cols = cols, direction = FocusDirection.Left))
    }

    @Test
    fun `Up from the very first item does not move -- there is no row above it`() {
        assertNull(gridPadTarget(at = 0, count = count, cols = cols, direction = FocusDirection.Up))
    }

    @Test
    fun `Left from the very first item does not move -- there is no card before it`() {
        assertNull(gridPadTarget(at = 0, count = count, cols = cols, direction = FocusDirection.Left))
    }

    @Test
    fun `Up from the first row's last column still does not move`() {
        // Index 3: row 0, column 3 -- still the top row, Up still has nothing above it.
        assertNull(gridPadTarget(at = 3, count = count, cols = cols, direction = FocusDirection.Up))
    }

    @Test
    fun `Left from a row's first column does not move -- that is a real edge, not a bug`() {
        // Index 4: row 1, column 0.
        assertNull(gridPadTarget(at = 4, count = count, cols = cols, direction = FocusDirection.Left))
    }

    @Test
    fun `Down and Right are symmetric with Up and Left from the same middle position`() {
        assertEquals(10, gridPadTarget(at = 6, count = count, cols = cols, direction = FocusDirection.Down))
        assertEquals(7, gridPadTarget(at = 6, count = count, cols = cols, direction = FocusDirection.Right))
    }

    @Test
    fun `Right at a row's real last column does not move`() {
        // Index 7: row 1, column 3 -- the right edge of its row.
        assertNull(gridPadTarget(at = 7, count = count, cols = cols, direction = FocusDirection.Right))
    }

    @Test
    fun `an out-of-range focused index never moves`() {
        assertNull(gridPadTarget(at = -1, count = count, cols = cols, direction = FocusDirection.Up))
        assertNull(gridPadTarget(at = count, count = count, cols = cols, direction = FocusDirection.Left))
    }

    @Test
    fun `the four direction actions map to their focus directions and no other action does`() {
        assertEquals(FocusDirection.Up, gridDirection(GamepadAction.UP))
        assertEquals(FocusDirection.Down, gridDirection(GamepadAction.DOWN))
        assertEquals(FocusDirection.Left, gridDirection(GamepadAction.LEFT))
        assertEquals(FocusDirection.Right, gridDirection(GamepadAction.RIGHT))
        assertNull(gridDirection(GamepadAction.A))
        assertNull(gridDirection(GamepadAction.B))
        assertNull(gridDirection(GamepadAction.L))
    }
}
