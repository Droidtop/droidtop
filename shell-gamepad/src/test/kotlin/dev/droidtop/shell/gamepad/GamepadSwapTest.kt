package dev.droidtop.shell.gamepad

import androidx.compose.ui.input.key.Key
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The face-button question onboarding asks, and what answering it does
 * (see GamepadKeyMap). The swap is a process-level value, so each test
 * sets it through the same entry point the app uses and puts it back.
 */
class GamepadSwapTest {

    private fun withSwap(swapped: Boolean, body: () -> Unit) {
        setSwap(swapped)
        try {
            body()
        } finally {
            setSwap(false)
        }
    }

    /** GamepadKeyMap.load reads a preference; this is the same value, set directly. */
    private fun setSwap(swapped: Boolean) = GamepadKeyMap.useSwap(swapped)

    @Test
    fun `by default the bottom face button confirms`() {
        assertEquals(GamepadAction.A, GamepadKeyMap.actionFor(Key.ButtonA))
        assertEquals(GamepadAction.B, GamepadKeyMap.actionFor(Key.ButtonB))
    }

    @Test
    fun `swapped, the two face buttons trade meanings`() = withSwap(true) {
        assertEquals(GamepadAction.B, GamepadKeyMap.actionFor(Key.ButtonA))
        assertEquals(GamepadAction.A, GamepadKeyMap.actionFor(Key.ButtonB))
    }

    @Test
    fun `the swap reaches nothing else`() = withSwap(true) {
        assertEquals(GamepadAction.X, GamepadKeyMap.actionFor(Key.ButtonX))
        assertEquals(GamepadAction.Y, GamepadKeyMap.actionFor(Key.ButtonY))
        assertEquals(GamepadAction.UP, GamepadKeyMap.actionFor(Key.DirectionUp))
        assertEquals(GamepadAction.L, GamepadKeyMap.actionFor(Key.ButtonL1))
    }

    @Test
    fun `the system back key still means back`() = withSwap(true) {
        // Not a face button: a person who swapped A and B did not ask the
        // hardware back key to start confirming.
        assertEquals(GamepadAction.BACK, GamepadKeyMap.actionFor(Key.Back))
    }

    @Test
    fun `a touch affordance and a hint name the button that now confirms`() = withSwap(true) {
        assertEquals(
            android.view.KeyEvent.KEYCODE_BUTTON_B,
            GamepadKeyMap.keyCodeFor(GamepadAction.A),
        )
        assertEquals("B", GamepadKeyMap.labelFor(GamepadAction.A))
        assertEquals("A", GamepadKeyMap.labelFor(GamepadAction.B))
    }

    @Test
    fun `a press is named by where it is on the pad, never by what it means`() {
        assertEquals("the bottom face button", GamepadKeyMap.positionName(Key.ButtonA))
        withSwap(true) {
            // Unchanged by the swap: this answers "which button did you
            // press", which the swap has nothing to do with.
            assertEquals("the bottom face button", GamepadKeyMap.positionName(Key.ButtonA))
        }
        assertNotNull(GamepadKeyMap.positionName(Key.ButtonR1))
        assertNull(GamepadKeyMap.positionName(Key.A))
    }
}
