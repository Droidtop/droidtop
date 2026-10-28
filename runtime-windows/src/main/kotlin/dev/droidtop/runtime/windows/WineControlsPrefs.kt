package dev.droidtop.runtime.windows

import android.content.Context

/**
 * Per game, whether the pad is played through the keyboard-and-mouse layout (Droidtop/tracker#618): off until the
 * person turns it on in the Quick Menu, then kept for that game. Reads and writes preferences: call off the main thread.
 */
internal object WineControlsPrefs {
    private const val FILE = "droidtop_wine_controls"
    private const val KEYBOARD_MOUSE = "keyboard_mouse:"

    fun keyboardMouse(context: Context, entryId: String): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEYBOARD_MOUSE + entryId, false)

    fun setKeyboardMouse(context: Context, entryId: String, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEYBOARD_MOUSE + entryId, on).apply()
    }
}
