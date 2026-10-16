package dev.droidtop.shell.gamepad

import dev.droidtop.shell.gamepad.input.GamepadAction
import org.junit.Assert.assertEquals
import org.junit.Test

/** A menu panel scrolls with the D-pad past its rows (Droidtop/tracker#381): an action's long result is reached without touch. */
class PanelScrollTest {
    @Test
    fun downAtTheLastRowScrollsWhenThereIsMoreBelow() {
        assertEquals(1, panelScrollDirection(GamepadAction.DOWN, atEdge = true, canForward = true, canBackward = false))
    }

    @Test
    fun upAtTheFirstRowScrollsBackWhenScrolled() {
        assertEquals(-1, panelScrollDirection(GamepadAction.UP, atEdge = true, canForward = false, canBackward = true))
    }

    @Test
    fun aStepAmongTheRowsNeverScrollsThePanel() {
        assertEquals(0, panelScrollDirection(GamepadAction.DOWN, atEdge = false, canForward = true, canBackward = true))
        assertEquals(0, panelScrollDirection(GamepadAction.UP, atEdge = false, canForward = true, canBackward = true))
    }

    @Test
    fun aPanelThatFitsKeepsItsBump() {
        assertEquals(0, panelScrollDirection(GamepadAction.DOWN, atEdge = true, canForward = false, canBackward = false))
        assertEquals(0, panelScrollDirection(GamepadAction.UP, atEdge = true, canForward = false, canBackward = false))
    }

    @Test
    fun otherButtonsNeverScroll() {
        assertEquals(0, panelScrollDirection(GamepadAction.A, atEdge = true, canForward = true, canBackward = true))
        assertEquals(0, panelScrollDirection(GamepadAction.LEFT, atEdge = true, canForward = true, canBackward = true))
    }
}
