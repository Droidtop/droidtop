package dev.droidtop.shell.gamepad.query

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.GameNaming
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.needsInstall
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
    /** The row's second line; null reads "App". An app shortcut names its app ("Shortcut · Maps"). */
    val detail: String? = null,
)

/**
 * The launcher's search: the drawer's search field and the app search of
 * Standard mode open this, and it is the shared [LibrarySearchDialog] (the
 * one the PC library and the console lists use) with the launcher's own
 * rows in the one list: the installed apps and the library's games that
 * match, ranked together with whatever the download sources answer
 * (docs/SPEC.md 12a "One search"); the surface's settings follow as a section of
 * their own ([settingsRoot]). While the field is empty droidtop's
 * Recommendations show.
 *
 * [games] is the library's already-scanned list, null while it has not been
 * read; matching it is a filter over that list in memory, nothing more
 * (no per-game disk lookups while typing). [findApps] answers for the
 * launcher's app list off the main thread.
 *
 * Which games it matches is [listExclusion]'s answer (docs/SPEC.md 7j), with
 * the default ownership options: hidden games and free-to-play games the
 * account never added are out, each with a switch under the field to take
 * them in. The switches are off every time search opens.
 */
@Composable
fun LauncherSearchScreen(
    initialText: String,
    games: List<LibraryEntry>?,
    findApps: suspend (String) -> List<LauncherSearchApp>,
    // A game row picked: its page where the surface has one, else play it. The owner, 2026-10-16: "Clicking on
    // the game entry should give the game's page" (docs/SPEC.md 12a).
    onOpen: (LibraryEntry) -> Unit,
    onDismiss: () -> Unit,
    // The settings of this surface, searched with the rest (null: this surface has none to search).
    settingsRoot: dev.droidtop.library.settings.CatalogScreen? = null,
    // A web search for the text, as one row of the list (Standard's search bar, Droidtop/tracker#564); null: none.
    webSearch: ((String) -> Unit)? = null,
) {
    CompositionLocalProvider(LocalShellWindow provides currentShellWindow()) {
        val context = LocalContext.current
        var includeHidden by remember { mutableStateOf(false) }
        var includeFree by remember { mutableStateOf(false) }
        // The person's own ownership options, as PC Games shows them; a preferences read, off the main thread.
        val saved by produceState(OwnershipOptions()) {
            value = withContext(Dispatchers.IO) { LibraryViewPrefs.ownershipOptions(context) }
        }
        val playable = remember(games, includeHidden, includeFree, saved) {
            searchablePool(games.orEmpty(), saved, includeFree = includeFree, includeHidden = includeHidden)
        }
        val suggestions by produceState(emptyList<Recommendation>(), playable) {
            value = LocalSimilarityRecommendations({ playable })
                .recommend(context, RecommendationScope.Overall, 5)
        }
        // Android's own Settings pages by title (Droidtop/tracker#704): which resolve on this device, read once off the
        // main thread; typing only filters them in memory.
        val androidPages by produceState(emptyList<dev.droidtop.runtime.systemstatus.SystemControls.SettingsLink>()) {
            value = withContext(Dispatchers.IO) {
                runCatching { dev.droidtop.runtime.systemstatus.SystemControls.settingsLinks(context) }.getOrDefault(emptyList())
            }
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
            settingsRoot = settingsRoot,
            localKey = playable to systemNames,
            switches = listOf(
                SearchSwitch("Include hidden games", includeHidden) { includeHidden = !includeHidden },
                SearchSwitch("Include free-to-play games not in your library", includeFree) { includeFree = !includeFree },
            ),
            local = { text ->
                val apps = findApps(text).map { app ->
                    LocalSearchRow(app.key, SearchRowKind.APP, app.title, app.detail ?: APP_DETAIL, app.icon, app.open)
                }
                val matching = playable.filter { matchesSearchText(it, text) }.take(MAX_GAME_ROWS).map { game ->
                    // The detail says whether it is here, so the places of one game (docs/SPEC.md 12a) say which one is installed.
                    val here = when {
                        game.missing -> MISSING
                        game.needsInstall() -> NOT_INSTALLED
                        else -> INSTALLED
                    }
                    val detail = listOfNotNull(kindBadgeOf(game, systemNames)?.text, here).joinToString(" · ")
                    LocalSearchRow(game.id, SearchRowKind.GAME, GameNaming.displayName(game.title), detail) { onOpen(game) }
                }
                // The web is one row, after everything else: the query is its detail, not its title, so the title never
                // matches it and the row ranks last (SearchRank OTHER, then SearchRowKind.WEB). Picking it searches and
                // closes this search.
                val web = webSearch?.takeIf { text.isNotBlank() }?.let { search ->
                    val query = text.trim()
                    LocalSearchRow("web", SearchRowKind.WEB, WEB_TITLE, "\u201c$query\u201d") {
                        search(query)
                        onDismiss()
                    }
                }
                // A sum answers first (Droidtop/tracker#704): its title starts with the text, so it ranks with the best
                // matches, and CALC sorts ahead of them; picking it copies the answer.
                val sum = dev.droidtop.library.integrations.SearchCalculator.answer(text)?.let { value ->
                    LocalSearchRow("calc", SearchRowKind.CALC, "${text.trim()} = $value", CALC_DETAIL) {
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(CALC_DETAIL, value))
                        dev.droidtop.library.settings.Notices.flash("$value copied", context = context)
                    }
                }
                // An Android Settings page whose title matches, said to be Android's; picking it opens it and closes this.
                val pages = androidPages.takeIf { text.isNotBlank() }.orEmpty()
                    .filter { dev.droidtop.library.integrations.SearchRank.score(it.label, text) < dev.droidtop.library.integrations.SearchRank.OTHER }
                    .map { link ->
                        LocalSearchRow("android:${link.id}", SearchRowKind.APP, link.label, ANDROID_PAGE_DETAIL) {
                            dev.droidtop.runtime.systemstatus.SettingsLaunch.start(context, link.intent)
                            onDismiss()
                        }
                    }
                listOfNotNull(sum) + apps + pages + matching + listOfNotNull(web)
            },
        )
    }
}

private const val MAX_GAME_ROWS = 20
private const val APP_DETAIL = "App"
private const val INSTALLED = "Installed"
private const val NOT_INSTALLED = "Not installed"

/** A game whose files are not reachable right now (an offline card or share): found, and said so. */
private const val MISSING = "Missing"
private const val WEB_TITLE = "Search the web"
private const val CALC_DETAIL = "Calculator · press to copy"
private const val ANDROID_PAGE_DETAIL = "Android's Settings page"
