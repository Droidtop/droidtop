package dev.droidtop.shell.gamepad.input

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
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
 * So the outermost node of a window consumes every pad button nothing
 * below it wanted, and gives the one fallback the shell does mean --
 * B is back -- explicitly, through the same back dispatcher a BACK key
 * reaches. That is the pad's one meaning of B and the touch pill's too:
 * a pill dispatches a real BUTTON_B into the window, and it arrives here
 * exactly as a pad's does. D-pad, keyboard and volume keys are not pad
 * buttons and pass through untouched.
 */
fun Modifier.ownPadButtons(onBack: () -> Unit): Modifier = onKeyEvent { event ->
    if (!KeyEvent.isGamepadButton(event.nativeKeyEvent.keyCode)) return@onKeyEvent false
    if (event.type == KeyEventType.KeyUp && GamepadKeyMap.actionFor(event.key) == GamepadAction.B) onBack()
    true
}

/**
 * A control that the pad, a keyboard and a finger all press the same way,
 * with ONE focus target that can hold the selection in every input mode.
 *
 * Why not `clickable`: in touch mode its focus target refuses focus
 * (`focusableInNonTouchMode`), so a screen that asks for initial focus on
 * a touch-mode device gets none, the first pad press only brings the
 * selection back, and a tapped hint pill dispatches its key into a window
 * with nothing focused, where it goes nowhere (rig, dq-onboard-01: A had
 * to be pressed twice on Welcome, the hint pills did nothing, the
 * tutorial's first A landed on "Skip"). And `clickable` answers Enter and
 * DPAD_CENTER but never BUTTON_A, which a window that owns its pad
 * ([ownPadButtons]) no longer lets Android turn into DPAD_CENTER.
 *
 * So: the key handler first (a key event travels from the focused node up,
 * and [focusable] below is that node), then [onFocus] for the selection
 * ring, then one [focusable], a tap, and button semantics for a screen
 * reader.
 */
fun Modifier.padSelectable(
    onFocus: (Boolean) -> Unit = {},
    onPress: () -> Unit,
): Modifier = this
    .onKeyEvent { event ->
        val key = event.nativeKeyEvent.keyCode
        val confirms = if (KeyEvent.isGamepadButton(key)) {
            GamepadKeyMap.actionFor(event.key) == GamepadAction.A
        } else {
            key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER
        }
        if (confirms && event.type == KeyEventType.KeyUp) onPress()
        confirms
    }
    .onFocusChanged { onFocus(it.isFocused) }
    .focusable()
    .semantics {
        role = Role.Button
        onClick { onPress(); true }
    }
    .pointerInput(onPress) { detectTapGestures(onTap = { onPress() }) }
