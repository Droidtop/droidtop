package dev.droidtop.library

import android.content.Context

/**
 * The layouts a Windows game's pad can be played through (Droidtop/tracker#618 C5, #228; docs/SPEC.md 5b
 * "Controller layouts"), droidtop's own, in the order the running game's L2 sheet's Controls row steps through them
 * after Gamepad (null: no layout, the pad reaches the game as a pad). Game properties sets the same choice before a
 * launch; the runtime holds each layout's file.
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

/**
 * Each Windows game's [GameControls] by library id, where the L2 sheet's Controls row and Game properties both read
 * and write it. Disk: IO only.
 */
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
}
