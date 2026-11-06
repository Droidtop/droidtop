package dev.droidtop.app.terminal

import dev.droidtop.shell.gamepad.input.GamepadAction

/**
 * What the pad does on droidtop's container terminal (docs/SPEC.md 3d, "A terminal on Android"; Droidtop/tracker#909).
 * The pad's meaning comes from the one key table every screen reads ([dev.droidtop.shell.gamepad.input.GamepadKeyMap],
 * the face-button swap applied there); this says what each meaning does here: the D-pad is the arrow keys, A is
 * Enter, X shows or hides the on-screen keyboard, and B (or Back) leaves. A keyboard's own keys never come through
 * here: Enter, Esc and the arrows of a keyboard go to the terminal as typed. Pure.
 */
object TerminalPad {
    sealed interface Press {
        /** A key the terminal sends as a keyboard's would, by its Android key code. */
        data class Key(val keyCode: Int) : Press

        data object ToggleKeyboard : Press

        data object Leave : Press
    }

    // android.view.KeyEvent's codes and android.view.InputDevice's source bits, as numbers, so the rules run on a JVM.
    const val KEYCODE_DPAD_UP = 19
    const val KEYCODE_DPAD_DOWN = 20
    const val KEYCODE_DPAD_LEFT = 21
    const val KEYCODE_DPAD_RIGHT = 22
    const val KEYCODE_ENTER = 66
    private const val KEYCODE_BUTTON_A = 96
    private const val KEYCODE_BUTTON_MODE = 110
    private const val KEYCODE_BUTTON_1 = 188
    private const val KEYCODE_BUTTON_16 = 203
    private const val SOURCE_GAMEPAD = 0x00000401
    private const val SOURCE_JOYSTICK = 0x01000010

    /** What [action] does on the terminal; null for a button that does nothing here (it is swallowed, not typed). */
    fun pressFor(action: GamepadAction): Press? = when (action) {
        GamepadAction.UP -> Press.Key(KEYCODE_DPAD_UP)
        GamepadAction.DOWN -> Press.Key(KEYCODE_DPAD_DOWN)
        GamepadAction.LEFT -> Press.Key(KEYCODE_DPAD_LEFT)
        GamepadAction.RIGHT -> Press.Key(KEYCODE_DPAD_RIGHT)
        GamepadAction.A -> Press.Key(KEYCODE_ENTER)
        GamepadAction.X -> Press.ToggleKeyboard
        GamepadAction.B, GamepadAction.BACK -> Press.Leave
        else -> null
    }

    /**
     * Whether a key event is the pad's: a gamepad or joystick source, or a pad button's code from anywhere (the hint
     * bar's pills send those with no source). A keyboard's keys are not, even where its device also reports a D-pad.
     */
    fun isPadKey(keyCode: Int, source: Int): Boolean =
        keyCode in KEYCODE_BUTTON_A..KEYCODE_BUTTON_MODE ||
            keyCode in KEYCODE_BUTTON_1..KEYCODE_BUTTON_16 ||
            (source and SOURCE_GAMEPAD) == SOURCE_GAMEPAD ||
            (source and SOURCE_JOYSTICK) == SOURCE_JOYSTICK

    /** Whether a held button repeats [press]: the arrows do, as a held arrow key does; Enter and leaving do not. */
    fun repeats(press: Press): Boolean = press is Press.Key && press.keyCode != KEYCODE_ENTER

    /** How long the first B (or Back) waits for the second that leaves, while the shell still runs. */
    const val LEAVE_CONFIRM_MS = 3_000L

    /** Whether a leave press at [nowMs] closes the terminal: at once when the shell has ended, else the second press within [LEAVE_CONFIRM_MS] of [armedAtMs]. */
    fun leaves(shellRunning: Boolean, armedAtMs: Long?, nowMs: Long): Boolean =
        !shellRunning || (armedAtMs != null && nowMs - armedAtMs in 0..LEAVE_CONFIRM_MS)
}
