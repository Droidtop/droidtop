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

    private const val MOUSE_MODE = "mouse_mode:"

    /** Before #618 C4, mouse mode was kept as "on|stick|clicks" under this key in droidtop's main preferences. */
    private const val OLD_MOUSE_MODE = "droidtop_pad_mouse_"

    /** Whether the game's pad is in mouse mode (docs/SPEC.md 5b, "Controller mouse mode"): off until turned on. */
    fun mouseMode(context: Context, entryId: String): Boolean {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (prefs.contains(MOUSE_MODE + entryId)) return prefs.getBoolean(MOUSE_MODE + entryId, false)
        val old = context.getSharedPreferences(dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .getString(OLD_MOUSE_MODE + entryId, null)
        return old?.substringBefore('|') == "true"
    }

    fun setMouseMode(context: Context, entryId: String, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(MOUSE_MODE + entryId, on).apply()
        context.getSharedPreferences(dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE).edit()
            .remove(OLD_MOUSE_MODE + entryId).apply()
    }

    fun setLayout(context: Context, entryId: String, layout: WineControls?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(LAYOUT + entryId, layout?.id ?: "").remove(KEYBOARD_MOUSE + entryId).apply()
    }
}
