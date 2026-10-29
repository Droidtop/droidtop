package dev.droidtop.library

import android.content.Context

/**
 * The layouts a Windows game's pad can be played through (Droidtop/tracker#618 C5, #228; docs/SPEC.md 5b
 * "Controller layouts"), droidtop's own, in the order the Quick Menu's Controls row steps through them after Gamepad
 * (null: no layout, the pad reaches the game as a pad). Game properties sets the same choice before a launch; the
 * runtime holds each layout's file.
 */
enum class GameControls(val id: String, val label: String, val line: String) {
    KEYBOARD_MOUSE("keyboard_mouse", "Keyboard and mouse", "Keyboard and mouse: left stick WASD, right stick the mouse"),
    MOUSE_ONLY("mouse_only", "Mouse only", "Mouse only: left stick the pointer, A clicks, B right-clicks"),
    ;

    companion object {
        const val GAMEPAD_LABEL = "Gamepad"
        const val GAMEPAD_LINE = "Gamepad"

        fun byId(id: String?): GameControls? = entries.firstOrNull { it.id == id }

        /** The choice after [current] (null is Gamepad), wrapping back to Gamepad. Pure. */
        fun next(current: GameControls?): GameControls? = when (current) {
            null -> entries.first()
            entries.last() -> null
            else -> entries[current.ordinal + 1]
        }
    }
}

/** Each Windows game's [GameControls] by library id, where the Quick Menu and Game properties both read and write it. Disk: IO only. */
object GameControlsPrefs {
    private const val FILE = "droidtop_wine_controls"
    private const val LAYOUT = "layout:"

    /** The first build stored only an on/off for the keyboard-and-mouse layout; it still counts. */
    private const val KEYBOARD_MOUSE = "keyboard_mouse:"

    fun layout(context: Context, entryId: String): GameControls? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (prefs.contains(LAYOUT + entryId)) return GameControls.byId(prefs.getString(LAYOUT + entryId, null))
        return if (prefs.getBoolean(KEYBOARD_MOUSE + entryId, false)) GameControls.KEYBOARD_MOUSE else null
    }

    fun setLayout(context: Context, entryId: String, layout: GameControls?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(LAYOUT + entryId, layout?.id ?: "").remove(KEYBOARD_MOUSE + entryId).apply()
    }

    private const val MOUSE_MODE = "mouse_mode:"

    /** Before #618 C4, mouse mode was kept as "on|stick|clicks" under this key in droidtop's main preferences. */
    private const val OLD_MOUSE_MODE = "droidtop_pad_mouse_"

    /**
     * Whether the game's pad is in mouse mode (docs/SPEC.md 5b, "Controller mouse mode"): the person's choice for it,
     * else [byDefault] (on for a game whose genre is played with a pointer, the runtime's MouseModeDefault;
     * Droidtop/tracker#618 C2).
     */
    fun mouseMode(context: Context, entryId: String, byDefault: Boolean = false): Boolean {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (prefs.contains(MOUSE_MODE + entryId)) return prefs.getBoolean(MOUSE_MODE + entryId, false)
        val old = context.getSharedPreferences(dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .getString(OLD_MOUSE_MODE + entryId, null) ?: return byDefault
        return old.substringBefore('|') == "true"
    }

    private const val MOUSE_HINT = "mouse_hint:"

    /** True the first time a game asks, then never again: its first start shows how mouse mode is reached (#618 C2). */
    fun firstMouseHint(context: Context, entryId: String): Boolean {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (prefs.getBoolean(MOUSE_HINT + entryId, false)) return false
        prefs.edit().putBoolean(MOUSE_HINT + entryId, true).apply()
        return true
    }

    fun setMouseMode(context: Context, entryId: String, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(MOUSE_MODE + entryId, on).apply()
        context.getSharedPreferences(dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE).edit()
            .remove(OLD_MOUSE_MODE + entryId).apply()
    }
}
