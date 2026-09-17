package dev.droidtop.shell.gamepad.input

import android.content.Context
import androidx.compose.ui.input.key.Key
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

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

    /**
     * Whether the two face buttons are swapped: A cancels and B confirms,
     * the Nintendo-style layout half the pads in the world ship.
     *
     * Held here rather than read per press because [actionFor] is on the
     * key path of every screen and has no Context. [load] is called when
     * the shell starts and whenever the answer changes (onboarding's
     * Controller step, the Settings row), which is every time it can
     * change -- the preference is never written by anything else.
     */
    @Volatile
    private var swapped: Boolean = false

    fun load(context: Context) {
        useSwap(ControllerPrefs.swapConfirmCancel(context))
    }

    /** [load] without a Context, for the unit tests that exercise the rule. */
    internal fun useSwap(swapConfirmCancel: Boolean) {
        swapped = swapConfirmCancel
    }

    fun actionFor(key: Key): GamepadAction? = DEFAULT[key]?.let(::applySwap)

    /**
     * The swap, in ONE place, applied to the meaning rather than to the
     * map: A and B trade what they mean, and every other action, BACK
     * included, is untouched. BACK stays BACK because it is the system's
     * own back and not a face button -- a person who swapped their face
     * buttons did not ask for the hardware back key to start confirming.
     */
    private fun applySwap(action: GamepadAction): GamepadAction = when {
        !swapped -> action
        action == GamepadAction.A -> GamepadAction.B
        action == GamepadAction.B -> GamepadAction.A
        else -> action
    }

    /**
     * The reverse: the Android key code an on-screen touch affordance
     * dispatches to MEAN this action. Touch does not get its own copy of
     * what a press does -- it sends the real key event and every existing
     * `onKeyEvent` handler treats it as the press it is (see
     * `rememberGamepadTouch`). Derived from [DEFAULT] rather than written
     * out twice, so a remap that changes one changes both.
     */
    fun keyCodeFor(action: GamepadAction): Int = when (applySwap(action)) {
        GamepadAction.A -> android.view.KeyEvent.KEYCODE_BUTTON_A
        GamepadAction.B -> android.view.KeyEvent.KEYCODE_BUTTON_B
        GamepadAction.X -> android.view.KeyEvent.KEYCODE_BUTTON_X
        GamepadAction.Y -> android.view.KeyEvent.KEYCODE_BUTTON_Y
        GamepadAction.START -> android.view.KeyEvent.KEYCODE_BUTTON_START
        GamepadAction.SELECT -> android.view.KeyEvent.KEYCODE_BUTTON_SELECT
        GamepadAction.UP -> android.view.KeyEvent.KEYCODE_DPAD_UP
        GamepadAction.DOWN -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
        GamepadAction.LEFT -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
        GamepadAction.RIGHT -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        GamepadAction.L -> android.view.KeyEvent.KEYCODE_BUTTON_L1
        GamepadAction.R -> android.view.KeyEvent.KEYCODE_BUTTON_R1
        GamepadAction.L2 -> android.view.KeyEvent.KEYCODE_BUTTON_L2
        GamepadAction.R2 -> android.view.KeyEvent.KEYCODE_BUTTON_R2
        GamepadAction.L3 -> android.view.KeyEvent.KEYCODE_BUTTON_THUMBL
        GamepadAction.R3 -> android.view.KeyEvent.KEYCODE_BUTTON_THUMBR
        GamepadAction.BACK -> android.view.KeyEvent.KEYCODE_BACK
    }

    /**
     * What a key IS on the pad, said by POSITION, for a person checking
     * that droidtop reads their controller (onboarding's Controller step).
     *
     * Deliberately not [labelFor]: that says what a press MEANS, which is
     * the thing the face-button question is about. Android's own gamepad
     * key codes are positional -- `KEYCODE_BUTTON_A` is the bottom face
     * button whatever the plastic says -- so this reads them that way and
     * never claims to know what is printed on the pad.
     */
    fun positionName(key: Key): String? = when (key) {
        Key.ButtonA, Key.DirectionCenter -> "the bottom face button"
        Key.ButtonB -> "the right face button"
        Key.ButtonX -> "the left face button"
        Key.ButtonY -> "the top face button"
        Key.ButtonL1 -> "the left shoulder"
        Key.ButtonR1 -> "the right shoulder"
        Key.ButtonL2 -> "the left trigger"
        Key.ButtonR2 -> "the right trigger"
        Key.ButtonThumbLeft -> "the left stick press"
        Key.ButtonThumbRight -> "the right stick press"
        Key.ButtonStart -> "Start"
        Key.ButtonSelect -> "Select"
        Key.DirectionUp -> "up on the d-pad"
        Key.DirectionDown -> "down on the d-pad"
        Key.DirectionLeft -> "left on the d-pad"
        Key.DirectionRight -> "right on the d-pad"
        else -> null
    }

    /**
     * Real label shown in the (currently still hand-drawn, see
     * `ButtonHintFooter`) help bar for [action] -- matches droidtop's
     * existing on-screen labels ("A", "B", "L/R", "◄/►") exactly, so
     * routing call sites through [GamepadKeyMap] doesn't change what a
     * user sees yet. Like [keyCodeFor] this answers in PHYSICAL terms --
     * which button to press -- so with the face buttons swapped a hint
     * for "confirm" names the button that now confirms. Real theme-provided button-icon glyphs
     * (`<helpsystem>`'s `iconColor`/`customButtonIcon`) are separate,
     * later work.
     */
    fun labelFor(action: GamepadAction): String = when (applySwap(action)) {
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
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
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
