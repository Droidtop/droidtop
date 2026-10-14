package dev.droidtop.shell.gamepad

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
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog

/**
 * "Your theme is ready: <name>" with "Use it" and "Later" (docs/SPEC.md 7f, Droidtop/tracker#532): raised by
 * [dev.droidtop.library.theme.ThemeOffer.pending] when a theme finished downloading while nobody was choosing one, so
 * the look of the shell never changes under the person. B is Later. Same panel and pad handling as every modal here.
 */
@Composable
internal fun ThemeReadyDialog(name: String, onUse: () -> Unit, onLater: () -> Unit) {
    val rows = remember { listOf("Use it", "Later") }
    var selected by remember { mutableIntStateOf(0) }
    val window = LocalShellWindow.current
    Dialog(onDismissRequest = onLater) {
        GatePadInThisDialog()
        MenuPanel(
            modifier = Modifier.width(window.panelWidth(400.dp)),
            focusLabel = "Theme ready",
            onPad = { press ->
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> selected = menuMove(selected, rows.size, press)
                    GamepadAction.A -> if (selected == 0) onUse() else onLater()
                    GamepadAction.B -> onLater()
                    else -> Unit
                }
                true
            },
        ) {
            Text("Your theme is ready: $name", color = MenuTokens.OnSurface, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
            rows.forEachIndexed { index, row ->
                Text(
                    row,
                    color = if (index == selected) MenuTokens.OnSurface else MenuTokens.Value,
                    fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (window.touchFirst) Modifier.heightIn(min = window.minTouchTarget) else Modifier)
                        .clip(RoundedCornerShape(8.dp))
                        .selectionFrame(index == selected, RoundedCornerShape(8.dp), rest = Color.Transparent)
                        .clickable { if (index == 0) onUse() else onLater() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}
