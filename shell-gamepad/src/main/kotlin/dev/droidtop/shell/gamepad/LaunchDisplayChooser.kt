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
 * The per-launch display chooser (docs/SPEC.md section 4c): the first
 * launch of a game with two displays present stops here — Up/Down pick,
 * A acts, B backs out and abandons the launch entirely (nothing was
 * started yet). Installed into [dev.droidtop.library.LaunchDisplay.chooser]
 * by GamepadShell.
 *
 * The row set is iiSU's own vocabulary (section 4c): a plain "launch
 * here" pair for this one launch, then an "Always" pair that remembers
 * the choice for THIS GAME so the question never comes back for it —
 * ask-per-launch is the honest default, a remembered per-game answer is
 * the steady state, and clearing lives in the game's metadata editor as
 * a first-class action.
 */
@Composable
internal fun LaunchDisplayChooserDialog(
    options: List<LaunchDisplayOption>,
    /** Whether the launch has a game identity to remember a choice for — no identity, no "Always" rows. */
    canRemember: Boolean,
    onPick: (option: LaunchDisplayOption, remember: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val rows = remember(options, canRemember) {
        options.map { ChooserRow(it, remember = false, label = it.label) } +
            if (canRemember) {
                options.map { ChooserRow(it, remember = true, label = "Always: " + it.label) }
            } else {
                emptyList()
            }
    }
    var selected by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus, "Launch display chooser") }

    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MenuTokens.OverlaySurface)
                .focusRequester(focus)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            selected = (selected + 1).coerceAtMost(rows.lastIndex)
                            true
                        }
                        Key.DirectionUp -> {
                            selected = (selected - 1).coerceAtLeast(0)
                            true
                        }
                        Key.ButtonA, Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                            rows.getOrNull(selected)?.let { onPick(it.option, it.remember) }
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
            Text("Launch on which screen?", color = MenuTokens.OnSurface, style = MaterialTheme.typography.titleMedium)
            Text(
                if (canRemember) "\"Always\" remembers for this game · B cancels the launch" else "B cancels the launch",
                color = MenuTokens.OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            rows.forEachIndexed { index, row ->
                Text(
                    row.label,
                    color = if (index == selected) Color.White else MenuTokens.Value,
                    fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (index == selected) MenuTokens.SurfaceSelected else Color.Transparent)
                        .clickable { onPick(row.option, row.remember) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

private data class ChooserRow(val option: LaunchDisplayOption, val remember: Boolean, val label: String)
