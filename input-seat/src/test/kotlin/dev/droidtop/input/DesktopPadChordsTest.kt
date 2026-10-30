package dev.droidtop.input

import android.view.KeyEvent
import dev.droidtop.mapping.ControllerLayout
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desktop's pad chords (docs/SPEC.md 6b "Pad chords", Droidtop/tracker#383), with the bundled layout, through
 * [DesktopInputRouter.onPadChord]. Expected values are evdev codes (KEY_LEFTCTRL = 29, KEY_C = 46, KEY_V = 47,
 * KEY_UP = 103), what the container receives.
 */
class DesktopPadChordsTest {
    private val layout = ControllerLayout.parse(File("src/main/res/raw/desktop_pad_chords.vdf").readText())

    private fun router(bridge: FakeHostBridge) = DesktopInputRouter().apply {
        seat = InputSeat(bridge)
        chords = layout
    }

    private fun DesktopInputRouter.down(code: Int, at: Long) = onPadChord(code, KeyEvent.ACTION_DOWN, 0, at)
    private fun DesktopInputRouter.up(code: Int, at: Long) = onPadChord(code, KeyEvent.ACTION_UP, 0, at)

    @Test
    fun `the bundled layout reads back as the same tree and names itself`() {
        assertEquals(layout.root, ControllerLayout.parse(layout.write()).root)
        assertEquals("Desktop chords", layout.localized(layout.title))
    }

    @Test
    fun `without L2 the face buttons and the D-pad are the container's keys, and Start is not`() {
        // KEY_ENTER = 28, KEY_ESC = 1, KEY_BACKSPACE = 14, KEY_TAB = 15, KEY_UP = 103.
        val bridge = FakeHostBridge()
        val router = router(bridge)
        for ((code, key) in listOf(
            KeyEvent.KEYCODE_BUTTON_A to 28,
            KeyEvent.KEYCODE_BUTTON_B to 1,
            KeyEvent.KEYCODE_BUTTON_X to 14,
            KeyEvent.KEYCODE_BUTTON_Y to 15,
            KeyEvent.KEYCODE_DPAD_UP to 103,
        )) {
            bridge.keys.clear()
            assertTrue(router.down(code, 0))
            assertTrue(router.up(code, 10))
            assertEquals(listOf(key to true, key to false), bridge.keys)
        }
        bridge.keys.clear()
        assertFalse(router.down(KeyEvent.KEYCODE_BUTTON_START, 30))
        assertTrue(bridge.keys.isEmpty())
    }

    @Test
    fun `L2 and A copy, L2 and B paste`() {
        val bridge = FakeHostBridge()
        val router = router(bridge)
        assertTrue(router.down(KeyEvent.KEYCODE_BUTTON_L2, 0))
        assertTrue(router.down(KeyEvent.KEYCODE_BUTTON_A, 10))
        assertTrue(router.up(KeyEvent.KEYCODE_BUTTON_A, 20))
        assertEquals(listOf(29 to true, 46 to true, 46 to false, 29 to false), bridge.keys)
        bridge.keys.clear()
        assertTrue(router.down(KeyEvent.KEYCODE_BUTTON_B, 30))
        assertTrue(router.up(KeyEvent.KEYCODE_BUTTON_B, 40))
        assertTrue(router.up(KeyEvent.KEYCODE_BUTTON_L2, 50))
        assertEquals(listOf(29 to true, 47 to true, 47 to false, 29 to false), bridge.keys)
    }

    @Test
    fun `L2 and the D-pad are the arrow keys`() {
        val bridge = FakeHostBridge()
        val router = router(bridge)
        router.down(KeyEvent.KEYCODE_BUTTON_L2, 0)
        assertTrue(router.down(KeyEvent.KEYCODE_DPAD_UP, 10))
        assertTrue(router.up(KeyEvent.KEYCODE_DPAD_UP, 20))
        assertEquals(listOf(103 to true, 103 to false), bridge.keys)
    }

    @Test
    fun `a chord's keys are let go when L2 is released first`() {
        val bridge = FakeHostBridge()
        val router = router(bridge)
        router.down(KeyEvent.KEYCODE_BUTTON_L2, 0)
        router.down(KeyEvent.KEYCODE_BUTTON_A, 10)
        assertTrue(router.up(KeyEvent.KEYCODE_BUTTON_L2, 20))
        // A's release still reaches the engine, which lets go of what A pressed.
        assertTrue(router.up(KeyEvent.KEYCODE_BUTTON_A, 30))
        assertEquals(listOf(29 to true, 46 to true, 46 to false, 29 to false), bridge.keys)
        // And with L2 up, A is Enter again.
        bridge.keys.clear()
        assertTrue(router.down(KeyEvent.KEYCODE_BUTTON_A, 40))
        assertEquals(listOf(28 to true), bridge.keys)
    }

    @Test
    fun `with no layout nothing is taken`() {
        val bridge = FakeHostBridge()
        val router = DesktopInputRouter().apply { seat = InputSeat(bridge) }
        assertFalse(router.down(KeyEvent.KEYCODE_BUTTON_L2, 0))
        assertFalse(router.down(KeyEvent.KEYCODE_BUTTON_A, 10))
        assertTrue(bridge.keys.isEmpty())
    }
}
