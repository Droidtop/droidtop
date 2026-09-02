package org.pocketworkstation.pckeyboard

import android.view.KeyEvent
import android.view.KeyCharacterMap

/**
 * One physical keystroke: which key, and whether Shift is part of it.
 *
 * That is all a container needs, because the container's compositor owns
 * the layout -- :host-bridge hands it a fixed us/pc105 XKB keymap, and it
 * is that keymap, not droidtop, that decides `Shift`+`2` is `@`. Sending
 * anything richer than "which key, plus Shift" would be inventing a second
 * layout table beside the one already in play, which is the exact mistake
 * `EvdevKeys`' own documentation exists to prevent.
 */
data class KeyStroke(val androidKeyCode: Int, val shift: Boolean = false)

/**
 * Reads Hacker's Keyboard's own key codes.
 *
 * Nothing here is a new mapping table. Hacker's Keyboard already encodes
 * every non-printable key as the NEGATED Android `KeyEvent` keycode --
 * `LatinKeyboardView.KEYCODE_ESCAPE` is -111 and `KeyEvent.KEYCODE_ESCAPE`
 * is 111, `KEYCODE_CTRL_LEFT` is -113 against Android's 113, `KEYCODE_FKEY_F1`
 * is -131 against Android's `KEYCODE_F1` of 131, and so on for the whole
 * set. So the translation is the fork's own encoding read back, plus the
 * handful of codes that predate it (`Keyboard.KEYCODE_SHIFT` and friends,
 * which are -1..-6 and mean something to the keyboard rather than to a
 * computer).
 *
 * From there the existing `EvdevKeys` table takes Android keycodes to
 * evdev, and the compositor's keymap takes evdev to characters. Three
 * links in one chain, no branch of it duplicating another.
 */
object HackersKeyCodes {

    /** `Keyboard.KEYCODE_*`: the fork's pre-existing codes, which are not negated keycodes. */
    private const val HK_SHIFT = -1
    private const val HK_MODE_CHANGE = -2
    private const val HK_CANCEL = -3
    private const val HK_DONE = -4
    private const val HK_DELETE = -5
    private const val HK_ALT_SYM = -6

    /**
     * Codes that address the KEYBOARD rather than a key: switch layout,
     * open the options dialog, start voice input, cycle language, enter
     * compose mode. They have no meaning to a container and are dropped
     * rather than guessed at. (`LatinKeyboardView.KEYCODE_OPTIONS` and
     * the four after it, plus its `KEYCODE_COMPOSE`; listed by value
     * because they are package-private constants used from Java.)
     */
    private val KEYBOARD_INTERNAL = setOf(
        HK_MODE_CHANGE, HK_CANCEL, HK_DONE, HK_ALT_SYM,
        -100, -101, -102, -103, -104, -105, -10024,
    )

    /**
     * The keystroke [hkCode] means, or null when it is either a printable
     * character (use [charStroke], which asks the platform rather than a
     * table) or something only the keyboard itself can act on.
     */
    fun specialStroke(hkCode: Int): KeyStroke? = when {
        hkCode in KEYBOARD_INTERNAL -> null
        hkCode == HK_SHIFT -> KeyStroke(KeyEvent.KEYCODE_SHIFT_LEFT)
        // The fork's DELETE is Backspace, which is Android's KEYCODE_DEL.
        hkCode == HK_DELETE -> KeyStroke(KeyEvent.KEYCODE_DEL)
        // Control characters the fork emits as bare ASCII rather than as a
        // negated keycode.
        hkCode == 8 -> KeyStroke(KeyEvent.KEYCODE_DEL)
        hkCode == 9 -> KeyStroke(KeyEvent.KEYCODE_TAB)
        hkCode == 10 -> KeyStroke(KeyEvent.KEYCODE_ENTER)
        hkCode == 27 -> KeyStroke(KeyEvent.KEYCODE_ESCAPE)
        hkCode < 0 -> KeyStroke(-hkCode)
        else -> null
    }

    /** True for a code that is a printable character rather than a key identity. */
    fun isPrintable(hkCode: Int): Boolean = hkCode >= 32
}

/**
 * Turns a character into the key that produces it.
 *
 * Behind an interface so the modifier bookkeeping around it can be tested
 * without a device; the real implementation asks Android's own
 * `KeyCharacterMap` rather than carrying a table, which is the same
 * principle as leaving the layout to the compositor's keymap.
 */
fun interface CharKeyResolver {
    fun resolve(character: Char): KeyStroke?
}

/**
 * The platform's virtual-keyboard character map.
 *
 * `VIRTUAL_KEYBOARD` is the built-in full QWERTY map every device has,
 * chosen over the actual attached device's map because the destination is
 * a container running a us/pc105 keymap -- asking a French hardware
 * keyboard's map which key makes `q` would answer for a layout that is not
 * the one at the other end.
 */
class AndroidCharKeyResolver : CharKeyResolver {
    private val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)

    override fun resolve(character: Char): KeyStroke? {
        val events = map.getEvents(charArrayOf(character)) ?: return null
        val down = events.firstOrNull { it.action == KeyEvent.ACTION_DOWN && it.keyCode != KeyEvent.KEYCODE_SHIFT_LEFT }
            ?: return null
        return KeyStroke(
            androidKeyCode = down.keyCode,
            shift = down.metaState and KeyEvent.META_SHIFT_ON != 0,
        )
    }
}
