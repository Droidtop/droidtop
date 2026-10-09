package dev.droidtop.shell.gamepad

import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.GamepadAction
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * The Gaming shell's launch-failure dialog (Droidtop/tracker#171): a
 * failed launch is a focused moment -- [LaunchFailureMessage]'s plain
 * sentence and a clear next step -- not raw exception text on a black
 * screen the pad's B cannot dismiss. The shell stays visible behind it;
 * the pad's B, a row tap, or a tap outside dismisses it. The fixes the
 * caller knows ([actions]: "Get an emulator" for a missing one, "Close it"
 * and "Return to droidtop" for the launch watchdog's alert) come first, and
 * the last row is always OK. [detail] is a muted line under the sentence.
 *
 * Built on [MenuPanel] like every other shell dialog (docs/SPEC.md 6e):
 * the dialog takes focus and the pipeline's front, and the pad's presses
 * are handled there.
 */
@Composable
internal fun LaunchFailureDialog(
    /** The plain sentence [LaunchFailureMessage] mapped the cause to. */
    message: String,
    /** The fixes on offer, in row order; empty when there is none. */
    actions: List<LaunchFailureAction>,
    onDismiss: () -> Unit,
    /** One muted line under [message], such as where the log is. */
    detail: String? = null,
) {
    val groups = remember(actions) { listOf(actions.map { DialogChoice(it.label) }, listOf(DialogChoice("OK"))) }
    val count = actions.size + 1
    val choose: (Int) -> Unit = { index -> if (index < actions.size) actions[index].run() else onDismiss() }
    var selected by remember { mutableIntStateOf(0) }

    val window = LocalShellWindow.current
    Dialog(onDismissRequest = onDismiss) {
        GatePadInThisDialog()
        // The shell's one modal panel: focus, the pipeline's front for this
        // dialog and the pad's presses are handled there (docs/SPEC.md 6e).
        MenuPanel(
            modifier = Modifier.width(window.panelWidth(400.dp)),
            focusLabel = "Launch failure",
            onPad = { press ->
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> selected = menuMove(selected, count, press)
                    GamepadAction.A -> choose(selected)
                    GamepadAction.B -> onDismiss()
                    else -> Unit
                }
                true
            },
        ) {
            Text(message, color = MenuTokens.OnSurface, style = TypeRole.rowTitle)
            if (detail != null) {
                Text(detail, color = MenuTokens.OnSurfaceMuted, style = TypeRole.supporting)
            }
            // The fixes first, OK last on its own (Steam's dialog order).
            DialogChoices(groups, selected) { index ->
                selected = index
                choose(index)
            }
        }
    }
}

/** One fix a [LaunchFailureDialog] offers: its row's label and what choosing it does. */
internal class LaunchFailureAction(val label: String, val run: () -> Unit)
