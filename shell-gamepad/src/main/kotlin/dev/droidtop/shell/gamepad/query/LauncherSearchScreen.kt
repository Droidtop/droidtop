package dev.droidtop.shell.gamepad.query

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.GameNaming
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.integrations.LocalSearchRow
import dev.droidtop.library.integrations.LocalSimilarityRecommendations
import dev.droidtop.library.integrations.Recommendation
import dev.droidtop.library.integrations.RecommendationScope
import dev.droidtop.library.integrations.SearchRowKind
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.currentShellWindow
import dev.droidtop.shell.gamepad.pc.kindBadgeOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * rows in the one list: the installed apps and the library's games that
 * match, ranked together with whatever the download sources answer
 * (docs/SPEC.md 12a "One search"). While the field is empty droidtop's
 * Recommendations show.
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
        // System names for the Retro badge, read once off the main thread; the rows use a map, never a lookup each.
        val systemNames by produceState(emptyMap<String, String>()) {
            value = withContext(Dispatchers.IO) {
                ConsoleSystemsRepository.allSystems(context).associate { it.id to it.displayName }
            }
        }
        LibrarySearchDialog(
            query = LibraryQuery(text = initialText),
            matchCount = 0,
            totalCount = playable.size,
            onTextChange = {},
            onDismiss = onDismiss,
            suggestions = suggestions,
            localKey = playable to systemNames,
            local = { text ->
                val apps = findApps(text).map { app ->
                    LocalSearchRow(app.key, SearchRowKind.APP, app.title, APP_DETAIL, app.icon, app.open)
                }
                val matching = playable.filter { matchesSearchText(it, text) }.take(MAX_GAME_ROWS).map { game ->
                    LocalSearchRow(game.id, SearchRowKind.GAME, GameNaming.displayName(game.title), kindBadgeOf(game, systemNames).text) { onPlay(game) }
                }
                apps + matching
            },
        )
    }
}

private const val MAX_GAME_ROWS = 20
private const val APP_DETAIL = "App"
