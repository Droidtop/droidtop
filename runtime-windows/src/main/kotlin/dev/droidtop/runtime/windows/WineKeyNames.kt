package dev.droidtop.runtime.windows

import com.winlator.xserver.XKeycode

/**
 * Steam's key names in a layout's `key_press` bindings (Droidtop/tracker#618) as the X server's key codes. Pure.
 * Letters and digits are themselves; the rest are Steam's words, with the common spellings of each accepted, since the
 * exact spellings in Valve-written files are still to be checked against real ones (C17). A name with no X key here
 * is null, and that binding does nothing.
 */
internal object WineKeyNames {
    private val NAMED: Map<String, XKeycode> = mapOf(
        "SPACE" to XKeycode.KEY_SPACE,
        "RETURN" to XKeycode.KEY_ENTER, "ENTER" to XKeycode.KEY_ENTER,
        "ESCAPE" to XKeycode.KEY_ESC, "ESC" to XKeycode.KEY_ESC,
        "TAB" to XKeycode.KEY_TAB,
        "BACKSPACE" to XKeycode.KEY_BKSP,
        "LEFT_SHIFT" to XKeycode.KEY_SHIFT_L, "SHIFT" to XKeycode.KEY_SHIFT_L, "RIGHT_SHIFT" to XKeycode.KEY_SHIFT_R,
        "LEFT_CONTROL" to XKeycode.KEY_CTRL_L, "CONTROL" to XKeycode.KEY_CTRL_L, "RIGHT_CONTROL" to XKeycode.KEY_CTRL_R,
        "LEFT_ALT" to XKeycode.KEY_ALT_L, "ALT" to XKeycode.KEY_ALT_L, "RIGHT_ALT" to XKeycode.KEY_ALT_R,
        "UP_ARROW" to XKeycode.KEY_UP, "UP" to XKeycode.KEY_UP,
        "DOWN_ARROW" to XKeycode.KEY_DOWN, "DOWN" to XKeycode.KEY_DOWN,
        "LEFT_ARROW" to XKeycode.KEY_LEFT, "LEFT" to XKeycode.KEY_LEFT,
        "RIGHT_ARROW" to XKeycode.KEY_RIGHT, "RIGHT" to XKeycode.KEY_RIGHT,
        "PAGE_UP" to XKeycode.KEY_PRIOR, "PAGE_DOWN" to XKeycode.KEY_NEXT,
        "HOME" to XKeycode.KEY_HOME, "END" to XKeycode.KEY_END,
        "INSERT" to XKeycode.KEY_INSERT, "DELETE" to XKeycode.KEY_DEL,
        "CAPSLOCK" to XKeycode.KEY_CAPS_LOCK, "CAPS_LOCK" to XKeycode.KEY_CAPS_LOCK,
        "MINUS" to XKeycode.KEY_MINUS, "DASH" to XKeycode.KEY_MINUS,
        "EQUALS" to XKeycode.KEY_EQUAL, "EQUAL" to XKeycode.KEY_EQUAL,
        "LEFT_BRACKET" to XKeycode.KEY_BRACKET_LEFT, "RIGHT_BRACKET" to XKeycode.KEY_BRACKET_RIGHT,
        "SEMICOLON" to XKeycode.KEY_SEMICOLON, "SINGLE_QUOTE" to XKeycode.KEY_APOSTROPHE,
        "BACK_TICK" to XKeycode.KEY_GRAVE, "GRAVE" to XKeycode.KEY_GRAVE,
        "BACKSLASH" to XKeycode.KEY_BACKSLASH, "FORWARD_SLASH" to XKeycode.KEY_SLASH, "SLASH" to XKeycode.KEY_SLASH,
        "COMMA" to XKeycode.KEY_COMMA, "PERIOD" to XKeycode.KEY_PERIOD,
    )

    fun keycode(name: String): XKeycode? {
        val n = name.trim().uppercase()
        NAMED[n]?.let { return it }
        if (n.length == 1 && (n[0] in 'A'..'Z' || n[0] in '0'..'9')) return runCatching { XKeycode.valueOf("KEY_$n") }.getOrNull()
        val f = n.removePrefix("F").toIntOrNull()
        if (n.startsWith("F") && f != null && f in 1..12) return runCatching { XKeycode.valueOf("KEY_F$f") }.getOrNull()
        val keypad = n.removePrefix("KEYPAD_").takeIf { n.startsWith("KEYPAD_") }
        if (keypad != null && keypad.length == 1 && keypad[0] in '0'..'9') return runCatching { XKeycode.valueOf("KEY_KP_$keypad") }.getOrNull()
        return null
    }
}
