package dev.droidtop.shell.gamepad.input

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode

/**
 * What a press MEANS -- the one vocabulary every screen in the shell reads
 * (docs/SPEC.md 6e). It is real ES-DE's own `es_input.xml` vocabulary
 * (a/b/x/y/start/select/up/down/left/right/l/r/l2/r2/l3/r3), which is
 * also what a theme's `<helpsystem>` glyphs are keyed to, and it is
 * LOGICAL, not physical: [A] is "accept" and [B] is "back one level"
 * whichever face button the person chose for them, and a keyboard's Enter
 * is [A] and Escape is [B]. What each one means in droidtop:
 *
 * - [A] accept, [B] back one level, [X] the focused thing's toggle
 *   (favourite), [Y] the focused thing's detail or info;
 * - [UP]/[DOWN]/[LEFT]/[RIGHT] move;
 * - [L]/[R] previous/next tab (a keyboard's Page Up/Page Down and
 *   Shift+Tab/Tab);
 * - [R2] the Quick Menu (a held Select is the same press, for pads whose
 *   triggers send no key), [START] the same menu, [SELECT] options for
 *   where you are, [L2] a PC game's own menu;
 * - [BACK] the system back key. It belongs to the back dispatcher
 *   (`BackHandler`), never to a key handler, so a long press of it still
 *   reaches the activity.
 */
enum class GamepadAction {
    A, B, X, Y, START, SELECT,
    UP, DOWN, LEFT, RIGHT,
    L, R, L2, R2, L3, R3,
    BACK,
    ;

    /** A direction repeats while it is held; nothing else does. */
    val isDirection: Boolean get() = this == UP || this == DOWN || this == LEFT || this == RIGHT
}

/**
 * The ONE table from a key to the [GamepadAction] it means, and the label
 * and key code the hint bar uses for each action (docs/SPEC.md 6e). Pad
 * buttons and the keyboard are in the same table, so no screen keeps a
 * key list of its own.
 */
object GamepadKeyMap {
    /**
     * Key code to action, BEFORE the face-button swap. Android's key codes
     * are plain constants, so this is a pure table the unit tests read
     * directly.
     *
     * The keyboard rows are SPEC 6's "a keyboard is treated as a pad":
     * arrows are the D-pad, Enter is A, Escape is B, Space is X, Backspace
     * is Y, the menu key is Select, F10 is R2, and Page Up/Page Down and
     * Shift+Tab/Tab are L1/R1 -- the top bar is never a D-pad focus target
     * (SPEC 7j), so a keyboard needs its own route to the shoulders that
     * cycle the sections. A focused text field takes its own keys first.
     */
    internal fun physicalAction(keyCode: Int, shift: Boolean = false): GamepadAction? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        -> GamepadAction.A
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> GamepadAction.B
        KeyEvent.KEYCODE_BACK -> GamepadAction.BACK
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_SPACE -> GamepadAction.X
        KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_DEL -> GamepadAction.Y
        KeyEvent.KEYCODE_BUTTON_START -> GamepadAction.START
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_MENU -> GamepadAction.SELECT
        KeyEvent.KEYCODE_DPAD_UP -> GamepadAction.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> GamepadAction.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> GamepadAction.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> GamepadAction.RIGHT
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_UP -> GamepadAction.L
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_DOWN -> GamepadAction.R
        KeyEvent.KEYCODE_TAB -> if (shift) GamepadAction.L else GamepadAction.R
        KeyEvent.KEYCODE_BUTTON_L2 -> GamepadAction.L2
        KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_F10 -> GamepadAction.R2
        KeyEvent.KEYCODE_BUTTON_THUMBL -> GamepadAction.L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> GamepadAction.R3
        else -> null
    }

    /**
     * Keys a text field types or moves with. A PREVIEW handler sees a key
     * before the focused field does, so it leaves these alone; otherwise a
     * menu around a search box would eat its space bar.
     */
    internal fun isTextKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_TAB

    /**
     * Whether the two face buttons are swapped: A cancels and B confirms,
     * the Nintendo-style layout half the pads in the world ship.
     *
     * Held here rather than read per press because [actionFor] is on the
     * key path of every screen and has no Context. [load] is called when
     * the shell starts and whenever the answer changes (onboarding's
     * Controller step, the Settings row), which is every time it can
     * change -- the preference is never written by anything else.
     *
     * A SNAPSHOT state, not a plain `@Volatile` field, and that is the
     * whole of build 546's "changing the confirm button does not refresh
     * the hint row". The mapping and the legend are the same fact --
     * [labelFor] and [keyCodeFor] are what the hint bar draws and dispatches
     * -- but a plain field read inside a composition subscribes to nothing,
     * so the buttons changed meaning at once while the legend kept drawing
     * the old letters until the process was killed. Reading a snapshot
     * state inside composition subscribes to it, and writing it invalidates
     * every reader; reading it OFF the composition -- which is what the key
     * path does -- is an ordinary field read with no subscription and no
     * cost. One source of truth, observed by everything that draws it.
     */
    private val swappedState = mutableStateOf(false)
    private var swapped: Boolean
        get() = swappedState.value
        set(value) { swappedState.value = value }

    fun load(context: Context) {
        useSwap(ControllerPrefs.swapConfirmCancel(context))
    }

    /** [load] without a Context, for the unit tests that exercise the rule. */
    internal fun useSwap(swapConfirmCancel: Boolean) {
        // Only on a real change: a write to a snapshot state invalidates
        // every reader even when the value is identical, and `load` is
        // called on every shell start.
        if (swappedState.value != swapConfirmCancel) swappedState.value = swapConfirmCancel
    }

    /**
     * The action [keyCode] means, the swap applied. The swap is a question
     * about the PAD's two face buttons (onboarding asks what is printed on
     * them), so a keyboard's own Enter and Escape are outside it: Enter
     * confirms and Escape cancels whatever a pad's buttons say. DPAD_CENTER
     * stays inside it, because it is what Android re-sends for a pad's
     * unhandled bottom face button (`Generic.kcm`), and the system back key
     * stays back.
     */
    fun actionFor(keyCode: Int, shift: Boolean = false): GamepadAction? {
        val action = physicalAction(keyCode, shift) ?: return null
        val keyboardOnly = keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_ESCAPE
        return if (keyboardOnly) action else applySwap(action)
    }

    /** [actionFor] for a Compose [Key]. */
    fun actionFor(key: Key): GamepadAction? = actionFor(key.nativeKeyCode)

    /**
     * The swap, in ONE place, applied to the meaning rather than to the
     * table: A and B trade what they mean, and every other action is
     * untouched.
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
     * what a press does -- it sends the real key event and every handler
     * treats it as the press it is (see `rememberGamepadTouch`).
     */
    fun keyCodeFor(action: GamepadAction): Int = when (applySwap(action)) {
        GamepadAction.A -> KeyEvent.KEYCODE_BUTTON_A
        GamepadAction.B -> KeyEvent.KEYCODE_BUTTON_B
        GamepadAction.X -> KeyEvent.KEYCODE_BUTTON_X
        GamepadAction.Y -> KeyEvent.KEYCODE_BUTTON_Y
        GamepadAction.START -> KeyEvent.KEYCODE_BUTTON_START
        GamepadAction.SELECT -> KeyEvent.KEYCODE_BUTTON_SELECT
        GamepadAction.UP -> KeyEvent.KEYCODE_DPAD_UP
        GamepadAction.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        GamepadAction.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        GamepadAction.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        GamepadAction.L -> KeyEvent.KEYCODE_BUTTON_L1
        GamepadAction.R -> KeyEvent.KEYCODE_BUTTON_R1
        GamepadAction.L2 -> KeyEvent.KEYCODE_BUTTON_L2
        GamepadAction.R2 -> KeyEvent.KEYCODE_BUTTON_R2
        GamepadAction.L3 -> KeyEvent.KEYCODE_BUTTON_THUMBL
        GamepadAction.R3 -> KeyEvent.KEYCODE_BUTTON_THUMBR
        GamepadAction.BACK -> KeyEvent.KEYCODE_BACK
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
     * The label the hint bar shows for [action]. Like [keyCodeFor] this
     * answers in PHYSICAL terms -- which button to press -- so with the
     * face buttons swapped a hint for "confirm" names the button that now
     * confirms.
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
        // The printed names: the shoulders carry "L1"/"R1" beside
        // "L2"/"R2" on the pads droidtop targets, and a hint reading "R"
        // next to an "R2" chip left which one was meant to the reader.
        GamepadAction.L -> "L1"
        GamepadAction.R -> "R1"
        GamepadAction.L2 -> "L2"
        GamepadAction.R2 -> "R2"
        GamepadAction.L3 -> "L3"
        GamepadAction.R3 -> "R3"
        GamepadAction.BACK -> "B"
    }
}
