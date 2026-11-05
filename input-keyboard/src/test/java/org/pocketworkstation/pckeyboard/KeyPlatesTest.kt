package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hacker Keyboard Reborn's key plates (Droidtop/tracker#903 H2): which keys are function keys, and their colours. */
class KeyPlatesTest {
    private val colors = KeyboardColors(
        ground = 0xFF202124.toInt(), panel = 0xFF292A2D.toInt(), key = 0xFF3C4043.toInt(), keyFocused = 0xFF5F6368.toInt(),
        text = 0xFFFFFFFF.toInt(), muted = 0xFFAAACB0.toInt(), accent = 0xFF1A73E8.toInt(),
    )

    @Test fun lettersDigitsAndTheSpaceBarAreNotFunctionKeys() {
        assertFalse(KeyPlates.functional(intArrayOf('a'.code), "a", hasIcon = false, modifier = false, sticky = false))
        assertFalse(KeyPlates.functional(intArrayOf('1'.code, '!'.code), "1", hasIcon = false, modifier = false, sticky = false))
        // The space bar, even drawn with an icon.
        assertFalse(KeyPlates.functional(intArrayOf(' '.code), null, hasIcon = true, modifier = false, sticky = false))
    }

    @Test fun modifiersWordKeysAndIconKeysAreFunctionKeys() {
        // Ctrl, Alt: modifiers, sticky.
        assertTrue(KeyPlates.functional(intArrayOf(-113), "Ctrl", hasIcon = false, modifier = true, sticky = true))
        // Esc and Tab and F1: a word on a single-code key.
        assertTrue(KeyPlates.functional(intArrayOf(27), "Esc", hasIcon = false, modifier = false, sticky = false))
        assertTrue(KeyPlates.functional(intArrayOf(9), "Tab", hasIcon = false, modifier = false, sticky = false))
        assertTrue(KeyPlates.functional(intArrayOf(-131), "F1", hasIcon = false, modifier = false, sticky = false))
        // Delete, Enter, Shift: icon keys.
        assertTrue(KeyPlates.functional(intArrayOf(-5), null, hasIcon = true, modifier = false, sticky = false))
    }

    @Test fun pressedBeatsLatchedBeatsFunction() {
        assertEquals(colors.keyFocused, KeyPlates.plateColor(colors, functional = true, pressed = true, latched = true))
        assertEquals(KeyPlates.blend(colors.key, colors.accent, 0.45f), KeyPlates.plateColor(colors, true, pressed = false, latched = true))
        assertEquals(KeyPlates.blend(colors.key, colors.text, 0.12f), KeyPlates.plateColor(colors, true, pressed = false, latched = false))
        assertEquals(colors.key, KeyPlates.plateColor(colors, false, pressed = false, latched = false))
    }

    @Test fun blendMovesEachChannel() {
        assertEquals(0xFF000000.toInt(), KeyPlates.blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0f))
        assertEquals(0xFFFFFFFF.toInt(), KeyPlates.blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 1f))
        assertEquals(0xFF808080.toInt(), KeyPlates.blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
    }
}
