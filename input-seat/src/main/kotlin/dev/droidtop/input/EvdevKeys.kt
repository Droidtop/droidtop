package dev.droidtop.input

import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Translates Android's input constants into the Linux evdev codes the seat
 * speaks, and nothing else — no policy about what should be forwarded lives
 * here, only what a given Android code *is* in evdev terms.
 *
 * There is deliberately no second layout table in this file. The layout —
 * which keysym a keycode produces, what Shift does to it, how modifiers
 * latch — is decided entirely by the XKB keymap :host-bridge hands the
 * compositor (`host-bridge/native/src/default_keymap.h`, a fixed us/pc105
 * map). This table only answers "which physical key did Android just
 * report", which is exactly the question the keymap's `xkb_keycodes`
 * section already answers in the other direction: its `<AC01> = 38` is
 * evdev `KEY_A = 30` plus XKB's fixed offset of 8. So every code below can
 * be checked against a name in that generated file rather than trusted.
 *
 * Consequence worth stating plainly: because the compositor applies a US
 * layout, a user typing on a physical AZERTY keyboard gets QWERTY output.
 * That is a keymap-selection gap (host-bridge's README already names it),
 * not something a bigger table here would fix — and adding a compensating
 * table here would be the second mapping mechanism this design exists to
 * avoid.
 */
object EvdevKeys {

    // linux/input-event-codes.h BTN_*
    const val BTN_LEFT = 0x110
    const val BTN_RIGHT = 0x111
    const val BTN_MIDDLE = 0x112
    const val BTN_SIDE = 0x113
    const val BTN_EXTRA = 0x114

    /**
     * The Android [KeyEvent] keycode -> evdev KEY_* table. Absent means
     * "droidtop does not forward this key", which is a real answer for
     * Back/Home/Recents/volume: those belong to Android and the shell
     * around the surface, and swallowing them would strand the user inside
     * a full-screen desktop with no way out.
     */
    private val KEYS: Map<Int, Int> = buildMap {
        put(KeyEvent.KEYCODE_ESCAPE, 1)
        // Digit row: evdev runs KEY_1..KEY_9 = 2..10 then KEY_0 = 11, so
        // Android's KEYCODE_0-first ordering cannot be a straight offset.
        put(KeyEvent.KEYCODE_1, 2)
        put(KeyEvent.KEYCODE_2, 3)
        put(KeyEvent.KEYCODE_3, 4)
        put(KeyEvent.KEYCODE_4, 5)
        put(KeyEvent.KEYCODE_5, 6)
        put(KeyEvent.KEYCODE_6, 7)
        put(KeyEvent.KEYCODE_7, 8)
        put(KeyEvent.KEYCODE_8, 9)
        put(KeyEvent.KEYCODE_9, 10)
        put(KeyEvent.KEYCODE_0, 11)
        put(KeyEvent.KEYCODE_MINUS, 12)
        put(KeyEvent.KEYCODE_EQUALS, 13)
        put(KeyEvent.KEYCODE_DEL, 14) // Android's DEL is Backspace; FORWARD_DEL is Delete
        put(KeyEvent.KEYCODE_TAB, 15)

        put(KeyEvent.KEYCODE_Q, 16)
        put(KeyEvent.KEYCODE_W, 17)
        put(KeyEvent.KEYCODE_E, 18)
        put(KeyEvent.KEYCODE_R, 19)
        put(KeyEvent.KEYCODE_T, 20)
        put(KeyEvent.KEYCODE_Y, 21)
        put(KeyEvent.KEYCODE_U, 22)
        put(KeyEvent.KEYCODE_I, 23)
        put(KeyEvent.KEYCODE_O, 24)
        put(KeyEvent.KEYCODE_P, 25)
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 26)
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, 27)
        put(KeyEvent.KEYCODE_ENTER, 28)
        put(KeyEvent.KEYCODE_CTRL_LEFT, 29)

        put(KeyEvent.KEYCODE_A, 30)
        put(KeyEvent.KEYCODE_S, 31)
        put(KeyEvent.KEYCODE_D, 32)
        put(KeyEvent.KEYCODE_F, 33)
        put(KeyEvent.KEYCODE_G, 34)
        put(KeyEvent.KEYCODE_H, 35)
        put(KeyEvent.KEYCODE_J, 36)
        put(KeyEvent.KEYCODE_K, 37)
        put(KeyEvent.KEYCODE_L, 38)
        put(KeyEvent.KEYCODE_SEMICOLON, 39)
        put(KeyEvent.KEYCODE_APOSTROPHE, 40)
        put(KeyEvent.KEYCODE_GRAVE, 41)
        put(KeyEvent.KEYCODE_SHIFT_LEFT, 42)
        put(KeyEvent.KEYCODE_BACKSLASH, 43)

        put(KeyEvent.KEYCODE_Z, 44)
        put(KeyEvent.KEYCODE_X, 45)
        put(KeyEvent.KEYCODE_C, 46)
        put(KeyEvent.KEYCODE_V, 47)
        put(KeyEvent.KEYCODE_B, 48)
        put(KeyEvent.KEYCODE_N, 49)
        put(KeyEvent.KEYCODE_M, 50)
        put(KeyEvent.KEYCODE_COMMA, 51)
        put(KeyEvent.KEYCODE_PERIOD, 52)
        put(KeyEvent.KEYCODE_SLASH, 53)
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, 54)
        put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 55)
        put(KeyEvent.KEYCODE_ALT_LEFT, 56)
        put(KeyEvent.KEYCODE_SPACE, 57)
        put(KeyEvent.KEYCODE_CAPS_LOCK, 58)

        put(KeyEvent.KEYCODE_F1, 59)
        put(KeyEvent.KEYCODE_F2, 60)
        put(KeyEvent.KEYCODE_F3, 61)
        put(KeyEvent.KEYCODE_F4, 62)
        put(KeyEvent.KEYCODE_F5, 63)
        put(KeyEvent.KEYCODE_F6, 64)
        put(KeyEvent.KEYCODE_F7, 65)
        put(KeyEvent.KEYCODE_F8, 66)
        put(KeyEvent.KEYCODE_F9, 67)
        put(KeyEvent.KEYCODE_F10, 68)
        put(KeyEvent.KEYCODE_NUM_LOCK, 69)
        put(KeyEvent.KEYCODE_SCROLL_LOCK, 70)

        put(KeyEvent.KEYCODE_NUMPAD_7, 71)
        put(KeyEvent.KEYCODE_NUMPAD_8, 72)
        put(KeyEvent.KEYCODE_NUMPAD_9, 73)
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 74)
        put(KeyEvent.KEYCODE_NUMPAD_4, 75)
        put(KeyEvent.KEYCODE_NUMPAD_5, 76)
        put(KeyEvent.KEYCODE_NUMPAD_6, 77)
        put(KeyEvent.KEYCODE_NUMPAD_ADD, 78)
        put(KeyEvent.KEYCODE_NUMPAD_1, 79)
        put(KeyEvent.KEYCODE_NUMPAD_2, 80)
        put(KeyEvent.KEYCODE_NUMPAD_3, 81)
        put(KeyEvent.KEYCODE_NUMPAD_0, 82)
        put(KeyEvent.KEYCODE_NUMPAD_DOT, 83)

        put(KeyEvent.KEYCODE_F11, 87)
        put(KeyEvent.KEYCODE_F12, 88)

        put(KeyEvent.KEYCODE_NUMPAD_ENTER, 96)
        put(KeyEvent.KEYCODE_CTRL_RIGHT, 97)
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 98)
        put(KeyEvent.KEYCODE_SYSRQ, 99)
        put(KeyEvent.KEYCODE_ALT_RIGHT, 100)

        put(KeyEvent.KEYCODE_MOVE_HOME, 102)
        put(KeyEvent.KEYCODE_DPAD_UP, 103)
        put(KeyEvent.KEYCODE_PAGE_UP, 104)
        put(KeyEvent.KEYCODE_DPAD_LEFT, 105)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, 106)
        put(KeyEvent.KEYCODE_MOVE_END, 107)
        put(KeyEvent.KEYCODE_DPAD_DOWN, 108)
        put(KeyEvent.KEYCODE_PAGE_DOWN, 109)
        put(KeyEvent.KEYCODE_INSERT, 110)
        put(KeyEvent.KEYCODE_FORWARD_DEL, 111)

        put(KeyEvent.KEYCODE_NUMPAD_EQUALS, 117)
        put(KeyEvent.KEYCODE_BREAK, 119)
        put(KeyEvent.KEYCODE_NUMPAD_COMMA, 121)

        put(KeyEvent.KEYCODE_META_LEFT, 125)
        put(KeyEvent.KEYCODE_META_RIGHT, 126)
        put(KeyEvent.KEYCODE_MENU, 127) // KEY_COMPOSE — the pc105 "menu" key
    }

    /** Null when droidtop deliberately does not forward [androidKeyCode]. */
    fun evdevKeyCode(androidKeyCode: Int): Int? = KEYS[androidKeyCode]

    /**
     * Splits a [MotionEvent.getButtonState] bitmask into evdev button
     * codes. BUTTON_BACK/BUTTON_FORWARD become BTN_SIDE/BTN_EXTRA rather
     * than BTN_BACK/BTN_FORWARD because that is the pair libinput actually
     * emits for a mouse's thumb buttons, and therefore the pair desktop
     * applications have back/forward bound to.
     */
    fun evdevButtons(androidButtonState: Int): List<Int> = buildList {
        if (androidButtonState and MotionEvent.BUTTON_PRIMARY != 0) add(BTN_LEFT)
        if (androidButtonState and MotionEvent.BUTTON_SECONDARY != 0) add(BTN_RIGHT)
        if (androidButtonState and MotionEvent.BUTTON_TERTIARY != 0) add(BTN_MIDDLE)
        if (androidButtonState and MotionEvent.BUTTON_BACK != 0) add(BTN_SIDE)
        if (androidButtonState and MotionEvent.BUTTON_FORWARD != 0) add(BTN_EXTRA)
    }
}
