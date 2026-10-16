package org.pocketworkstation.pckeyboard

import android.view.KeyEvent

/**
 * What a pad's face key means, with the face-button swap applied: the one answer every screen that reads a pad
 * key itself asks, whatever module it is in (review, 2026-10-16: raw BUTTON_A and BUTTON_B handlers ignored a
 * Nintendo-layout or toggled pad, so on such a pad they confirmed with the cancel button). [swapped] is the live
 * fact the shell's controller layout decides (`ControllerLayouts`, which keeps it in step); Android's key codes are
 * positional, and when the swap is on A and B trade meaning, and so do X and Y. A keyboard's Enter and Escape are
 * outside the swap: Enter confirms and Escape cancels whatever a pad says; the system back key is back.
 */
object FaceKeys {
    enum class Role { CONFIRM, CANCEL, X, Y }

    @Volatile
    var swapped: Boolean = false

    /** The role [keyCode] plays, or null for a key that is not a face key, Enter, Escape or the centre of a D-pad. */
    fun roleOf(keyCode: Int): Role? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Role.CONFIRM
        KeyEvent.KEYCODE_ESCAPE -> Role.CANCEL
        // DPAD_CENTER is what Android re-sends for a pad's unhandled bottom face button, so it is inside the swap.
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> if (swapped) Role.CANCEL else Role.CONFIRM
        KeyEvent.KEYCODE_BUTTON_B -> if (swapped) Role.CONFIRM else Role.CANCEL
        KeyEvent.KEYCODE_BUTTON_X -> if (swapped) Role.Y else Role.X
        KeyEvent.KEYCODE_BUTTON_Y -> if (swapped) Role.X else Role.Y
        else -> null
    }

    fun isConfirm(keyCode: Int): Boolean = roleOf(keyCode) == Role.CONFIRM

    /** Cancel, or the system back key. */
    fun isCancel(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_BACK || roleOf(keyCode) == Role.CANCEL
}
