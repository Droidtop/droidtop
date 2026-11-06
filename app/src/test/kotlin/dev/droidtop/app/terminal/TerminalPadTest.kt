package dev.droidtop.app.terminal

import dev.droidtop.app.terminal.TerminalPad.Press
import dev.droidtop.shell.gamepad.input.GamepadAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalPadTest {
    @Test
    fun `the D-pad is the arrow keys, A is Enter, X the keyboard and B leaves`() {
        assertEquals(Press.Key(19), TerminalPad.pressFor(GamepadAction.UP))
        assertEquals(Press.Key(20), TerminalPad.pressFor(GamepadAction.DOWN))
        assertEquals(Press.Key(21), TerminalPad.pressFor(GamepadAction.LEFT))
        assertEquals(Press.Key(22), TerminalPad.pressFor(GamepadAction.RIGHT))
        assertEquals(Press.Key(66), TerminalPad.pressFor(GamepadAction.A))
        assertEquals(Press.ToggleKeyboard, TerminalPad.pressFor(GamepadAction.X))
        assertEquals(Press.Leave, TerminalPad.pressFor(GamepadAction.B))
        assertEquals(Press.Leave, TerminalPad.pressFor(GamepadAction.BACK))
    }

    @Test
    fun `the other buttons do nothing on the terminal`() {
        listOf(GamepadAction.Y, GamepadAction.START, GamepadAction.SELECT, GamepadAction.L, GamepadAction.R, GamepadAction.L2, GamepadAction.R2)
            .forEach { assertNull(it.name, TerminalPad.pressFor(it)) }
    }

    @Test
    fun `a pad's keys are the pad's, a keyboard's are the terminal's`() {
        val keyboard = 0x00000101
        val gamepad = 0x00000401
        val joystick = 0x01000010
        val keyboardWithDpad = keyboard or 0x00000201
        // A keyboard's Enter, Esc and arrows are typed, even from a keyboard that also reports a D-pad.
        listOf(66, 111, 19, 20, 21, 22).forEach { assertFalse(TerminalPad.isPadKey(it, keyboard)) }
        listOf(66, 111, 19, 22).forEach { assertFalse(TerminalPad.isPadKey(it, keyboardWithDpad)) }
        // The pad's D-pad and buttons, from a gamepad or a joystick.
        listOf(19, 20, 21, 22, 23, 96, 97, 99, 100).forEach {
            assertTrue(TerminalPad.isPadKey(it, gamepad))
            assertTrue(TerminalPad.isPadKey(it, joystick))
        }
        // The hint bar's pills send pad buttons with no source.
        listOf(96, 97, 99, 108, 188, 203).forEach { assertTrue(TerminalPad.isPadKey(it, 0)) }
    }

    @Test
    fun `held arrows repeat, Enter and leaving do not`() {
        assertTrue(TerminalPad.repeats(Press.Key(19)))
        assertFalse(TerminalPad.repeats(Press.Key(66)))
        assertFalse(TerminalPad.repeats(Press.Leave))
        assertFalse(TerminalPad.repeats(Press.ToggleKeyboard))
    }

    @Test
    fun `leaving a running shell takes a second press soon after the first`() {
        assertFalse(TerminalPad.leaves(shellRunning = true, armedAtMs = null, nowMs = 10_000))
        assertTrue(TerminalPad.leaves(shellRunning = true, armedAtMs = 10_000, nowMs = 11_000))
        assertTrue(TerminalPad.leaves(shellRunning = true, armedAtMs = 10_000, nowMs = 10_000 + TerminalPad.LEAVE_CONFIRM_MS))
        assertFalse(TerminalPad.leaves(shellRunning = true, armedAtMs = 10_000, nowMs = 10_001 + TerminalPad.LEAVE_CONFIRM_MS))
        // An ended shell, or one that never opened, leaves at once.
        assertTrue(TerminalPad.leaves(shellRunning = false, armedAtMs = null, nowMs = 10_000))
    }
}
