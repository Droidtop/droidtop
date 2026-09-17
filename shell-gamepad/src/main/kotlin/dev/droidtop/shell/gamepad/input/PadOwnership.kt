package dev.droidtop.shell.gamepad.input

import android.view.KeyEvent
import androidx.compose.ui.Modifier
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
