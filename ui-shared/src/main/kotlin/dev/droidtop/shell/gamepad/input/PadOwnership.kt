package dev.droidtop.shell.gamepad.input

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/**
 * THE SHELL OWNS THE PAD (docs/SPEC.md 6e, 7j).
 *
 * Android's `Generic.kcm` gives every pad button a fallback key -- A, Start
 * and the thumb clicks become DPAD_CENTER, B becomes BACK, X becomes DEL,
 * Y becomes SPACE, Select becomes MENU -- and dispatches the fallback
 * whenever the window leaves the button's press unhandled. The outermost
 * node of a window gives B its one meaning, [onBack] (the back dispatcher),
 * and takes X, Y and Select whatever nothing below wanted, so their
 * fallbacks never type a DEL or a space into a text field or open a menu
 * nobody asked for. A is left alone: a focused control that does not take
 * the pad's A itself still answers its DPAD_CENTER, as before.
 *
 * Through [onPad], so B acts on the press and its release belongs to it: a
 * B that closed a screen below can no longer go back a second level here on
 * its release.
 */
fun Modifier.ownPadButtons(onBack: () -> Unit): Modifier = onPad { press ->
    when (press.action) {
        GamepadAction.B -> {
            onBack()
            true
        }
        GamepadAction.X, GamepadAction.Y, GamepadAction.SELECT -> true
        else -> false
    }
}

/**
 * A control that the pad, a keyboard and a finger all press the same way,
 * with ONE focus target that can hold the selection in every input mode.
 *
 * Why not `clickable`: in touch mode its focus target refuses focus
 * (`focusableInNonTouchMode`), so a screen that asks for initial focus on
 * a touch-mode device gets none, the first pad press only brings the
 * selection back. And `clickable` answers Enter and DPAD_CENTER but never
 * BUTTON_A. A tapped hint pill reaches the focused element exactly like a
 * pad press (rig, dq-onboard-01: A had to be pressed twice on Welcome, the
 * hint pills did nothing, the first A landed on "Skip" -- fixed).
 *
 * So: the key handler first (a key event travels from the focused node up,
 * and [focusable] below is that node), then [onFocus] for the selection
 * ring, then one [focusable], a tap, and button semantics for a screen
 * reader.
 */
fun Modifier.padSelectable(
    onFocus: (Boolean) -> Unit = {},
    onPress: () -> Unit,
): Modifier = composed {
    // The tap handler is keyed on nothing and reads the CURRENT onPress:
    // screens pass a fresh lambda on every recomposition, and keying the
    // pointer input on it restarted the gesture detector on each one, which
    // cancelled a tap whose DOWN had already landed. Onboarding
    // recomposes right as a step appears (focus retries, state
    // reads), so a first tap on a secondary button ("Continue without it")
    // was dropped and the same tap a moment later
    // worked (Droidtop/tracker#42).
    val currentPress by rememberUpdatedState(onPress)
    val currentFocus by rememberUpdatedState(onFocus)
    this
    // A -- the pad's, Enter, DPAD_CENTER -- presses it, on the press
    // (docs/SPEC.md 6e).
    .onPad { press ->
        if (press.action == GamepadAction.A) {
            currentPress()
            true
        } else {
            false
        }
    }
    .onFocusChanged { currentFocus(it.isFocused) }
    .focusable()
    .semantics {
        role = Role.Button
        onClick { currentPress(); true }
    }
    .pointerInput(Unit) { detectTapGestures(onTap = { currentPress() }) }
}
