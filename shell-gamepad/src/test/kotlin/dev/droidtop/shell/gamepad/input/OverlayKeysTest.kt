package dev.droidtop.shell.gamepad.input

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay stack owns the keys from the moment an overlay is pushed
 * (docs/SPEC.md 6e, Droidtop/tracker#359): the library behind a game menu
 * never sees the opening A's release or the first D-pad press.
 */
class OverlayKeysTest {
    private val a = KeyEvent.KEYCODE_BUTTON_A
    private val b = KeyEvent.KEYCODE_BUTTON_B
    private val dpadDown = KeyEvent.KEYCODE_DPAD_DOWN

    private val keys = OverlayKeys()
    private val menu = Any()
    private val sheet = Any()

    /** The library is layer 0 (the activity window); the menu pushed on top of it is layer 1. */
    private fun library(code: Int, down: Boolean, repeat: Int = 0) = keys.route(0, code, down, repeat)

    private fun menuLayer(code: Int, down: Boolean, repeat: Int = 0) =
        keys.route(keys.layerOf(menu), code, down, repeat)

    @Test
    fun `the library never sees the opening A release or the first D-pad press`() {
        assertTrue("A down reaches the library, which opens the menu", library(a, down = true))
        keys.push(menu)
        // Window focus is still on the activity: the release and the D-pad arrive there.
        assertFalse("A up must not reach the library", library(a, down = false))
        assertFalse("D-pad down must not reach the library", library(dpadDown, down = true))
        assertFalse(library(dpadDown, down = true, repeat = 1))
        assertFalse(library(dpadDown, down = false))
    }

    @Test
    fun `once the menu window has focus the D-pad reaches it and the opening A release does not`() {
        assertTrue(library(a, down = true))
        keys.push(menu)
        assertFalse("the release of a press the library took is not the menu's", menuLayer(a, down = false))
        assertTrue(menuLayer(dpadDown, down = true))
        assertTrue(menuLayer(dpadDown, down = true, repeat = 1))
        assertTrue(menuLayer(dpadDown, down = false))
        assertTrue("a fresh A belongs to the menu", menuLayer(a, down = true))
        assertTrue(menuLayer(a, down = false))
    }

    @Test
    fun `the closing B release does not reach the screen under the menu`() {
        keys.push(menu)
        assertTrue(menuLayer(b, down = true))
        keys.pop(menu)
        assertFalse("B up must not back out of the library too", library(b, down = false))
        assertTrue("the next fresh press is the library's again", library(b, down = true))
        assertTrue(library(b, down = false))
    }

    @Test
    fun `a press swallowed under an overlay stays swallowed when the overlay closes first`() {
        keys.push(menu)
        assertFalse(library(dpadDown, down = true))
        keys.pop(menu)
        assertFalse(library(dpadDown, down = false))
    }

    @Test
    fun `a window under two overlays is covered by both and a pop uncovers one layer`() {
        keys.push(menu)
        keys.push(sheet)
        assertFalse("the menu is covered by the sheet", menuLayer(dpadDown, down = true))
        assertTrue("the sheet is on top", keys.route(keys.layerOf(sheet), dpadDown, true, 0))
        keys.pop(sheet)
        assertTrue("the menu is on top again", menuLayer(a, down = true))
    }

    @Test
    fun `without overlays every press and release is delivered`() {
        assertTrue(library(dpadDown, down = true))
        assertTrue(library(dpadDown, down = true, repeat = 1))
        assertTrue(library(dpadDown, down = false))
        assertTrue(library(a, down = false))
    }
}
