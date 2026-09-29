package dev.droidtop.shell.gamepad.query

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.droidtop.library.GameNaming
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.integrations.LocalSimilarityRecommendations
import dev.droidtop.library.integrations.Recommendation
import dev.droidtop.library.integrations.RecommendationScope
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.MenuRow
import dev.droidtop.shell.gamepad.MenuSectionLabel
import dev.droidtop.shell.gamepad.currentShellWindow

/**
 * One installed app in the launcher's search results. The launcher owns the
 * app list (its model, its icons, its private space rules), so it hands
 * this screen only what a row needs: a name, the icon it already holds, and
 * what opening it does.
 */
class LauncherSearchApp(
    val key: String,
    val title: String,
    val icon: Bitmap?,
    val open: () -> Unit,
)

/**
 * The launcher's search: the drawer's search field and the app search of
 * Standard mode open this, and it is the shared [LibrarySearchDialog] (the
 * one the PC library and the console lists use) with the launcher's own
 * local results in it: the installed apps that match, then the library's
 * games, then the dialog's own "Get more" group from the source plugins,
 * and, while the field is empty, droidtop's Recommendations (docs/SPEC.md
 * 12a "Launcher search").
 *
 * [games] is the library's already-scanned list, null while it has not been
 * read; matching it is a filter over that list in memory, nothing more
 * (no per-game disk lookups while typing). [findApps] answers for the
 * launcher's app list off the main thread.
 */
@Composable
fun LauncherSearchScreen(
    initialText: String,
    games: List<LibraryEntry>?,
    findApps: suspend (String) -> List<LauncherSearchApp>,
    onPlay: (LibraryEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    CompositionLocalProvider(LocalShellWindow provides currentShellWindow()) {
        val context = LocalContext.current
        val playable = remember(games) { games.orEmpty().filter { !it.hidden && !it.missing } }
        val suggestions by produceState(emptyList<Recommendation>(), playable) {
            value = LocalSimilarityRecommendations({ playable })
                .recommend(context, RecommendationScope.Overall, 5)
        }
        LibrarySearchDialog(
            query = LibraryQuery(text = initialText),
            matchCount = 0,
            totalCount = playable.size,
            onTextChange = {},
            onDismiss = onDismiss,
            suggestions = suggestions,
            summary = if (games == null) "Reading the library" else "Search apps and games",
            results = { text ->
                val apps by produceState(emptyList<LauncherSearchApp>(), text) { value = findApps(text) }
                val matchingGames = remember(playable, text) {
                    playable.filter { matchesSearchText(it, text) }
                        .sortedBy { GameNaming.displayName(it.title).lowercase() }
                        .take(MAX_GAME_ROWS)
                }
                if (apps.isNotEmpty()) {
                    MenuSectionLabel("Apps (${apps.size})")
                    apps.forEach { app ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            app.icon?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 8.dp).size(40.dp),
                                )
                            }
                            MenuRow(
                                title = app.title,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    app.open()
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
                if (matchingGames.isNotEmpty()) {
                    MenuSectionLabel("Games (${matchingGames.size})")
                    matchingGames.forEach { game ->
                        MenuRow(
                            title = GameNaming.displayName(game.title),
                            onClick = {
                                onPlay(game)
                                onDismiss()
                            },
                        )
                    }
                }
                if (apps.isEmpty() && matchingGames.isEmpty()) {
                    MenuSectionLabel("On this device")
                    androidx.compose.material3.Text(
                        "No installed app or library game matches",
                        color = dev.droidtop.shell.gamepad.MenuTokens.OnSurfaceMuted,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            },
        )
    }
}

private const val MAX_GAME_ROWS = 8
