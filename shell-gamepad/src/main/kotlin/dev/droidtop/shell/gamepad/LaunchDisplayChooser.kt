package dev.droidtop.shell.gamepad

import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.GamepadAction



import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
 * a first-class action. Then an "Every game" pair (Droidtop/tracker#590): on a console with a dock the answer is the
 * same for every game, so one press sets Settings > Displays > Games launch on and the question stops for all of them.
 */
@Composable
internal fun LaunchDisplayChooserDialog(
    options: List<LaunchDisplayOption>,
    /** Whether the launch has a game identity to remember a choice for — no identity, no "Always" rows. */
    canRemember: Boolean,
    onPick: (option: LaunchDisplayOption, remember: Boolean) -> Unit,
    /** The "Every game" rows: the shell stores [option] as every game's screen, then launches on it. */
    onPickEveryGame: (option: LaunchDisplayOption) -> Unit,
    onCancel: () -> Unit,
) {
    val rows = remember(options, canRemember) {
        val rememberable = options.filter { it.rememberable }
        options.map { ChooserRow(it, Scope.ONCE, label = it.label) } +
            (if (canRemember) rememberable.map { ChooserRow(it, Scope.GAME, label = "Always: " + it.label) } else emptyList()) +
            rememberable.map { ChooserRow(it, Scope.EVERY_GAME, label = "Every game: " + it.label) }
    }
    // Steam's dialog order: the screens, then the remembered choices, then Cancel last on its own.
    val groups = remember(rows) {
        Scope.entries.map { scope -> rows.filter { it.scope == scope }.map { DialogChoice(it.label) } }.filter { it.isNotEmpty() } +
            listOf(listOf(DialogChoice("Cancel")))
    }
    val count = rows.size + 1
    val choose: (Int) -> Unit = { index ->
        val row = rows.getOrNull(index)
        when (row?.scope) {
            null -> onCancel()
            Scope.EVERY_GAME -> onPickEveryGame(row.option)
            else -> onPick(row.option, row.scope == Scope.GAME)
        }
    }
    var selected by remember { mutableStateOf(0) }

    val window = LocalShellWindow.current
    Dialog(onDismissRequest = onCancel) {
        GatePadInThisDialog()
        // The shell's one modal panel: focus, the pipeline's front for this
        // dialog and the pad's presses are handled there (docs/SPEC.md 6e).
        MenuPanel(
            modifier = Modifier.width(window.panelWidth(400.dp)),
            focusLabel = "Launch display chooser",
            title = "Launch on which screen?",
            onPad = { press ->
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> selected = menuMove(selected, count, press)
                    GamepadAction.A -> choose(selected)
                    GamepadAction.B -> onCancel()
                    else -> Unit
                }
                true
            },
        ) {
            Text(
                (if (canRemember) "\"Always\" remembers for this game. " else "") +
                    "\"Every game\" changes Settings > Displays > Games launch on",
                color = MenuTokens.OnSurfaceMuted,
                style = TypeRole.supporting,
            )
            DialogChoices(groups, selected) { index ->
                selected = index
                choose(index)
            }
        }
    }
}

/** How far a pick reaches: this launch, this game ("Always"), or every game. */
private enum class Scope { ONCE, GAME, EVERY_GAME }

private data class ChooserRow(val option: LaunchDisplayOption, val scope: Scope, val label: String)
