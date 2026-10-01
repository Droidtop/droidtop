package dev.droidtop.shell.gamepad.input

import android.view.KeyEvent
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * THE SHELL OWNS THE PAD (docs/SPEC.md 7j).
 *
 * Android's `Generic.kcm` gives every pad button a fallback key -- A, Start
 * and the thumb clicks become DPAD_CENTER, B becomes BACK, X becomes DEL,
 * Y becomes SPACE, Select becomes MENU -- and the input pipeline dispatches
 * that fallback, on both edges, whenever the window leaves the button
 * unhandled. Every screen here acts on the UP edge and leaves the DOWN
 * edge alone, so on a real pad the DOWN of A was falling back to a
 * DPAD_CENTER pair that landed on whatever the A had just opened: A on a
 * PC card opened the game's detail AND pressed its primary button, which
 * for an engine game with no plugin is "get the plugin" (rig, build 552,
 * Enginehost's plugin screen in front of a detail nobody asked to leave).
 * X and Y were reaching text fields as DEL and SPACE.
 *
 * The outermost node consumes only the one fallback the shell means: B is
 * back. Other gamepad buttons are not consumed here -- they either reach
 * the focused element (which handles A via [padSelectable]) or fall
 * through to Android's default handling, exactly like a physical pad press.
 * A touch pill dispatches a real BUTTON_B/BUTTON_A into the window, and it
 * arrives here exactly as a pad's does.
 */
fun Modifier.ownPadButtons(onBack: () -> Unit): Modifier = onKeyEvent { event ->
    if (!KeyEvent.isGamepadButton(event.nativeKeyEvent.keyCode)) return@onKeyEvent false
    if (event.type == KeyEventType.KeyUp && GamepadKeyMap.actionFor(event.key) == GamepadAction.B) {
        onBack()
        true
    } else {
        false
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
 * BUTTON_A. With `ownPadButtons` no longer consuming A, a tapped hint pill
 * now reaches the focused element exactly like a pad press (rig,
 * dq-onboard-01: A had to be pressed twice on Welcome, the hint pills did
 * nothing, the first A landed on "Skip" — fixed).
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
    .onKeyEvent { event ->
        val key = event.nativeKeyEvent.keyCode
        val confirms = if (KeyEvent.isGamepadButton(key)) {
            GamepadKeyMap.actionFor(event.key) == GamepadAction.A
        } else {
            key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER
        }
        if (confirms && event.type == KeyEventType.KeyUp) currentPress()
        confirms
    }
    .onFocusChanged { currentFocus(it.isFocused) }
    .focusable()
    .semantics {
        role = Role.Button
        onClick { currentPress(); true }
    }
    .pointerInput(Unit) { detectTapGestures(onTap = { currentPress() }) }
}
