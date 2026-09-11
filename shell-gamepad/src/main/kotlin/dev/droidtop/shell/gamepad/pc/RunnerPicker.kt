package dev.droidtop.shell.gamepad.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.droidtop.library.GameEngine
import dev.droidtop.library.GameLaunchStrategy
import dev.droidtop.library.RunnerOption
import dev.droidtop.library.RunnerState
import dev.droidtop.library.displayName
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap

/**
 * Every runner for one game, in its state — docs/SPEC.md §7i's picker.
 *
 * Three of the four states are on the page. Ready and Needs-setup rows
 * are selectable and carry their one action. "Not on this device" rows
 * are shown dimmed and unselectable, because somebody who does not know
 * an option exists cannot decide about it. "Not for this game" rows sit
 * behind the "why not" line at the bottom, because they are facts about
 * the game rather than choices, and a column of them would bury the real
 * decision.
 *
 * Picking clears back to the stated default as its own row, so an
 * override is never a one-way door.
 */
@Composable
internal fun RunnerPicker(
    options: List<RunnerOption>,
    engine: GameEngine?,
    current: GameLaunchStrategy?,
    overridden: Boolean,
    onPick: (GameLaunchStrategy?) -> Unit,
    onDismiss: () -> Unit,
) {
    var showWhyNot by remember { mutableStateOf(false) }
    val offered = options.filter { it.state != RunnerState.NOT_FOR_THIS_GAME }
    val hidden = options.filter { it.state == RunnerState.NOT_FOR_THIS_GAME }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 48.dp, vertical = 32.dp)
            .onKeyEvent { event ->
                val action = GamepadKeyMap.actionFor(event.key)
                if (event.type == KeyEventType.KeyUp && (action == GamepadAction.BACK || action == GamepadAction.B)) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Runs with", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (overridden) {
                item {
                    RunnerRow(
                        title = "Use droidtop's default again",
                        detail = "Clears your choice for this game",
                        enabled = true,
                        selected = false,
                        onSelect = { onPick(null) },
                    )
                }
            }
            items(offered, key = { it.strategy.name }) { option ->
                RunnerRow(
                    title = option.strategy.displayName(engine),
                    detail = listOfNotNull(
                        option.reason,
                        option.caveat,
                        if (option.state == RunnerState.NEEDS_SETUP && option.reason == null) "Needs setup" else null,
                    ).joinToString(" - ").ifBlank { "Ready" },
                    enabled = option.selectable,
                    selected = option.strategy == current,
                    onSelect = { if (option.selectable) onPick(option.strategy) },
                )
            }
            if (hidden.isNotEmpty()) {
                item {
                    RunnerRow(
                        title = if (showWhyNot) "Hide why the rest don't apply" else "Why not the others?",
                        detail = "${hidden.size} runners this game doesn't offer",
                        enabled = true,
                        selected = false,
                        onSelect = { showWhyNot = !showWhyNot },
                    )
                }
                if (showWhyNot) {
                    items(hidden, key = { "why:" + it.strategy.name }) { option ->
                        Text(
                            "${option.strategy.displayName(engine)} - ${option.reason.orEmpty()}",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RunnerRow(
    title: String,
    detail: String,
    enabled: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled = enabled)
            .then(if (enabled) Modifier.clickable(onClick = onSelect) else Modifier)
            .onKeyEvent { event ->
                if (enabled && event.type == KeyEventType.KeyUp &&
                    GamepadKeyMap.actionFor(event.key) == GamepadAction.A
                ) {
                    onSelect()
                    true
                } else {
                    false
                }
            }
            .background(if (focused) Color(0xFF2A2A2A) else Color(0xFF141414), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            title + if (selected) "  (current)" else "",
            // Dimmed rather than absent: this is the "not on this device"
            // row the user is entitled to know about.
            color = if (enabled) Color.White else Color(0xFF7A7A7A),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(detail, color = if (enabled) Color.LightGray else Color(0xFF5F5F5F), style = MaterialTheme.typography.bodySmall)
    }
}
