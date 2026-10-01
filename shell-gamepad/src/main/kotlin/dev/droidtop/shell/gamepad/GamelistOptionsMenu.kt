package dev.droidtop.shell.gamepad

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.scraper.importGamelistXml
import dev.droidtop.library.scraper.isPcOrEngineGame
import dev.droidtop.shell.gamepad.pc.PC_SYSTEM_ID
import dev.droidtop.library.integrations.AcquireContentSources
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * Per-gamelist sort order, persisted per group (the pattern is real
 * ES-DE's GuiGamelistOptions "SORT GAMES BY"; the placement and controls
 * are droidtop's own -- per direction, ES-DE's general UI is the copy
 * target, never its literal control scheme).
 */
enum class GamelistSort(val label: String) {
    NAME("Name"),
    RATING("Rating"),
    RELEASE_DATE("Release date"),
    LAST_PLAYED("Last played"),
}

/**
 * Which games a gamelist shows (the GuiGamelistFilter idea, kept to the
 * states droidtop actually stores per game). Persisted per group like
 * the sort order, so a filtered list stays filtered on the way back.
 *
 * The last three read [LibraryEntry.switchFacts] (docs/SPEC.md 7m,
 * "Switch content"), so on a non-Switch gamelist they simply match
 * nothing -- the same honest nothing a "Favorites" filter says on a
 * list with no favourites.
 */
enum class GamelistFilter(val label: String) {
    ALL("All games"),
    FAVORITES("Favorites"),
    COMPLETED("Completed"),
    UNPLAYED("Never played"),
    HAS_DLC("Has DLC"),
    MISSING_UPDATE("Missing update"),
    LOOSE_DLC("DLC without base game"),
    ;

    fun matches(entry: LibraryEntry): Boolean = when (this) {
        ALL -> true
        FAVORITES -> entry.favorite
        COMPLETED -> entry.completed
        UNPLAYED -> entry.lastPlayedEpochMs == null
        HAS_DLC -> entry.switchFacts?.let { it.dlcCount > 0 } == true
        // A Switch game classification could say nothing about is not
        // "missing" anything -- only a row known to be a base game
        // without an update beside it is.
        MISSING_UPDATE -> entry.switchFacts?.let { !it.loose && !it.hasUpdate } == true
        LOOSE_DLC -> entry.switchFacts?.loose == true
    }
}

object GamelistFilterPrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_PREFIX = "droidtop_gamelist_filter_"

    fun get(context: Context, groupKey: String): GamelistFilter {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + groupKey, null) ?: return GamelistFilter.ALL
        return runCatching { GamelistFilter.valueOf(raw) }.getOrDefault(GamelistFilter.ALL)
    }

    fun cycle(context: Context, groupKey: String): GamelistFilter {
        val next = GamelistFilter.entries[(get(context, groupKey).ordinal + 1) % GamelistFilter.entries.size]
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PREFIX + groupKey, next.name).apply()
        return next
    }
}

object GamelistSortPrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_PREFIX = "droidtop_gamelist_sort_"

    fun get(context: Context, groupKey: String): GamelistSort {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + groupKey, null) ?: return GamelistSort.NAME
        return runCatching { GamelistSort.valueOf(raw) }.getOrDefault(GamelistSort.NAME)
    }

    fun cycle(context: Context, groupKey: String): GamelistSort {
        val next = GamelistSort.entries[(get(context, groupKey).ordinal + 1) % GamelistSort.entries.size]
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PREFIX + groupKey, next.name).apply()
        return next
    }

    fun comparator(sort: GamelistSort): Comparator<LibraryEntry> = when (sort) {
        GamelistSort.NAME -> compareBy { it.title.lowercase() }
        GamelistSort.RATING -> compareByDescending<LibraryEntry> { it.rating ?: -1f }.thenBy { it.title.lowercase() }
        GamelistSort.RELEASE_DATE -> compareBy<LibraryEntry> { it.releaseDate ?: "99999999" }.thenBy { it.title.lowercase() }
        GamelistSort.LAST_PLAYED -> compareByDescending<LibraryEntry> { it.lastPlayedEpochMs ?: 0L }.thenBy { it.title.lowercase() }
    }
}

/** The one label for the PC/engine scrape action, shared by the list that offers it and the handler that runs it. */
private const val SCRAPE_PC_GAMES = "Scrape PC & engine games"
// Renamed from "Stores and folders" (uisources agent, 2026-09-28): the
// settings screen it opens now holds only Game folders, Windows games
// and Downloads -- store accounts moved to the "Accounts and sources"
// settings area.
private const val PC_SETUP = "PC setup"
private const val SYSTEM_SETTINGS = "System settings"
private const val ORPHANS_FIND = "Find orphaned media"
private const val ORPHANS_DELETE = "Delete orphaned media: press A again"

/**
 * The in-gamelist options overlay (the ES-DE GuiGamelistOptions
 * PATTERN: sort, scrape, and library actions right where the user is,
 * never a settings detour -- per direction, actions live on the main
 * screen and Settings is configuration only). Entirely
 * controller-driven: Up/Down moves, A activates, B closes.
 *
 * Scrape and import resolve the group's real folders themselves (every
 * configured games root's child folders whose resolved system is this
 * group's), run in place with live status, and never navigate away.
 */
@Composable
internal fun GamelistOptionsMenu(
    groupKey: String,
    groupLabel: String,
    systemId: String?,
    onSortChanged: () -> Unit,
    onScraped: () -> Unit,
    onDismiss: () -> Unit,
    // The gamelist as it is currently shown, so jump and random address
    // exactly what the user is looking at rather than a second copy.
    games: List<LibraryEntry> = emptyList(),
    onJumpTo: (Int) -> Unit = {},
    // "Stores and folders" (docs/SPEC.md 7i): sign in to a store, add a
    // games folder, set up Windows games, see what is downloading. Opens
    // the group's own options screen (ShellBackStack.optionsOpen), the
    // same level above the gamelist every group's options screen uses.
    onOpenStores: () -> Unit = {},
    // The console gamelist's search text (docs/SPEC.md 12a "Search
    // fan-out"): [games] is already narrowed by it, [totalGames] is the
    // list before narrowing. Null [onSearchTextChange] means this list has
    // no search row (the library scope; the PC group has its own chip row).
    searchText: String = "",
    totalGames: Int = games.size,
    onSearchTextChange: ((String) -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var focusIndex by remember { mutableIntStateOf(0) }
    var sort by remember { mutableStateOf(GamelistSortPrefs.get(context, groupKey)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // Non-null while a real settings catalog screen is hosted over this
    // menu -- the "Get games" screen (AcquireContentSources, docs/SPEC.md
    // 12/12a) or Console systems ("System settings" below, docs/SPEC.md
    // "One settings entry point") -- rendered by the SAME generic
    // CatalogNavigator Gaming's own Settings section uses, so real
    // controller/touch text entry and a focusable results list come for
    // free rather than a second hand-built input widget here. One state
    // slot for both: only ever one of them is open at a time, and a
    // second slot would be a second mechanism for "show a settings
    // screen over this menu".
    var acquireScreen by remember { mutableStateOf<CatalogScreen?>(null) }
    var filter by remember { mutableStateOf(GamelistFilterPrefs.get(context, groupKey)) }
    // Per-system launch-screen default (docs/SPEC.md section 4c: "Select
    // which display to open ROMs from this tab" / "Tune individual
    // platforms"). A per-game choice still wins over this; cycling back
    // to "Ask" clears it.
    var systemLaunchScreen by remember {
        mutableStateOf(systemId?.let { dev.droidtop.library.LaunchScreenMemory.systemChoice(context, it) })
    }
    // The letter list replaces the action list in place: a menu that
    // pushes a second dialog on a handheld is a menu you get lost in.
    var pickingLetter by remember { mutableStateOf(false) }
    val letters = remember(games) {
        games.map { entry ->
            entry.title.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#'
        }.distinct().sorted()
    }

    // Orphaned media is one row with a two-step confirm: the first A
    // finds and reports, the second deletes (recomputed, never the report
    // the first press made). Moving to another row disarms it.
    var orphansArmed by remember { mutableStateOf(false) }
    val orphansLabel = if (orphansArmed) ORPHANS_DELETE else ORPHANS_FIND

    val libraryScope = groupKey.isEmpty()
    val searchRowLabel = if (searchText.isBlank()) "Search" else "Search: ${searchText.trim()}"
    var searchOpen by remember { mutableStateOf(false) }
    val actions = buildList {
        if (libraryScope) {
            // The library-wide actions that used to live in Settings.
            add("Get games")
            add("Rescan library")
            add("Scrape all systems")
            add(orphansLabel)
            add("Update platform databases")
        } else {
            // The PC group's own filter/sort/search is the chip row over
            // its grid now (dev.droidtop.shell.gamepad.query.LibraryQuery,
            // docs/SPEC.md 7i, redecided 2026-09-28) -- these two rows
            // would be a second, always-out-of-sync mechanism for it.
            if (systemId != PC_SYSTEM_ID) {
                add("Sort: ${sort.label}")
                add("Show: ${filter.label}")
            }
            if (onSearchTextChange != null && systemId != PC_SYSTEM_ID) add(searchRowLabel)
            if (games.isNotEmpty()) {
                add("Jump to letter")
                add("Random game")
            }
            if (systemId != null) {
                add("Launch screen: " + (systemLaunchScreen?.label ?: "Ask"))
                add(SYSTEM_SETTINGS)
                add("Scrape this system")
                add("Import gamelist.xml")
            }
            // Wherever games are listed, not only one console system: All games and the PC list ask
            // which system to download for (rig, 2026-09-30: the entry was unreachable from the Games tab).
            add("Get games")
            // Offered wherever PC or engine games are actually on
            // screen, which is the same "act on what you are looking
            // at" placement every other action here uses. Those groups
            // have no console systemId, so the action above never
            // covered them and there was no way to scrape them at all.
            if (games.any { it.isPcOrEngineGame }) add(SCRAPE_PC_GAMES)
            // Only inside the PC group itself, whose games occupy this
            // whole gamelist -- not "All games" or another collection
            // that merely happens to contain a PC entry, which is not
            // where a store login belongs.
            if (systemId == PC_SYSTEM_ID) add(PC_SETUP)
        }
        add("Close")
    }

    suspend fun consoleFoldersFor(id: String): List<java.io.File> {
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        return dev.droidtop.library.consoles.SystemFolders.all(context, systemsById)
            .filter { (_, system) -> system.id == id }
            .map { (folder, _) -> folder }
    }

    fun jumpToLetter(index: Int) {
        val letter = letters.getOrNull(index) ?: return
        val target = games.indexOfFirst { entry ->
            (entry.title.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#') == letter
        }
        if (target >= 0) {
            onJumpTo(target)
            onDismiss()
        }
    }

    // The scrape is a job (docs/SPEC.md 12a "Jobs"): this only starts it, or finds it already
    // running or paused. Its progress, Pause, Resume and Cancel live under Downloads and installs.
    fun startScrapeJob(title: String, systemId: String?) {
        val started = dev.droidtop.library.scraper.LibraryScrapeJob.start(context, title, systemId) { summary ->
            status = summary
            scope.launch { onScraped() }
        }
        status = if (started != null) "$title started. Follow it, pause it or cancel it under Downloads and installs." else "Scraping isn't available."
    }

    fun activate(index: Int) {
        when (actions[index]) {
            "Rescan library" -> {
                // Says it started and what it found, like the settings row
                // it is the same action as (LibraryRescan).
                if (busy) return
                busy = true
                scope.launch {
                    status = withContext(Dispatchers.IO) {
                        dev.droidtop.library.settings.LibraryRescan.run(context) { status = it }
                    }
                    busy = false
                }
            }
            "Scrape all systems" -> {
                startScrapeJob(title = "Scrape all systems", systemId = null)
            }
            ORPHANS_FIND -> {
                if (busy) return
                busy = true
                scope.launch {
                    status = "Looking for orphaned media\u2026"
                    val report = dev.droidtop.library.scraper.OrphanedMedia.find(context)
                    status = if (report.isEmpty) {
                        report.describe()
                    } else {
                        report.describe() + "\nPress A again to delete them."
                    }
                    orphansArmed = !report.isEmpty
                    busy = false
                }
            }
            ORPHANS_DELETE -> {
                if (busy) return
                busy = true
                orphansArmed = false
                scope.launch {
                    status = "Checking again before deleting\u2026"
                    status = withContext(Dispatchers.IO) {
                        val report = dev.droidtop.library.scraper.OrphanedMedia.find(context)
                        dev.droidtop.library.scraper.OrphanedMedia.clean(context, report)
                    }
                    busy = false
                    onScraped()
                }
            }
            "Update platform databases" -> {
                if (busy) return
                busy = true
                scope.launch {
                    // The same call as the settings row and the update
                    // schedule (SPEC 7e2): index-driven, all four databases.
                    status = withContext(Dispatchers.IO) {
                        runCatching {
                            dev.droidtop.library.consoles.PlatformDatabases.refresh(context) { status = it }
                        }.getOrElse { "Update failed: ${it.message}" }
                    }
                    busy = false
                }
            }
            "Sort: ${sort.label}" -> {
                sort = GamelistSortPrefs.cycle(context, groupKey)
                onSortChanged()
            }
            "Show: ${filter.label}" -> {
                filter = GamelistFilterPrefs.cycle(context, groupKey)
                onSortChanged()
            }
            "Launch screen: " + (systemLaunchScreen?.label ?: "Ask") -> {
                val next = when (systemLaunchScreen) {
                    null -> dev.droidtop.library.LaunchScreen.BUILT_IN
                    dev.droidtop.library.LaunchScreen.BUILT_IN -> dev.droidtop.library.LaunchScreen.SECOND
                    dev.droidtop.library.LaunchScreen.SECOND -> null
                }
                systemLaunchScreen = next
                systemId?.let { dev.droidtop.library.LaunchScreenMemory.setSystemChoice(context, it, next) }
            }
            searchRowLabel -> searchOpen = true
            "Jump to letter" -> {
                pickingLetter = true
                focusIndex = 0
            }
            "Random game" -> {
                if (games.isNotEmpty()) {
                    onJumpTo(games.indices.random())
                    onDismiss()
                }
            }
            "Scrape this system" -> {
                startScrapeJob(title = "Scrape $groupLabel", systemId = systemId)
            }
            SCRAPE_PC_GAMES -> {
                if (busy) return
                busy = true
                val pcGames = games.filter { it.isPcOrEngineGame }
                scope.launch {
                    status = withContext(Dispatchers.IO) {
                        dev.droidtop.library.scraper.PcScraper.scrape(context, pcGames) { done, total ->
                            status = "Scraping PC & engine games: $done/$total"
                        }
                    }
                    busy = false
                    onScraped()
                }
            }
            "Import gamelist.xml" -> {
                if (busy) return
                busy = true
                scope.launch {
                    val results = withContext(Dispatchers.IO) {
                        consoleFoldersFor(systemId!!).ifEmpty { null }
                            ?.map { folder -> importGamelistXml(context, folder) }
                            ?: listOf("No folder for $groupLabel in any games root.")
                    }
                    status = results.joinToString("\n")
                    busy = false
                    onScraped()
                }
            }
            "Get games" -> {
                if (busy) return
                val id = systemId
                if (id == null || id == PC_SYSTEM_ID) {
                    acquireScreen = AcquireContentSources.chooseSystemScreen()
                    return
                }
                busy = true
                scope.launch {
                    val folder = withContext(Dispatchers.IO) { consoleFoldersFor(id).firstOrNull() }
                    busy = false
                    if (folder == null) {
                        status = "No folder for $groupLabel in any games root."
                    } else {
                        acquireScreen = AcquireContentSources.systemScreen(id, groupLabel, folder)
                    }
                }
            }
            PC_SETUP -> {
                onDismiss()
                onOpenStores()
            }
            SYSTEM_SETTINGS -> {
                // The same registered screen Settings > Library > Console
                // systems opens (registryId "console_systems",
                // AppSettingsCatalogs), through the same generic
                // CatalogNavigator dialog "Get games" uses -- but
                // DEEP-LINKED: the registry argument re-opens that one
                // screen parameterized with THIS system (docs/SPEC.md
                // "One consistent way into Settings"), so it lands on
                // this system's folder rows instead of the top of the
                // whole list, never a second folder/emulator picker.
                // A system with no folder on any games root falls back
                // to the full list inside the screen itself.
                val screen = SettingsScreenRegistry.get("console_systems", systemId)
                if (screen != null) acquireScreen = screen else status = "Settings screen unavailable"
            }
            "Close" -> onDismiss()
        }
    }

    val openAcquireScreen = acquireScreen
    if (openAcquireScreen != null) {
        Dialog(onDismissRequest = { acquireScreen = null }) {
            CatalogNavigator(
                root = openAcquireScreen,
                onExit = {
                    acquireScreen = null
                    // A download may have just landed a real file in
                    // this system's folder -- rescan so it shows up,
                    // same as every other library-changing action here.
                    onScraped()
                },
            )
        }
        return
    }

    // B, Select and the system back key all step out of the letter list
    // first, and only then close the menu.
    val goBack = {
        if (pickingLetter) {
            pickingLetter = false
            focusIndex = 0
        } else {
            onDismiss()
        }
    }
    Dialog(onDismissRequest = goBack) {
        MenuPanel(
            // A fixed 520dp panel is wider than a phone, and the part
            // that falls off the edge is the part with the buttons on it.
            modifier = Modifier.width(dev.droidtop.shell.gamepad.LocalShellWindow.current.panelWidth(520.dp)),
            focusLabel = "Gamelist options",
            onPad = { press ->
                val itemCount = if (pickingLetter) letters.size else actions.size
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> {
                        orphansArmed = false
                        focusIndex = menuMove(focusIndex, itemCount, press)
                    }
                    GamepadAction.A -> if (pickingLetter) jumpToLetter(focusIndex) else activate(focusIndex)
                    GamepadAction.B, GamepadAction.SELECT -> goBack()
                    else -> Unit
                }
                true
            },
        ) {
            Text(
                groupLabel,
                style = MaterialTheme.typography.titleLarge,
                color = MenuTokens.OnSurface,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            if (pickingLetter) {
                letters.forEachIndexed { index, letter ->
                    MenuRow(
                        title = letter.toString(),
                        selected = index == focusIndex,
                        onClick = {
                            focusIndex = index
                            jumpToLetter(index)
                        },
                    )
                }
            } else {
                actions.forEachIndexed { index, label ->
                    MenuRow(
                        title = label,
                        selected = index == focusIndex,
                        danger = label == ORPHANS_DELETE,
                        onClick = {
                            if (focusIndex != index) orphansArmed = false
                            focusIndex = index
                            activate(index)
                        },
                    )
                }
            }
            // A result is read, not selected: every line of it, wrapped,
            // never cut. The sentence that says how to fix a refusal is at
            // its end, and a row that cut it at one line (or four) showed
            // the problem and hid the fix (rig, dq-shell2-01). The panel
            // scrolls, so a long result makes the panel longer instead.
            status?.let { ResultText(it) }
            MenuHint(
                if (pickingLetter) "Up/Down moves, A jumps, B goes back" else "Up/Down moves, A activates, B closes",
            )
        }
    }

    // The same search dialog the PC library opens (LibraryQueryUi), so the
    // "Get more" fan-out to source plugins is one component, not a copy.
    // Downloads land in this system's own folder.
    if (searchOpen && onSearchTextChange != null) {
        var searchFolder by remember { mutableStateOf<java.io.File?>(null) }
        androidx.compose.runtime.LaunchedEffect(systemId) {
            searchFolder = systemId?.let { id -> withContext(Dispatchers.IO) { consoleFoldersFor(id).firstOrNull() } }
        }
        dev.droidtop.shell.gamepad.query.LibrarySearchDialog(
            query = dev.droidtop.shell.gamepad.query.LibraryQuery(text = searchText),
            matchCount = games.size,
            totalCount = totalGames,
            onTextChange = onSearchTextChange,
            onDismiss = { searchOpen = false },
            systemId = systemId,
            systemFolder = searchFolder,
        )
    }
}

/**
 * An action's result: its first line as a title, the rest under it, all of
 * it wrapped and none of it cut. When it appears or changes, the panel
 * scrolls it wholly into view: a long result below the actions ended
 * under the panel's bottom edge with nothing saying there was more, and
 * the fix sentence was in the hidden part (rig, dq-shell2-02, IGDB).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ResultText(text: String) {
    val bringIntoView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    androidx.compose.runtime.LaunchedEffect(text) {
        // After this text is laid out, so the whole of it is what is shown.
        androidx.compose.runtime.withFrameNanos { }
        runCatching { bringIntoView.bringIntoView() }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .padding(horizontal = Space.Lg, vertical = Space.Md),
        verticalArrangement = Arrangement.spacedBy(Space.Xs),
    ) {
        Text(text.lineSequence().first(), color = MenuTokens.OnSurface, style = TypeRole.rowTitle)
        text.substringAfter('\n', "").takeIf { it.isNotBlank() }?.let { rest ->
            Text(rest, color = MenuTokens.OnSurfaceMuted, style = TypeRole.supporting)
        }
    }
}
