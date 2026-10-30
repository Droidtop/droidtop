package org.pocketworkstation.pckeyboard

/**
 * "Switch keyboard" (docs/SPEC.md 6a, Droidtop/tracker#717): one code that moves to the next keyboard enabled on the
 * device, so someone typing a second language in another keyboard does not go through Settings. A long press on the
 * space bar sends it, the place other keyboards keep it; a layout may also carry it as a key of its own. It addresses
 * the keyboard, not the text, so it never reaches a container (HackersKeyCodes drops it).
 */
object SwitchKeyboard {
    /**
     * The key code, beside the fork's other keyboard codes (LatinKeyboardView.KEYCODE_OPTIONS and on, -100 to -105); the
     * one definition, which LatinIME and LatinKeyboardView read.
     */
    const val CODE = -106

    /** The code a long press on the key whose primary code is [primaryCode] sends, or null for the key's own. */
    fun longPressCode(primaryCode: Int): Int? = if (primaryCode == ' '.code) CODE else null
}
