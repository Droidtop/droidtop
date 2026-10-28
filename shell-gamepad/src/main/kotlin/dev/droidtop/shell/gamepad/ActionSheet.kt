package dev.droidtop.shell.gamepad

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding

/**
 * One row of an [ActionSheet]: what it is called, a line under it when the name needs one, and what A on it does.
 * [danger] draws a row that loses something in the danger look (docs/SPEC.md 7k).
 */
internal class ActionRow(
    val title: String,
    val detail: String? = null,
    val danger: Boolean = false,
    val onSelect: () -> Unit,
)

/**
 * The one sheet of choices for a thing (Droidtop/tracker#610: "a shared ActionSheet and row model"): [title], then
 * [rows], the first focused. Up and Down move, A chooses, and B, Select or L2 closes it, L2 because L2 opens the
 * focused thing's menu (docs/SPEC.md 7j). It is a window of its own that holds the pad while open ([MenuPanel]), and
 * every row is a touch target. A choice inside a row opens a page or a task, never a second sheet, so nothing built on
 * this is deeper than one level.
 */
@Composable
internal fun ActionSheet(
    title: String,
    rows: List<ActionRow>,
    onDismiss: () -> Unit,
) {
    var focusIndex by remember(rows.size) { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss) {
        MenuPanel(
            modifier = Modifier.width(LocalShellWindow.current.panelWidth(480.dp)),
            focusLabel = title,
            title = title,
            hints = ACTION_SHEET_HINTS,
            onPad = { press ->
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> focusIndex = menuMove(focusIndex, rows.size, press)
                    GamepadAction.A -> rows.getOrNull(focusIndex)?.onSelect?.invoke()
                    GamepadAction.B, GamepadAction.SELECT, GamepadAction.L2 -> onDismiss()
                    else -> Unit
                }
                true
            },
        ) {
            rows.forEachIndexed { index, row ->
                MenuRow(
                    title = row.title,
                    subtitle = row.detail,
                    danger = row.danger,
                    selected = index == focusIndex,
                    onClick = row.onSelect,
                )
            }
        }
    }
}

private val ACTION_SHEET_HINTS = listOf(HintBinding(GamepadAction.A, "Choose"), HintBinding(GamepadAction.B, "Close"))
