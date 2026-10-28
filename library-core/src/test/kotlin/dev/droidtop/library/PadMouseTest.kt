package dev.droidtop.library

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The controller as a mouse in a PC game (docs/SPEC.md 5b, "Controller mouse mode"; Droidtop/tracker#565). */
class PadMouseTest {
    @Test
    fun `a stick at rest or inside the deadzone does not move the pointer`() {
        assertEquals(0f to 0f, PadMouse.velocity(0f, 0f, 1920))
        assertEquals(0f to 0f, PadMouse.velocity(0.1f, -0.1f, 1920))
    }

    @Test
    fun `speed rises with the square of the push, full speed crossing the screen in two thirds of a second`() {
        val (full, _) = PadMouse.velocity(1f, 0f, 1920)
        assertEquals(1920 * PadMouse.FULL_SPEED_WIDTHS_PER_S, full, 0.5f)
        val (half, _) = PadMouse.velocity(0.575f, 0f, 1920)
        // Halfway between the deadzone and full: a quarter of full speed.
        assertEquals(full / 4, half, 1f)
        val (_, down) = PadMouse.velocity(0f, 1f, 1920)
        assertTrue(down > 0)
    }

    @Test
    fun `the clicks are the shoulders or the face buttons`() {
        assertEquals(PadMouse.Button.LEFT, PadMouse.buttonFor(KeyEvent.KEYCODE_BUTTON_R1, PadMouse.Clicks.SHOULDERS))
        assertEquals(PadMouse.Button.RIGHT, PadMouse.buttonFor(KeyEvent.KEYCODE_BUTTON_L1, PadMouse.Clicks.SHOULDERS))
        assertNull(PadMouse.buttonFor(KeyEvent.KEYCODE_BUTTON_A, PadMouse.Clicks.SHOULDERS))
        assertEquals(PadMouse.Button.LEFT, PadMouse.buttonFor(KeyEvent.KEYCODE_BUTTON_A, PadMouse.Clicks.FACE))
        assertEquals(PadMouse.Button.RIGHT, PadMouse.buttonFor(KeyEvent.KEYCODE_BUTTON_B, PadMouse.Clicks.FACE))
    }

    @Test
    fun `the moving stick's click is the scroll key and its vertical axis the scroll rate`() {
        assertEquals(KeyEvent.KEYCODE_BUTTON_THUMBR, PadMouse.scrollKey(PadMouse.Stick.RIGHT))
        assertEquals(KeyEvent.KEYCODE_BUTTON_THUMBL, PadMouse.scrollKey(PadMouse.Stick.LEFT))
        assertEquals(0f, PadMouse.scrollRate(0.1f), 0f)
        assertEquals(PadMouse.SCROLL_STEPS_PER_S, PadMouse.scrollRate(1f), 0.01f)
        assertEquals(-PadMouse.SCROLL_STEPS_PER_S, PadMouse.scrollRate(-1f), 0.01f)
    }

    @Test
    fun `both stick clicks held long enough toggle once per hold`() {
        val combo = PadMouse.Combo()
        combo.press(KeyEvent.KEYCODE_BUTTON_THUMBL, 0)
        assertFalse(combo.due(1000))
        combo.press(KeyEvent.KEYCODE_BUTTON_THUMBR, 100)
        assertFalse(combo.due(500))
        assertTrue(combo.due(700))
        assertFalse(combo.due(2000))
        combo.release(KeyEvent.KEYCODE_BUTTON_THUMBR)
        combo.press(KeyEvent.KEYCODE_BUTTON_THUMBR, 3000)
        assertTrue(combo.due(3600))
    }

    @Test
    fun `settings round-trip, and a game with none starts off unless its runner needs a mouse`() {
        val settings = PadMouse.Settings(on = true, stick = PadMouse.Stick.LEFT, clicks = PadMouse.Clicks.FACE)
        assertEquals(settings, PadMouse.decode(PadMouse.encode(settings)))
        assertEquals(PadMouse.Settings(), PadMouse.decode("garbage"))
        assertFalse(PadMouse.initial(null, runnerNeedsMouse = false).on)
        assertTrue(PadMouse.initial(null, runnerNeedsMouse = true).on)
        assertFalse(PadMouse.initial(PadMouse.encode(PadMouse.Settings(on = false)), runnerNeedsMouse = true).on)
    }

    @Test
    fun `a pointer genre starts mouse mode on, and a word inside another does not count`() {
        assertTrue(PadMouse.genreNeedsMouse("Adventure, Point-and-click"))
        assertTrue(PadMouse.genreNeedsMouse("Adventure / Point and Click"))
        assertTrue(PadMouse.genreNeedsMouse("Real Time Strategy (RTS)"))
        assertTrue(PadMouse.genreNeedsMouse("Visual Novel"))
        assertTrue(PadMouse.genreNeedsMouse("Card & Board Game"))
        assertFalse(PadMouse.genreNeedsMouse("Sports"))
        assertFalse(PadMouse.genreNeedsMouse("Platform, Action"))
        assertFalse(PadMouse.genreNeedsMouse(null))
    }
}
