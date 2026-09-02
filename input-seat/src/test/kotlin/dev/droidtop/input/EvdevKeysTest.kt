package dev.droidtop.input

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The expected values here are the evdev codes named in
 * `host-bridge/native/src/default_keymap.h`'s own `xkb_keycodes` section,
 * minus XKB's fixed offset of 8 — `<AC01> = 38` there is `KEY_A = 30` here.
 * Checking against the keymap that the compositor is actually handed is the
 * whole point: a table that agrees only with itself would pass while the
 * container received a different letter than the user pressed.
 */
class EvdevKeysTest {

    @Test
    fun `letters match the keymap's home and top rows`() {
        assertEquals(30, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_A)) // <AC01> = 38
        assertEquals(16, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_Q)) // <AD01> = 24
        assertEquals(44, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_Z)) // <AB01> = 52
        assertEquals(25, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_P)) // <AD10> = 33
        assertEquals(50, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_M)) // <AB07> = 58
    }

    @Test
    fun `the digit row is not a straight offset from Android's ordering`() {
        // Android counts KEYCODE_0 first; evdev puts KEY_0 last, after
        // KEY_9. Getting this wrong shifts every digit by one and is the
        // single easiest mistake in the whole table.
        assertEquals(11, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_0)) // <AE10> = 19
        assertEquals(2, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_1)) // <AE01> = 10
        assertEquals(10, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_9)) // <AE09> = 18
    }

    @Test
    fun `Android's DEL is Backspace and FORWARD_DEL is Delete`() {
        assertEquals(14, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_DEL)) // <BKSP> = 22
        assertEquals(111, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_FORWARD_DEL)) // <DELE> = 119
    }

    @Test
    fun `modifiers keep their left and right identity`() {
        assertEquals(29, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_CTRL_LEFT)) // <LCTL> = 37
        assertEquals(97, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_CTRL_RIGHT)) // <RCTL> = 105
        assertEquals(42, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_SHIFT_LEFT)) // <LFSH> = 50
        assertEquals(54, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_SHIFT_RIGHT)) // <RTSH> = 62
        assertEquals(56, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_ALT_LEFT)) // <LALT> = 64
        assertEquals(100, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_ALT_RIGHT)) // <RALT> = 108
        assertEquals(125, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_META_LEFT)) // <LWIN> = 133
    }

    @Test
    fun `the function row runs F1-F10 then jumps for F11 and F12`() {
        assertEquals(59, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_F1)) // <FK01> = 67
        assertEquals(68, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_F10)) // <FK10> = 76
        assertEquals(87, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_F11)) // <FK11> = 95
        assertEquals(88, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_F12)) // <FK12> = 96
    }

    @Test
    fun `arrows and navigation cluster`() {
        assertEquals(103, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_DPAD_UP)) // <UP> = 111
        assertEquals(108, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)) // <DOWN> = 116
        assertEquals(105, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_DPAD_LEFT)) // <LEFT> = 113
        assertEquals(106, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)) // <RGHT> = 114
        assertEquals(102, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_MOVE_HOME)) // <HOME> = 110
        assertEquals(107, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_MOVE_END)) // <END> = 115
        assertEquals(104, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_PAGE_UP)) // <PGUP> = 112
        assertEquals(109, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_PAGE_DOWN)) // <PGDN> = 117
    }

    @Test
    fun `numeric keypad is distinct from the digit row`() {
        assertEquals(82, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_NUMPAD_0)) // <KP0> = 90
        assertEquals(79, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_NUMPAD_1)) // <KP1> = 87
        assertEquals(71, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_NUMPAD_7)) // <KP7> = 79
        assertEquals(96, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_NUMPAD_ENTER)) // <KPEN> = 104
        assertEquals(98, EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_NUMPAD_DIVIDE)) // <KPDV> = 106
    }

    @Test
    fun `keys Android owns are deliberately not forwarded`() {
        // Swallowing these would strand the user inside a full-screen
        // desktop surface with no way back out to the shell.
        assertNull(EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_BACK))
        assertNull(EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_HOME))
        assertNull(EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_APP_SWITCH))
        assertNull(EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertNull(EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_POWER))
    }

    @Test
    fun `gamepad face buttons stay with the shell`() {
        // The shell's Compose focus navigation runs on these; the router
        // only ever claims the two stick clicks.
        assertNull(EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_BUTTON_A))
        assertNull(EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_BUTTON_B))
        assertNull(EvdevKeys.evdevKeyCode(KeyEvent.KEYCODE_DPAD_CENTER))
    }

    @Test
    fun `mouse buttons split out of the bitmask`() {
        assertEquals(listOf(EvdevKeys.BTN_LEFT), EvdevKeys.evdevButtons(MotionEvent.BUTTON_PRIMARY))
        assertEquals(listOf(EvdevKeys.BTN_RIGHT), EvdevKeys.evdevButtons(MotionEvent.BUTTON_SECONDARY))
        assertEquals(
            listOf(EvdevKeys.BTN_LEFT, EvdevKeys.BTN_MIDDLE),
            EvdevKeys.evdevButtons(MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_TERTIARY),
        )
        assertEquals(emptyList<Int>(), EvdevKeys.evdevButtons(0))
    }

    @Test
    fun `thumb buttons use the codes libinput emits for them`() {
        assertEquals(listOf(EvdevKeys.BTN_SIDE), EvdevKeys.evdevButtons(MotionEvent.BUTTON_BACK))
        assertEquals(listOf(EvdevKeys.BTN_EXTRA), EvdevKeys.evdevButtons(MotionEvent.BUTTON_FORWARD))
    }
}
