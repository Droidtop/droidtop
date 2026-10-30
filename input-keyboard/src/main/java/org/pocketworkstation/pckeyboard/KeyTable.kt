package org.pocketworkstation.pckeyboard

import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * The one table from characters and key names to Android keys (Droidtop/tracker#772): the slab, macros and the
 * bridge from Hacker's Keyboard all read it, where each used to carry its own copy.
 *
 * Key codes, not a layout. The far side owns the layout (a container's compositor runs a us/pc105 keymap, see
 * [KeyStroke]), so a character is the US key that types it, plus Shift where that key needs it: `!` is Shift+1.
 * A character the table does not hold (anything outside printable ASCII) is asked of Android's own virtual-keyboard
 * map ([platform]), which is loaded once.
 */
object KeyTable {
    private const val UNSHIFTED = "`1234567890-=[]\\;',./"
    private const val SHIFTED = "~!@#\$%^&*()_+{}|:\"<>?"
    private val CODES = intArrayOf(
        KeyEvent.KEYCODE_GRAVE, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4,
        KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_9,
        KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_LEFT_BRACKET,
        KeyEvent.KEYCODE_RIGHT_BRACKET, KeyEvent.KEYCODE_BACKSLASH, KeyEvent.KEYCODE_SEMICOLON,
        KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_SLASH,
    )

    /** The US key that types [ch], with Shift where it needs it; null outside printable ASCII, space and tab. Pure. */
    fun stroke(ch: Char): KeyStroke? = when {
        ch in 'a'..'z' -> KeyStroke(KeyEvent.KEYCODE_A + (ch - 'a'))
        ch in 'A'..'Z' -> KeyStroke(KeyEvent.KEYCODE_A + (ch - 'A'), shift = true)
        ch == ' ' -> KeyStroke(KeyEvent.KEYCODE_SPACE)
        ch == '\t' -> KeyStroke(KeyEvent.KEYCODE_TAB)
        ch in UNSHIFTED -> KeyStroke(CODES[UNSHIFTED.indexOf(ch)])
        // The shifted characters pair with the unshifted ones position for position (~ on `, ! on 1, ...).
        ch in SHIFTED -> KeyStroke(CODES[SHIFTED.indexOf(ch)], shift = true)
        else -> null
    }

    /** The key a name means (`esc`, `pgup`, `f5`; any case), or null. Pure. */
    fun named(name: String): Int? = NAMED[name.lowercase()]

    private val NAMED: Map<String, Int> = buildMap {
        put("esc", KeyEvent.KEYCODE_ESCAPE)
        put("escape", KeyEvent.KEYCODE_ESCAPE)
        put("enter", KeyEvent.KEYCODE_ENTER)
        put("return", KeyEvent.KEYCODE_ENTER)
        put("tab", KeyEvent.KEYCODE_TAB)
        put("space", KeyEvent.KEYCODE_SPACE)
        put("bs", KeyEvent.KEYCODE_DEL)
        put("backspace", KeyEvent.KEYCODE_DEL)
        put("del", KeyEvent.KEYCODE_FORWARD_DEL)
        put("delete", KeyEvent.KEYCODE_FORWARD_DEL)
        put("ins", KeyEvent.KEYCODE_INSERT)
        put("insert", KeyEvent.KEYCODE_INSERT)
        put("home", KeyEvent.KEYCODE_MOVE_HOME)
        put("end", KeyEvent.KEYCODE_MOVE_END)
        put("pgup", KeyEvent.KEYCODE_PAGE_UP)
        put("pageup", KeyEvent.KEYCODE_PAGE_UP)
        put("pgdn", KeyEvent.KEYCODE_PAGE_DOWN)
        put("pagedown", KeyEvent.KEYCODE_PAGE_DOWN)
        put("up", KeyEvent.KEYCODE_DPAD_UP)
        put("down", KeyEvent.KEYCODE_DPAD_DOWN)
        put("left", KeyEvent.KEYCODE_DPAD_LEFT)
        put("right", KeyEvent.KEYCODE_DPAD_RIGHT)
        for (n in 1..12) put("f$n", KeyEvent.KEYCODE_F1 + (n - 1))
    }

    /**
     * Android's built-in full QWERTY map, for what [stroke] does not hold. `VIRTUAL_KEYBOARD` rather than the attached
     * device's map, because the destination runs a us/pc105 keymap: a French hardware keyboard's map would answer for
     * a layout that is not the one at the other end. Loaded once, on first use.
     */
    val platform: KeyCharacterMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }
}
