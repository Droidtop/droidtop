package dev.droidtop.shell.gamepad

import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding

/**
 * Select on an app: the focused app's own menu (docs/SPEC.md 7j, "Filters,
 * sort and the hint bar"). The same shape as the game menu's top
 * (Droidtop/tracker#610): its primary action (Open an app, Play a game) first,
 * then its details, its favourite, and Mark as game
 * (the Apps view's Category filter seeds Games from Android's own flag and
 * droidtop's lists; this is the person's override, also on a long press),
 * then Hide from droidtop and Uninstall (Android's own prompt) for an installed app.
 * Controller-first like every menu here: Up/Down moves, A chooses, B closes,
 * and every row is a touch target.
 */
@Composable
internal fun AppOptionsMenu(
    entry: LibraryEntry,
    // What A on the thing does, first in the menu as on the game menu; null where the caller has none.
    onPrimary: (() -> Unit)? = null,
    onDetails: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit,
    isGame: Boolean = false,
    // Null where the caller has no category to mark (Home's mixed shelves).
    onMarkGame: (() -> Unit)? = null,
    // Null where the caller does not manage installed apps (Hide, Uninstall).
    hidden: Boolean = false,
    onToggleHidden: (() -> Unit)? = null,
    onUninstall: (() -> Unit)? = null,
    detailsLabel: String = "App details",
) {
    class Option(val title: String, val subtitle: String?, val onClick: () -> Unit)

    // What plugins offer on this app (C4, Droidtop/tracker#67): the same page as Standard's long-press menu.
    var pluginScreen by remember(entry) { mutableStateOf<CatalogScreen?>(null) }
    val hasPluginActions = rememberHasPluginActions(entry)
    pluginScreen?.let { screen ->
        CatalogSheet(root = screen, onExit = { pluginScreen = null })
        return
    }

    val options = remember(entry, isGame, hidden, detailsLabel, hasPluginActions) {
        buildList<Option> {
            onPrimary?.let { add(Option(if (entry.appFacts != null) "Open" else "Play", null, it)) }
            add(Option(detailsLabel, null, onDetails))
            if (hasPluginActions) {
                add(
                    Option(PLUGIN_ACTIONS_LABEL, "What your plugins can do with this app") {
                        pluginScreen = pluginActionsScreen(entry, dev.droidtop.pluginhost.PluginModes.Surfaces.GAMING_APP_MENU) { reply -> pluginScreen = reply }
                    },
                )
            }
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
            if (entry.appFacts != null && onToggleHidden != null) {
                add(
                    if (hidden) {
                        Option("Show in droidtop", null, onToggleHidden)
                    } else {
                        Option("Hide from droidtop", "Filter, then Hidden, brings it back", onToggleHidden)
                    },
                )
            }
            // Android refuses to uninstall a system app, so the row is only for the ones it will take.
            if (entry.appFacts?.system == false && onUninstall != null) add(Option("Uninstall", null, onUninstall))
            add(Option("Close", null, onDismiss))
        }
    }
    var focusIndex by remember { mutableIntStateOf(0) }

    Dialog(onDismissRequest = onDismiss) {
        GatePadInThisDialog()
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

/** Android's own uninstall prompt for [packageName]; it asks its own confirmation and refuses a system app. */
internal fun startUninstall(context: android.content.Context, packageName: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_DELETE,
                android.net.Uri.fromParts("package", packageName, null),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
