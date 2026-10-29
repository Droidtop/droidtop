package dev.droidtop.shell.gamepad

import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.HideSystemBarsInThisDialog
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import dev.droidtop.library.settings.CatalogIcon
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

/**
 * One row of the mode switcher: what it says and what choosing it does. [icon] is a mode's glyph; [current] marks the
 * mode in use; [apart] is a row that is not a mode (a maintenance action), drawn below the modes under its own heading
 * (Droidtop/tracker#530).
 */
class ModeSwitcherChoice(
    val label: String,
    val icon: CatalogIcon? = null,
    val current: Boolean = false,
    val apart: Boolean = false,
    val choose: () -> Unit,
)

/**
 * The mode switcher (docs/SPEC.md 2c, "Switching modes is named on every surface") as the shell's own
 * modal sheet: [MenuPanel]'s panel, dim, glide and title, its rows the shell's rows, in the theme's
 * type in Gaming and droidtop's own elsewhere. It replaced a platform AlertDialog in the system face
 * (rig, build 1535). The first row holds the cursor from the start, so one A chooses it; Up/Down move,
 * B closes, and the hint row under the rows is the touch route to the same presses, since the window
 * hosting this has no shell footer of its own. [title] lets the same sheet ask a question ("Make droidtop your Home
 * app...") with its answers as the rows. [hideSystemBars] is for a sheet over a shell that hides Android's bars
 * (Gaming, Desktop): its window hides them too, so it draws as the shell's own sheet rather than a separate window
 * under Android's status bar (Droidtop/tracker#750, console 2026-10-29).
 */
@Composable
fun ModeSwitcherSheet(
    choices: List<ModeSwitcherChoice>,
    onDismiss: () -> Unit,
    title: String = "Switch mode",
    hideSystemBars: Boolean = false,
) {
    CompositionLocalProvider(LocalShellWindow provides currentShellWindow()) {
        var focus by remember { mutableIntStateOf(0) }
        fun choose(index: Int) {
            val choice = choices.getOrNull(index) ?: return
            choice.choose()
            onDismiss()
        }
        Dialog(onDismissRequest = onDismiss) {
            GatePadInThisDialog()
            if (hideSystemBars) HideSystemBarsInThisDialog()
            MenuPanel(
                modifier = Modifier.width(LocalShellWindow.current.panelWidth(420.dp)),
                focusLabel = title,
                title = title,
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
                    // The rows that are not modes come after the modes, under their own quiet heading, so a press
                    // meant for a mode does not land on "Close all apps".
                    if (choice.apart && choices.getOrNull(index - 1)?.apart == false) {
                        Text(
                            "Also here".labelCase(),
                            color = MenuTokens.OnSurfaceMuted,
                            style = TypeRole.eyebrow,
                            modifier = Modifier.padding(start = Space.Lg, top = Space.Md, bottom = Space.Xs),
                        )
                    }
                    MenuRow(
                        title = choice.label,
                        value = if (choice.current) "Current" else null,
                        icon = choice.icon,
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
