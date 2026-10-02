package dev.droidtop.shell.gamepad

import dev.droidtop.shell.gamepad.input.GamepadAction
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * The Gaming shell's launch-failure dialog (Droidtop/tracker#171): a
 * failed launch is a focused moment -- [LaunchFailureMessage]'s plain
 * sentence and a clear next step -- not raw exception text on a black
 * screen the pad's B cannot dismiss. The shell stays visible behind it;
 * the pad's B, a row tap, or a tap outside dismisses it. "Get an
 * emulator" is offered only when the caller knows the fix exists
 * ([onGetEmulator] non-null: the cause named a system with no installed
 * emulator), otherwise the dialog's one row is OK.
 *
 * Built on [MenuPanel] like every other shell dialog (docs/SPEC.md 6e):
 * the dialog takes focus and the pipeline's front, and the pad's presses
 * are handled there.
 */
@Composable
internal fun LaunchFailureDialog(
    /** The plain sentence [LaunchFailureMessage] mapped the cause to. */
    message: String,
    /** The missing-emulator fix, or null when there is none to offer. */
    onGetEmulator: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val rows = remember(onGetEmulator) {
        if (onGetEmulator != null) listOf("Get an emulator", "OK") else listOf("OK")
    }
    var selected by remember { mutableIntStateOf(0) }

    val window = LocalShellWindow.current
    Dialog(onDismissRequest = onDismiss) {
        // The shell's one modal panel: focus, the pipeline's front for this
        // dialog and the pad's presses are handled there (docs/SPEC.md 6e).
        MenuPanel(
            modifier = Modifier.width(window.panelWidth(400.dp)),
            focusLabel = "Launch failure",
            onPad = { press ->
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> selected = menuMove(selected, rows.size, press)
                    GamepadAction.A -> if (selected == 0 && onGetEmulator != null) onGetEmulator() else onDismiss()
                    GamepadAction.B -> onDismiss()
                    else -> Unit
                }
                true
            },
        ) {
            Text(
                message,
                color = MenuTokens.OnSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "A confirms · B closes",
                color = MenuTokens.OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            rows.forEachIndexed { index, row ->
                Text(
                    row,
                    color = if (index == selected) MenuTokens.OnSurface else MenuTokens.Value,
                    fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        // A row is a button here, so it is at least as
                        // big as a finger on a screen without a pad.
                        .then(if (window.touchFirst) Modifier.heightIn(min = window.minTouchTarget) else Modifier)
                        .clip(RoundedCornerShape(8.dp))
                        .selectionFrame(index == selected, RoundedCornerShape(8.dp), rest = Color.Transparent)
                        .clickable { if (index == 0 && onGetEmulator != null) onGetEmulator() else onDismiss() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}
