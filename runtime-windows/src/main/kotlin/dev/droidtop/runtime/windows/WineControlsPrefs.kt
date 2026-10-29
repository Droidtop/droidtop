package dev.droidtop.runtime.windows

import android.content.Context

/**
 * Per game, which layout the pad is played through (Droidtop/tracker#618): Gamepad (none) until the person picks one in
 * the Quick Menu, then kept for that game. Reads and writes preferences: call off the main thread.
 */
internal object WineControlsPrefs {
    private const val FILE = "droidtop_wine_controls"
    private const val LAYOUT = "layout:"

    /** The first build stored only an on/off for the keyboard-and-mouse layout; it still counts. */
    private const val KEYBOARD_MOUSE = "keyboard_mouse:"

    fun layout(context: Context, entryId: String): WineControls? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (prefs.contains(LAYOUT + entryId)) return WineControls.byId(prefs.getString(LAYOUT + entryId, null))
        return if (prefs.getBoolean(KEYBOARD_MOUSE + entryId, false)) WineControls.KEYBOARD_MOUSE else null
    }

    fun setLayout(context: Context, entryId: String, layout: WineControls?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(LAYOUT + entryId, layout?.id ?: "").remove(KEYBOARD_MOUSE + entryId).apply()
    }
}
