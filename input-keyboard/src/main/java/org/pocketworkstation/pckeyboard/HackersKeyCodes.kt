package org.pocketworkstation.pckeyboard

import android.view.KeyEvent

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

    /**
     * Codes that address the KEYBOARD rather than a key: switch layout,
     * open the options dialog, start voice input, cycle language, enter
     * compose mode, the Fn layer. They have no meaning to a container and
     * are dropped rather than guessed at. The fork's own constants, read
     * directly (Droidtop/tracker#772): Kotlin in this package sees
     * `LatinKeyboardView`'s package-private ones, so a renumbering there
     * reaches here too.
     */
    private val KEYBOARD_INTERNAL = setOf(
        Keyboard.KEYCODE_MODE_CHANGE, Keyboard.KEYCODE_CANCEL, Keyboard.KEYCODE_DONE, Keyboard.KEYCODE_ALT_SYM,
        LatinKeyboardView.KEYCODE_OPTIONS, LatinKeyboardView.KEYCODE_OPTIONS_LONGPRESS, LatinKeyboardView.KEYCODE_VOICE,
        LatinKeyboardView.KEYCODE_F1, LatinKeyboardView.KEYCODE_NEXT_LANGUAGE, LatinKeyboardView.KEYCODE_PREV_LANGUAGE,
        SwitchKeyboard.CODE, LatinKeyboardView.KEYCODE_COMPOSE, LatinKeyboardView.KEYCODE_FN,
    )

    /**
     * The keystroke [hkCode] means, or null when it is either a printable
     * character (use [charStroke], which asks the platform rather than a
     * table) or something only the keyboard itself can act on.
     */
    fun specialStroke(hkCode: Int): KeyStroke? = when {
        hkCode in KEYBOARD_INTERNAL -> null
        hkCode == Keyboard.KEYCODE_SHIFT -> KeyStroke(KeyEvent.KEYCODE_SHIFT_LEFT)
        // The fork's DELETE is Backspace, which is Android's KEYCODE_DEL.
        hkCode == Keyboard.KEYCODE_DELETE -> KeyStroke(KeyEvent.KEYCODE_DEL)
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
 * [KeyTable]'s key for a character, and Android's virtual-keyboard map ([KeyTable.platform]) for a character the
 * table does not hold.
 */
class AndroidCharKeyResolver : CharKeyResolver {
    override fun resolve(character: Char): KeyStroke? {
        KeyTable.stroke(character)?.let { return it }
        val events = KeyTable.platform.getEvents(charArrayOf(character)) ?: return null
        val down = events.firstOrNull { it.action == KeyEvent.ACTION_DOWN && it.keyCode != KeyEvent.KEYCODE_SHIFT_LEFT }
            ?: return null
        return KeyStroke(
            androidKeyCode = down.keyCode,
            shift = down.metaState and KeyEvent.META_SHIFT_ON != 0,
        )
    }
}
