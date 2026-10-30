package dev.droidtop.shell.gamepad

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.settings.CatalogScreen

/**
 * Select on an app: the focused app's own menu (docs/SPEC.md 7j, "Filters,
 * sort and the hint bar"). The same shape as the game menu's top
 * (Droidtop/tracker#610): its primary action (Open an app, Play a game) first,
 * then its details, its favourite, and Mark as game
 * (the Apps view's Category filter seeds Games from Android's own flag and
 * droidtop's lists; this is the person's override, also on a long press),
 * then Hide from droidtop and Uninstall (Android's own prompt) for an installed app.
 * Controller-first like every menu here: Up/Down moves, A chooses, B closes,
 * and every row is a touch target. With [anchor] it opens beside the item it
 * is the menu of ([AnchoredMenu], Droidtop/tracker#827); without one, centred.
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
    // Where the item is on the screen, so the menu opens beside it; null opens it centred.
    anchor: AnchorRect? = null,
) {
    // The game that is running leads with its own sheet, this menu one row into it (Droidtop/tracker#740).
    RunningGameFirst(entry, onDismiss) {
        AppOptionsSheet(entry, onPrimary, onDetails, onToggleFavorite, onDismiss, isGame, onMarkGame, hidden, onToggleHidden, onUninstall, detailsLabel, anchor)
    }
}

@Composable
private fun AppOptionsSheet(
    entry: LibraryEntry,
    onPrimary: (() -> Unit)?,
    onDetails: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit,
    isGame: Boolean,
    onMarkGame: (() -> Unit)?,
    hidden: Boolean,
    onToggleHidden: (() -> Unit)?,
    onUninstall: (() -> Unit)?,
    detailsLabel: String,
    anchor: AnchorRect?,
) {
    // What plugins offer on this app (C4, Droidtop/tracker#67): the same page as Standard's long-press menu.
    var pluginScreen by remember(entry) { mutableStateOf<CatalogScreen?>(null) }
    val hasPluginActions = rememberHasPluginActions(entry)
    pluginScreen?.let { screen ->
        CatalogSheet(root = screen, onExit = { pluginScreen = null })
        return
    }

    val options = remember(entry, isGame, hidden, detailsLabel, hasPluginActions) {
        buildList<ActionRow> {
            onPrimary?.let { add(ActionRow(if (entry.appFacts != null) "Open" else "Play", onSelect = it)) }
            add(ActionRow(detailsLabel, onSelect = onDetails))
            if (hasPluginActions) {
                add(
                    ActionRow(PLUGIN_ACTIONS_LABEL, "What your plugins can do with this app") {
                        pluginScreen = pluginActionsScreen(entry, dev.droidtop.pluginhost.PluginModes.Surfaces.GAMING_APP_MENU) { reply -> pluginScreen = reply }
                    },
                )
            }
            add(ActionRow(if (entry.favorite) "Remove from favourites" else "Add to favourites", onSelect = onToggleFavorite))
            // Only an installed app has a category to mark; the other kinds of this tab have none.
            if (entry.appFacts != null && onMarkGame != null) {
                add(
                    if (isGame) {
                        ActionRow("Not a game", "Takes it out of Games in the Category filter", onSelect = onMarkGame)
                    } else {
                        ActionRow("Mark as game", "Puts it under Games in the Category filter", onSelect = onMarkGame)
                    },
                )
            }
            if (entry.appFacts != null && onToggleHidden != null) {
                add(
                    if (hidden) {
                        ActionRow("Show in droidtop", onSelect = onToggleHidden)
                    } else {
                        ActionRow("Hide from droidtop", "Filter, then Hidden, brings it back", onSelect = onToggleHidden)
                    },
                )
            }
            // Android refuses to uninstall a system app, so the row is only for the ones it will take.
            if (entry.appFacts?.system == false && onUninstall != null) add(ActionRow("Uninstall", onSelect = onUninstall))
            add(ActionRow("Close", onSelect = onDismiss))
        }
    }
    AnchoredMenu(anchor = anchor, title = entry.title, rows = options, onDismiss = onDismiss)
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
