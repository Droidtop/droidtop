package dev.droidtop.runtime.windows

/**
 * The layouts a Windows game's pad can be played through (Droidtop/tracker#618 C5; docs/SPEC.md 5b "Controller
 * layouts"), droidtop's own, in Steam's format, in the order the Quick Menu's Controls row steps through them after
 * Gamepad (no layout: the pad reaches the game as a pad).
 */
internal enum class WineControls(val id: String, val line: String, val raw: Int) {
    KEYBOARD_MOUSE("keyboard_mouse", "Keyboard and mouse: left stick WASD, right stick the mouse", R.raw.controls_keyboard_mouse),
    MOUSE_ONLY("mouse_only", "Mouse only: left stick the pointer, A clicks, B right-clicks", R.raw.controls_mouse_only),
    ;

    companion object {
        const val GAMEPAD_LINE = "Gamepad"

        fun byId(id: String?): WineControls? = entries.firstOrNull { it.id == id }

        /** The choice after [current] (null is Gamepad), wrapping back to Gamepad. Pure. */
        fun next(current: WineControls?): WineControls? = when (current) {
            null -> entries.first()
            entries.last() -> null
            else -> entries[current.ordinal + 1]
        }
    }
}
