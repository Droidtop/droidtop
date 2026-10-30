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

/**
 * The shell's one confirmation (Droidtop/tracker#835; the owner: "if I hit something like "quit", I can't back out of
 * it, all I can do is confirm it"). [question] over two choices: Cancel first and under the cursor, then [action] in the
 * danger tone. A on Cancel, B, or Back cancel ([onCancel]); only A on [action] runs [onConfirm]. It replaced every
 * "press A again" row, where a first A armed the row and only a second one, or leaving the whole menu, ended it.
 */
@Composable
fun ConfirmDialog(question: String, action: String, onConfirm: () -> Unit, onCancel: () -> Unit, detail: String? = null) {
    val groups = remember(action) { listOf(listOf(DialogChoice(CANCEL)), listOf(DialogChoice(action, danger = true))) }
    var selected by remember { mutableIntStateOf(0) }
    val choose: (Int) -> Unit = { index -> if (index == 1) onConfirm() else onCancel() }
    val window = LocalShellWindow.current
    ShellDialog(onDismissRequest = onCancel) {
        GatePadInThisDialog()
        MenuPanel(
            modifier = Modifier.width(window.panelWidth(400.dp)),
            focusLabel = "Confirm",
            onPad = { press ->
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> selected = menuMove(selected, 2, press)
                    GamepadAction.A -> choose(selected)
                    GamepadAction.B -> onCancel()
                    else -> Unit
                }
                true
            },
        ) {
            Text(question, color = MenuTokens.OnSurface, style = TypeRole.rowTitle)
            if (detail != null) Text(detail, color = MenuTokens.OnSurfaceMuted, style = TypeRole.supporting)
            DialogChoices(groups, selected) { index ->
                selected = index
                choose(index)
            }
        }
    }
}

/** A row's action waiting for its [ConfirmDialog]: what it asks, its action's name, and what it does. */
class PendingConfirm(val question: String, val action: String, val run: () -> Unit)

private const val CANCEL = "Cancel"
