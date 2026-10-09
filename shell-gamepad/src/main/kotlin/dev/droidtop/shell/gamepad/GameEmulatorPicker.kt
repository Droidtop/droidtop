package dev.droidtop.shell.gamepad

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.consoles.GameEmulatorChoice
import dev.droidtop.library.consoles.SystemEmulators
import dev.droidtop.library.consoles.loadSystemEmulators
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.onPad

/**
 * The per-game emulator choice: the third level of the order game, then
 * system, then global default (docs/SPEC.md "Launch resolution: keep the
 * default, expose it"). The stored value is a player id in the game's
 * `altEmulator` field (ES-DE's own per-game field, read by the launch
 * path through `EmulatorResolution`); empty means "follow the system".
 * The options and the summary are [GameEmulatorChoice], the one model the
 * Quick Menu and the companion draw too.
 */

/** The installed emulators for [entry]'s system, loaded off the main thread; null while loading or for a non-console game. */
@Composable
fun rememberSystemEmulators(entry: LibraryEntry): SystemEmulators? {
    val context = LocalContext.current
    var loaded by remember(entry.id) { mutableStateOf<SystemEmulators?>(null) }
    LaunchedEffect(entry.id) {
        loaded = entry.systemId?.let { loadSystemEmulators(context, it) }
    }
    return loaded
}

@Composable
internal fun GameEmulatorPicker(
    entry: LibraryEntry,
    emulators: SystemEmulators,
    choice: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // The system back key; the pad's B is the onPad handler below.
    androidx.activity.compose.BackHandler { onDismiss() }
    val options = GameEmulatorChoice.options(emulators, choice)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .groundBackground()
            .padding(LocalShellWindow.current.edgePadding)
            .onPad { press ->
                if (press.action == GamepadAction.B) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Emulator for this game", color = MenuTokens.OnSurface, style = MaterialTheme.typography.headlineSmall)
        Text(entry.title, color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = MenuTokens.HintBarRoom),
        ) {
            items(options, key = { it.id ?: "" }) { option ->
                EmulatorRow(
                    title = option.label + if (option.current) " (current)" else "",
                    detail = option.detail,
                    onPick = { onPick(option.id) },
                )
            }
        }
    }
}

@Composable
private fun EmulatorRow(title: String, detail: String, onPick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Ahead of the focus targets, not after them: see [GameCard].
            .onPad { press ->
                if (press.action == GamepadAction.A) {
                    onPick()
                    true
                } else {
                    false
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onPick)
            .selectionFrame(focused, RoundedCornerShape(8.dp), rest = Color.Transparent)
            .heightIn(min = 64.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, color = MenuTokens.OnSurface, style = MaterialTheme.typography.titleMedium)
        Text(detail, color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
    }
}
