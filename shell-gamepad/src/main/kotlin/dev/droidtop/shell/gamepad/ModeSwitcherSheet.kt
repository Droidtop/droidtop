package dev.droidtop.shell.gamepad

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow

/** One row of the mode switcher: what it says and what choosing it does. */
class ModeSwitcherChoice(val label: String, val choose: () -> Unit)

/**
 * The mode switcher (docs/SPEC.md 2c, "Switching modes is named on every surface") as the shell's own
 * modal sheet: [MenuPanel]'s panel, dim, glide and title, its rows the shell's rows, in the theme's
 * type in Gaming and droidtop's own elsewhere. It replaced a platform AlertDialog in the system face
 * (rig, build 1535). The first row holds the cursor from the start, so one A chooses it; Up/Down move,
 * B closes, and the hint row under the rows is the touch route to the same presses, since the window
 * hosting this has no shell footer of its own.
 */
@Composable
fun ModeSwitcherSheet(choices: List<ModeSwitcherChoice>, onDismiss: () -> Unit) {
    CompositionLocalProvider(LocalShellWindow provides currentShellWindow()) {
        var focus by remember { mutableIntStateOf(0) }
        fun choose(index: Int) {
            val choice = choices.getOrNull(index) ?: return
            choice.choose()
            onDismiss()
        }
        Dialog(onDismissRequest = onDismiss) {
            MenuPanel(
                modifier = Modifier.width(LocalShellWindow.current.panelWidth(420.dp)),
                focusLabel = "Switch mode",
                title = "Switch mode",
                // No shell footer behind this window: the panel draws its own hint row below.
                hints = emptyList(),
                onPad = { press ->
                    when (press.action) {
                        GamepadAction.UP, GamepadAction.DOWN -> {
                            focus = menuMove(focus, choices.size, press)
                            true
                        }
                        GamepadAction.A -> {
                            choose(focus)
                            true
                        }
                        GamepadAction.B -> {
                            onDismiss()
                            true
                        }
                        else -> false
                    }
                },
            ) {
                choices.forEachIndexed { index, choice ->
                    MenuRow(
                        title = choice.label,
                        selected = index == focus,
                        onClick = {
                            focus = index
                            choose(index)
                        },
                    )
                }
                HintRow(
                    bindings = listOf(HintBinding(GamepadAction.A, "Select"), HintBinding(GamepadAction.B, "Cancel")),
                    background = androidx.compose.ui.graphics.Color.Transparent,
                )
            }
        }
    }
}
