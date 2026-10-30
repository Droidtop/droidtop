package dev.droidtop.shell.gamepad

import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import android.text.format.DateUtils
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.droidtop.library.stores.SaveChoice
import dev.droidtop.library.stores.SaveConflict
import dev.droidtop.library.stores.SaveConflictPrompts
import dev.droidtop.library.stores.SaveSide
import dev.droidtop.library.stores.StoreSaves
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.menuStep

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
 * "Cloud saves differ" (a store's cloud) or "Saves differ" (a paired computer,
 * Droidtop/tracker#814: "for saves, nobody 'wins' ... We ask the user"): this
 * device's saves and the other side's, each with its device when known, when it
 * last changed and how many files, the newer one marked. When the replaced side
 * is kept as a copy ([SaveConflict.kept]), the dialog says so. "Decide later" comes
 * first and has the focus, so a mashed A changes nothing (the design
 * language's modal rule, Droidtop/tracker#663); each side is one labelled
 * action in the danger colour that says what it replaces, chosen with one
 * deliberate move and press, no arming. B decides later too.
 */
@Composable
internal fun SaveConflictDialog(gameTitle: String, conflict: SaveConflict, onAnswer: (SaveChoice?) -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableIntStateOf(0) }
    val newerIsLocal = conflict.local.timestampMs >= conflict.cloud.timestampMs
    fun press(index: Int) {
        selected = index
        onAnswer(
            when (index) {
                1 -> SaveChoice.LOCAL
                2 -> SaveChoice.CLOUD
                else -> null
            },
        )
    }
    val noDate = stringResource(R.string.saves_no_date)
    val newerWord = stringResource(R.string.saves_newer)
    fun describe(side: SaveSide, newer: Boolean): String {
        val whenText = if (side.timestampMs > 0) {
            DateUtils.formatDateTime(context, side.timestampMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
        } else {
            noDate
        }
        val files = context.resources.getQuantityString(R.plurals.saves_files, side.files, side.files)
        return listOfNotNull(side.device, whenText, files, newerWord.takeIf { newer }).joinToString(" · ")
    }
    val title = stringResource(if (conflict.computer) R.string.saves_differ else R.string.saves_differ_cloud)
    val local = describe(conflict.local, newerIsLocal)
    val other = describe(conflict.cloud, !newerIsLocal)
    ShellDialog(onDismissRequest = { onAnswer(null) }) {
        GatePadInThisDialog()
        MenuPanel(
            modifier = Modifier.width(LocalShellWindow.current.panelWidth(560.dp)),
            focusLabel = title,
            title = title,
            onPad = { pad ->
                when (pad.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> {
                        val next = menuStep(selected, 3, if (pad.action == GamepadAction.UP) -1 else 1)
                        moveCue(next != selected, pad.repeat)
                        selected = next
                    }
                    GamepadAction.A -> press(selected)
                    GamepadAction.B -> onAnswer(null)
                    else -> Unit
                }
                true
            },
        ) {
            Text(gameTitle, style = TypeRole.supporting, color = MenuTokens.OnSurfaceMuted)
            if (conflict.kept) Text(stringResource(R.string.saves_kept_copy), style = TypeRole.supporting, color = MenuTokens.OnSurfaceMuted)
            // Both sides are one-way, so the way out is the safe choice: first, on its own, focused.
            DialogChoices(
                groups = listOf(
                    listOf(DialogChoice(stringResource(R.string.saves_decide_later), stringResource(R.string.saves_changes_nothing))),
                    listOf(
                        DialogChoice(
                            stringResource(R.string.saves_keep_here),
                            if (conflict.computer) {
                                stringResource(R.string.saves_replaces_computer, conflict.cloudLabel, local)
                            } else {
                                stringResource(R.string.saves_replaces_cloud, conflict.cloudLabel, local)
                            },
                            danger = true,
                        ),
                        DialogChoice(
                            if (conflict.computer) {
                                stringResource(R.string.saves_keep_computer, conflict.cloudLabel)
                            } else {
                                stringResource(R.string.saves_keep_cloud, conflict.cloudLabel)
                            },
                            stringResource(R.string.saves_replaces_here, other),
                            danger = true,
                        ),
                    ),
                ),
                selected = selected,
                onChoose = ::press,
            )
        }
    }
}
