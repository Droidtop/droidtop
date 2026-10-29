package dev.droidtop.mapping

/**
 * Steam's key names in a layout's `key_press` bindings as Android key codes (Droidtop/tracker#383), for a host that
 * injects keys the way Android reports them (the desktop's seat, through its one Android-to-evdev table). Pure; the
 * codes are Android's `KeyEvent.KEYCODE_*` values, written out as [PadInputs] writes its own, so this module keeps no
 * Android types. Letters, digits and F1 to F12 are themselves; the rest are Steam's words, with the common spellings of
 * each accepted. A name with no key here is null, and that binding does nothing.
 */
object SteamKeys {
    private const val KEYCODE_0 = 7
    private const val KEYCODE_A = 29
    private const val KEYCODE_F1 = 131

    private val NAMED: Map<String, Int> = mapOf(
        "SPACE" to 62,
        "RETURN" to 66, "ENTER" to 66,
        "ESCAPE" to 111, "ESC" to 111,
        "TAB" to 61,
        "BACKSPACE" to 67,
        "DELETE" to 112,
        "INSERT" to 124,
        "LEFT_SHIFT" to 59, "SHIFT" to 59, "RIGHT_SHIFT" to 60,
        "LEFT_CONTROL" to 113, "CONTROL" to 113, "RIGHT_CONTROL" to 114,
        "LEFT_ALT" to 57, "ALT" to 57, "RIGHT_ALT" to 58,
        "LEFT_WINDOWS" to 117, "RIGHT_WINDOWS" to 118,
        "UP_ARROW" to 19, "UP" to 19,
        "DOWN_ARROW" to 20, "DOWN" to 20,
        "LEFT_ARROW" to 21, "LEFT" to 21,
        "RIGHT_ARROW" to 22, "RIGHT" to 22,
        "PAGE_UP" to 92, "PAGE_DOWN" to 93,
        "HOME" to 122, "END" to 123,
        "CAPSLOCK" to 115, "CAPS_LOCK" to 115,
        "MINUS" to 69, "DASH" to 69,
        "EQUALS" to 70, "EQUAL" to 70,
        "LEFT_BRACKET" to 71, "RIGHT_BRACKET" to 72,
        "SEMICOLON" to 74, "SINGLE_QUOTE" to 75,
        "BACK_TICK" to 68, "GRAVE" to 68,
        "BACKSLASH" to 73, "FORWARD_SLASH" to 76, "SLASH" to 76,
        "COMMA" to 55, "PERIOD" to 56,
    )

    /** The Android key code Steam's key [name] is, or null. */
    fun androidKeyCode(name: String): Int? {
        val n = name.trim().uppercase()
        NAMED[n]?.let { return it }
        if (n.length == 1 && n[0] in 'A'..'Z') return KEYCODE_A + (n[0] - 'A')
        if (n.length == 1 && n[0] in '0'..'9') return KEYCODE_0 + (n[0] - '0')
        val f = n.removePrefix("F").toIntOrNull()
        if (n.startsWith("F") && f != null && f in 1..12) return KEYCODE_F1 + f - 1
        return null
    }
}
