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
 * Each choice says what it does: [keep] names the safe one ("Keep downloading"), and the two never read the same
 * ([safeChoice]; the owner on the console: a download's cancel asked with two buttons both called "Cancel").
 */
@Composable
fun ConfirmDialog(
    question: String,
    action: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    detail: String? = null,
    keep: String? = null,
) {
    val safe = safeChoice(keep, action)
    val groups = remember(action, safe) { listOf(listOf(DialogChoice(safe)), listOf(DialogChoice(action, danger = true))) }
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

/**
 * The safe choice's name beside [action]: [keep] when given, else "Cancel", and never the same words as [action]
 * (an action itself called Cancel gets "Keep it"). Pure.
 */
fun safeChoice(keep: String?, action: String): String {
    val chosen = keep?.takeIf { it.isNotBlank() } ?: CANCEL
    return if (chosen.equals(action.trim(), ignoreCase = true)) KEEP_IT else chosen
}

private const val KEEP_IT = "Keep it"

/** A row's action waiting for its [ConfirmDialog]: what it asks, its action's name, and what it does. */
class PendingConfirm(val question: String, val action: String, val run: () -> Unit)

private const val CANCEL = "Cancel"
