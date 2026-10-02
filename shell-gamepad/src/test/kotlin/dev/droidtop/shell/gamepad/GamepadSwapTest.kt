package dev.droidtop.shell.gamepad

import androidx.compose.ui.input.key.Key
import dev.droidtop.library.controller.FaceLayout
import dev.droidtop.library.controller.GlyphFamily
import dev.droidtop.library.controller.LayoutSource
import dev.droidtop.shell.gamepad.input.ControllerLayouts
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the resolved face-button layout does to the key map and the hints
 * (see GamepadKeyMap and ControllerLayouts). The layout is a process-level
 * value, so each test sets it directly and puts it back.
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

    /** A swapped layout is what a Nintendo-style pad resolves to. */
    private fun setSwap(swapped: Boolean) = setLayout(
        if (swapped) FaceLayout.forFamily(GlyphFamily.NINTENDO, LayoutSource.FAMILY) else FaceLayout.DEFAULT,
    )

    private fun setLayout(layout: FaceLayout) = ControllerLayouts.useLayoutForTest(layout)

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
    fun `the swap turns the whole diamond and nothing else`() = withSwap(true) {
        // Android's key codes are positional, so a Nintendo-style pad's X is the top button: Y's key code.
        assertEquals(GamepadAction.Y, GamepadKeyMap.actionFor(Key.ButtonX))
        assertEquals(GamepadAction.X, GamepadKeyMap.actionFor(Key.ButtonY))
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
        // A Nintendo pad prints A on the button that confirms, and B on the one that cancels.
        assertEquals("A", GamepadKeyMap.labelFor(GamepadAction.A))
        assertEquals("B", GamepadKeyMap.labelFor(GamepadAction.B))
        assertEquals("X", GamepadKeyMap.labelFor(GamepadAction.X))
        assertEquals(android.view.KeyEvent.KEYCODE_BUTTON_Y, GamepadKeyMap.keyCodeFor(GamepadAction.X))
    }

    @Test
    fun `a PlayStation pad's hints name its own symbols`() {
        setLayout(FaceLayout.forFamily(GlyphFamily.PLAYSTATION, LayoutSource.FAMILY))
        try {
            assertEquals(GamepadAction.A, GamepadKeyMap.actionFor(Key.ButtonA))
            assertEquals("✕", GamepadKeyMap.labelFor(GamepadAction.A))
            assertEquals("○", GamepadKeyMap.labelFor(GamepadAction.B))
        } finally {
            setSwap(false)
        }
    }

    @Test
    fun `a handheld toggled to the Nintendo layout confirms on the right button and names what is printed there`() {
        // Retroid-style: Xbox print, the system reports the keys swapped and the right button confirms.
        setLayout(FaceLayout(GlyphFamily.XBOX, confirmOnRight = true, keysSwapped = true, source = LayoutSource.CONSOLE))
        try {
            // The system already calls the right button A, so there is nothing to swap in the key map.
            assertEquals(GamepadAction.A, GamepadKeyMap.actionFor(Key.ButtonA))
            assertEquals(GamepadAction.B, GamepadKeyMap.actionFor(Key.ButtonB))
            // The pill points at the right-hand button, whose plastic says B.
            assertEquals("B", GamepadKeyMap.labelFor(GamepadAction.A))
            assertEquals("A", GamepadKeyMap.labelFor(GamepadAction.B))
        } finally {
            setSwap(false)
        }
    }

    @Test
    fun `a captured answer makes the button the person calls A confirm`() {
        setLayout(FaceLayout.captured(confirmKeyCodeIsB = true))
        try {
            assertEquals(GamepadAction.A, GamepadKeyMap.actionFor(Key.ButtonB))
            assertEquals("A", GamepadKeyMap.labelFor(GamepadAction.A))
        } finally {
            setSwap(false)
        }
    }

    @Test
    fun `Page Up and Page Down are the keyboard's own L1 R1`() {
        // The top bar can never take real D-pad focus any more
        // (the removed Gaming navigation bar), so a keyboard-only session needs
        // a route to the shoulder buttons that switch Games/Apps/Settings
        // that isn't "click the tab" (owner, 2026-09-27).
        assertEquals(GamepadAction.L, GamepadKeyMap.actionFor(Key.PageUp))
        assertEquals(GamepadAction.R, GamepadKeyMap.actionFor(Key.PageDown))
    }

    @Test
    fun `the face-button swap never touches Page Up or Page Down`() = withSwap(true) {
        assertEquals(GamepadAction.L, GamepadKeyMap.actionFor(Key.PageUp))
        assertEquals(GamepadAction.R, GamepadKeyMap.actionFor(Key.PageDown))
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
