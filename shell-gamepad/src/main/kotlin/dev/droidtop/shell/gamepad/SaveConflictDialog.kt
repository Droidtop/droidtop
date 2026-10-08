package dev.droidtop.shell.gamepad

import android.text.format.DateUtils
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.library.stores.SaveChoice
import dev.droidtop.library.stores.SaveConflict
import dev.droidtop.library.stores.SaveConflictPrompts
import dev.droidtop.library.stores.SaveSide
import dev.droidtop.library.stores.StoreSaves
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.menuStep
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds

/**
 * Hosts the question a store's cloud-save sync asks when a game's saves
 * changed on this device and in the store's cloud since they last matched
 * (docs/SPEC.md 7g, "Stores", Droidtop/tracker#313). While the Gaming shell is
 * on screen it is the dialog; a launch from another shell finds nobody
 * registered and keeps both sides as they are.
 */
@Composable
internal fun SaveConflictHost() {
    DisposableEffect(Unit) {
        StoreSaves.resolver = SaveConflictPrompts
        onDispose { if (StoreSaves.resolver === SaveConflictPrompts) StoreSaves.resolver = null }
    }
    val prompt by SaveConflictPrompts.pending.collectAsState()
    prompt?.let { SaveConflictDialog(it.title, it.conflict, onAnswer = { choice -> it.settle(choice) }) }
}

/**
 * "Cloud saves differ": this device's saves and the cloud's, each with when it
 * last changed and how many files, the newer one marked. Picking a side
 * replaces the other, so it asks twice (the design language's two-step
 * confirm: A arms and the row says what it does, A again confirms, moving
 * disarms). B decides later and changes nothing.
 */
@Composable
internal fun SaveConflictDialog(gameTitle: String, conflict: SaveConflict, onAnswer: (SaveChoice?) -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableIntStateOf(0) }
    var armed by remember { mutableStateOf<Int?>(null) }
    val newerIsLocal = conflict.local.timestampMs >= conflict.cloud.timestampMs
    fun press(index: Int) {
        selected = index
        if (index == 2) {
            onAnswer(null)
        } else if (armed != index) {
            armed = index
        } else {
            onAnswer(if (index == 0) SaveChoice.LOCAL else SaveChoice.CLOUD)
        }
    }
    fun describe(side: SaveSide, newer: Boolean): String {
        val whenText = if (side.timestampMs > 0) {
            DateUtils.formatDateTime(context, side.timestampMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
        } else {
            "No date"
        }
        val files = "${side.files} ${if (side.files == 1) "file" else "files"}"
        return listOfNotNull(whenText, files, "Newer".takeIf { newer }).joinToString(" · ")
    }
    Dialog(onDismissRequest = { onAnswer(null) }) {
        MenuPanel(
            modifier = Modifier.width(LocalShellWindow.current.panelWidth(560.dp)),
            focusLabel = "Cloud saves differ",
            onPad = { pad ->
                when (pad.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> {
                        val next = menuStep(selected, 3, if (pad.action == GamepadAction.UP) -1 else 1)
                        if (next != selected) {
                            selected = next
                            armed = null
                            EsDeNavigationSounds.play("scroll")
                        }
                    }
                    GamepadAction.A -> press(selected)
                    GamepadAction.B -> onAnswer(null)
                    else -> Unit
                }
                true
            },
        ) {
            Text("Cloud saves differ", style = MaterialTheme.typography.titleMedium, color = MenuTokens.OnSurface)
            Text(gameTitle, style = MaterialTheme.typography.bodySmall, color = MenuTokens.OnSurfaceMuted)
            MenuRow(
                title = "This device",
                subtitle = if (armed == 0) "Replaces the saves in ${conflict.cloudLabel}" else describe(conflict.local, newerIsLocal),
                selected = selected == 0,
                onClick = { press(0) },
            )
            MenuRow(
                title = conflict.cloudLabel,
                subtitle = if (armed == 1) "Replaces the saves on this device" else describe(conflict.cloud, !newerIsLocal),
                selected = selected == 1,
                onClick = { press(1) },
            )
            MenuRow(title = "Decide later", selected = selected == 2, onClick = { press(2) })
            MenuHint("Up/Down moves, A picks and A again confirms, B decides later")
        }
    }
}
