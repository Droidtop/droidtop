package dev.droidtop.shell.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.library.LaunchDisplayOption

/**
 * The per-launch display chooser (docs/SPEC.md §4): with two displays and
 * the default "ask every time" launch target, every game/app launch stops
 * here first -- Up/Down pick, A launches on that display, B backs out and
 * abandons the launch entirely (nothing was started yet). Installed into
 * [dev.droidtop.library.LaunchDisplay.chooser] by GamepadShell.
 */
@Composable
internal fun LaunchDisplayChooserDialog(
    options: List<LaunchDisplayOption>,
    onPick: (Int?) -> Unit,
    onCancel: () -> Unit,
) {
    var selected by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus, "Launch display chooser") }

    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1A1A1A))
                .focusRequester(focus)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            selected = (selected + 1).coerceAtMost(options.lastIndex)
                            true
                        }
                        Key.DirectionUp -> {
                            selected = (selected - 1).coerceAtLeast(0)
                            true
                        }
                        Key.ButtonA, Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                            options.getOrNull(selected)?.let { onPick(it.displayId) }
                            true
                        }
                        Key.ButtonB, Key.Back, Key.Escape -> {
                            onCancel()
                            true
                        }
                        else -> false
                    }
                }
                .padding(20.dp),
        ) {
            Text("Launch on which display?", color = Color(0xFFEDEDED), style = MaterialTheme.typography.titleMedium)
            Text(
                "B cancels the launch",
                color = Color(0xFF9AA4B2),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            options.forEachIndexed { index, option ->
                Text(
                    option.label,
                    color = if (index == selected) Color.White else Color(0xFFB9C2CE),
                    fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (index == selected) Color(0x33FFFFFF) else Color.Transparent)
                        .clickable { onPick(option.displayId) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}
