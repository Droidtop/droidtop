package dev.droidtop.input.chinese

import org.pocketworkstation.pckeyboard.InputEngine

/**
 * The seam's keys as librime takes them (Droidtop/tracker#903 H8b): X11 keysyms and modifier masks, which
 * process_key reads (librime's key_table.h, X11's keysymdef.h). Pure.
 */
object RimeKeys {
    const val SHIFT_MASK = 1 shl 0

    const val BACKSPACE = 0xff08
    const val TAB = 0xff09
    const val RETURN = 0xff0d
    const val ESCAPE = 0xff1b
    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
    const val SPACE = 0x20

    /** A key with its modifier mask. */
    class Press(val keysym: Int, val mask: Int = 0) {
        override fun equals(other: Any?) = other is Press && other.keysym == keysym && other.mask == mask
        override fun hashCode() = keysym * 31 + mask
        override fun toString() = "0x${keysym.toString(16)}/$mask"
    }

    /**
     * A typed character's keysym: Latin-1 ones are their own code (X11's), any other code point is 0x01000000 plus it
     * (X11's Unicode keysyms). Enter and Tab as characters are their keys. Null for other control characters.
     */
    @JvmStatic
    fun forChar(code: Int): Int? = when {
        code == '\n'.code || code == '\r'.code -> RETURN
        code == '\t'.code -> TAB
        code in 0x20..0x7e || code in 0xa0..0xff -> code
        code in 0x100..0x10ffff && code !in 0xd800..0xdfff -> 0x01000000 or code
        else -> null
    }

    /**
     * An engine key's press; null for one librime has no counterpart for (Undo: librime cannot take back a commit;
     * Toggle: it has no multi-tap keys). Shift-Left and Shift-Right are the arrows with Shift: luna_pinyin binds
     * nothing to them, so they move nothing (librime has no clauses to resize, as Mozc does).
     */
    @JvmStatic
    fun forKey(key: InputEngine.Key): Press? = when (key) {
        InputEngine.Key.SPACE -> Press(SPACE)
        InputEngine.Key.ENTER -> Press(RETURN)
        InputEngine.Key.LEFT -> Press(LEFT)
        InputEngine.Key.RIGHT -> Press(RIGHT)
        InputEngine.Key.UP -> Press(UP)
        InputEngine.Key.DOWN -> Press(DOWN)
        InputEngine.Key.SHIFT_LEFT -> Press(LEFT, SHIFT_MASK)
        InputEngine.Key.SHIFT_RIGHT -> Press(RIGHT, SHIFT_MASK)
        InputEngine.Key.ESCAPE -> Press(ESCAPE)
        InputEngine.Key.UNDO, InputEngine.Key.TOGGLE -> null
    }
}
