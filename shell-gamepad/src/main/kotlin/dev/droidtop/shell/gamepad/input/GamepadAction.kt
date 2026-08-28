package dev.droidtop.shell.gamepad.input

import android.content.Context
import androidx.compose.ui.input.key.Key

/**
 * Real logical gamepad actions -- the same vocabulary real ES-DE's own
 * `es_input.xml` uses (a/b/x/y/start/select/up/down/left/right/l/r/l2/
 * r2/l3/r3), and what a theme's `<helpsystem>` button-icon glyphs are
 * keyed to. This is the missing layer between physical Android
 * [Key] events and what droidtop's UI actually means by a press --
 * before this, ~13 separate places in `GamepadShell.kt` and
 * `theme/EsDeSystemListView.kt` each hardcoded `event.key == Key.ButtonA`
 * (or `Key.ButtonA || Key.DirectionCenter || Key.Enter` for "confirm")
 * directly, so a real remap (or a future real es_input.xml-style import)
 * would have meant editing every one of those sites individually.
 */
enum class GamepadAction {
    A, B, X, Y, START, SELECT,
    UP, DOWN, LEFT, RIGHT,
    L, R, L2, R2, L3, R3,
    BACK,
}

/**
 * Resolves a physical [Key] to the [GamepadAction] it means, and the
 * real label shown in the button-hint bar for that action. [DEFAULT] is
 * droidtop's own existing hardcoded assumptions from before this file
 * existed, now centralized instead of duplicated -- confirmed against
 * every real `Key.Button*`/`Key.Direction*` check previously scattered
 * across `GamepadShell.kt`. [InputMapPrefs] exists so a future real
 * remap screen (an actual es_input.xml-style override UI) has somewhere
 * to persist a user's own mapping -- reading it isn't wired into
 * [actionFor] yet (real, scoped follow-up work, not attempted here),
 * this is deliberately just the abstraction layer other real screens can
 * be built on.
 */
object GamepadKeyMap {
    private val DEFAULT: Map<Key, GamepadAction> = mapOf(
        Key.ButtonA to GamepadAction.A,
        Key.DirectionCenter to GamepadAction.A,
        Key.Enter to GamepadAction.A,
        Key.ButtonB to GamepadAction.B,
        Key.Back to GamepadAction.BACK,
        Key.ButtonX to GamepadAction.X,
        Key.ButtonY to GamepadAction.Y,
        Key.ButtonStart to GamepadAction.START,
        Key.ButtonSelect to GamepadAction.SELECT,
        Key.DirectionUp to GamepadAction.UP,
        Key.DirectionDown to GamepadAction.DOWN,
        Key.DirectionLeft to GamepadAction.LEFT,
        Key.DirectionRight to GamepadAction.RIGHT,
        Key.ButtonL1 to GamepadAction.L,
        Key.ButtonR1 to GamepadAction.R,
        Key.ButtonL2 to GamepadAction.L2,
        Key.ButtonR2 to GamepadAction.R2,
        Key.ButtonThumbLeft to GamepadAction.L3,
        Key.ButtonThumbRight to GamepadAction.R3,
    )

    fun actionFor(key: Key): GamepadAction? = DEFAULT[key]

    /**
     * Real label shown in the (currently still hand-drawn, see
     * `ButtonHintFooter`) help bar for [action] -- matches droidtop's
     * existing on-screen labels ("A", "B", "L/R", "◄/►") exactly, so
     * routing call sites through [GamepadKeyMap] doesn't change what a
     * user sees yet. Real theme-provided button-icon glyphs
     * (`<helpsystem>`'s `iconColor`/`customButtonIcon`) are separate,
     * later work.
     */
    fun labelFor(action: GamepadAction): String = when (action) {
        GamepadAction.A -> "A"
        GamepadAction.B -> "B"
        GamepadAction.X -> "X"
        GamepadAction.Y -> "Y"
        GamepadAction.START -> "Start"
        GamepadAction.SELECT -> "Select"
        GamepadAction.UP -> "▲"
        GamepadAction.DOWN -> "▼"
        GamepadAction.LEFT -> "◄"
        GamepadAction.RIGHT -> "►"
        GamepadAction.L -> "L"
        GamepadAction.R -> "R"
        GamepadAction.L2 -> "L2"
        GamepadAction.R2 -> "R2"
        GamepadAction.L3 -> "L3"
        GamepadAction.R3 -> "R3"
        GamepadAction.BACK -> "B"
    }
}

/**
 * Storage for a future real per-user remap (same SharedPreferences
 * convention as [dev.droidtop.library.consoles.CustomPlayerPrefs] and
 * every other droidtop `*Prefs` object -- see that class's own doc
 * comment). Not yet read by [GamepadKeyMap.actionFor]; exists now so
 * that wiring doesn't need a second pass through every call site again
 * once a real remap screen is built.
 */
object InputMapPrefs {
    private const val PREFS_NAME = "com.android.launcher3.prefs"
    private const val KEY_PREFIX = "droidtop_gamepad_remap_"

    fun get(context: Context, action: GamepadAction): Int? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getInt("$KEY_PREFIX${action.name}", -1)
        return stored.takeIf { it != -1 }
    }

    fun set(context: Context, action: GamepadAction, keyCode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt("$KEY_PREFIX${action.name}", keyCode)
            .apply()
    }

    fun clear(context: Context, action: GamepadAction) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove("$KEY_PREFIX${action.name}")
            .apply()
    }
}
