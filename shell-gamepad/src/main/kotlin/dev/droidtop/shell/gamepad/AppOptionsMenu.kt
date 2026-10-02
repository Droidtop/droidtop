package dev.droidtop.shell.gamepad

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.library.LibraryEntry
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding

/**
 * Select on an app: the focused app's own menu (docs/SPEC.md 7j, "Filters,
 * sort and the hint bar"). Its details, its favourite, and Mark as game
 * (the Apps view's Category filter seeds Games from Android's own flag and
 * droidtop's lists; this is the person's override, also on a long press).
 * Controller-first like every menu here: Up/Down moves, A chooses, B closes,
 * and every row is a touch target.
 */
@Composable
internal fun AppOptionsMenu(
    entry: LibraryEntry,
    onDetails: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit,
    isGame: Boolean = false,
    // Null where the caller has no category to mark (Home's mixed shelves).
    onMarkGame: (() -> Unit)? = null,
    detailsLabel: String = "App details",
) {
    class Option(val title: String, val subtitle: String?, val onClick: () -> Unit)

    val options = remember(entry, isGame, detailsLabel) {
        buildList<Option> {
            add(Option(detailsLabel, null, onDetails))
            add(Option(if (entry.favorite) "Remove from favourites" else "Add to favourites", null, onToggleFavorite))
            // Only an installed app has a category to mark; the other kinds of this tab have none.
            if (entry.appFacts != null && onMarkGame != null) {
                add(
                    if (isGame) {
                        Option("Not a game", "Takes it out of Games in the Category filter", onMarkGame)
                    } else {
                        Option("Mark as game", "Puts it under Games in the Category filter", onMarkGame)
                    },
                )
            }
            add(Option("Close", null, onDismiss))
        }
    }
    var focusIndex by remember { mutableIntStateOf(0) }

    Dialog(onDismissRequest = onDismiss) {
        MenuPanel(
            modifier = Modifier.width(LocalShellWindow.current.panelWidth(480.dp)),
            focusLabel = "App options",
            hints = listOf(HintBinding(GamepadAction.A, "Choose"), HintBinding(GamepadAction.B, "Close")),
            onPad = { press ->
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> focusIndex = menuMove(focusIndex, options.size, press)
                    GamepadAction.A -> options.getOrNull(focusIndex)?.onClick?.invoke()
                    GamepadAction.B, GamepadAction.SELECT -> onDismiss()
                    else -> Unit
                }
                true
            },
        ) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleLarge,
                color = MenuTokens.OnSurface,
                maxLines = 1,
            )
            options.forEachIndexed { index, option ->
                MenuRow(
                    title = option.title,
                    subtitle = option.subtitle,
                    selected = index == focusIndex,
                    onClick = option.onClick,
                )
            }
        }
    }
}
