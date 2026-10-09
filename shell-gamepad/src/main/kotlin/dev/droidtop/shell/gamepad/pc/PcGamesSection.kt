package dev.droidtop.shell.gamepad.pc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.droidtop.library.AppCategoryRules
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.RunnerAction
import dev.droidtop.library.RunnerState
import dev.droidtop.library.StoreDownloads
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.scraper.isPcOrEngineGame
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.shell.gamepad.AppOptionsMenu
import dev.droidtop.shell.gamepad.CatalogNavigator
import dev.droidtop.shell.gamepad.GamelistOptionsMenu
import dev.droidtop.shell.gamepad.HelpRowClaim
import dev.droidtop.shell.gamepad.GamingSection
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.Motion
import dev.droidtop.shell.gamepad.rise
import dev.droidtop.shell.gamepad.OwnShoulders
import dev.droidtop.shell.gamepad.StatusClusterRoom
import dev.droidtop.shell.gamepad.ViewStrip
import dev.droidtop.shell.gamepad.Space
import dev.droidtop.shell.gamepad.TypeRole
import dev.droidtop.shell.gamepad.gridPadTarget
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.declaresHints
import dev.droidtop.shell.gamepad.input.menuStep
import dev.droidtop.shell.gamepad.input.onPad
import dev.droidtop.shell.gamepad.keepCentred
import dev.droidtop.shell.gamepad.keepInView
import dev.droidtop.shell.gamepad.query.LibraryFacet
import dev.droidtop.shell.gamepad.query.pillText
import dev.droidtop.shell.gamepad.query.LibraryFilterSheet
import dev.droidtop.shell.gamepad.query.LibrarySortSheet
import dev.droidtop.shell.gamepad.query.PersistQuery
import dev.droidtop.shell.gamepad.query.SheetAction
import dev.droidtop.shell.gamepad.query.rememberSavedViews
import dev.droidtop.shell.gamepad.query.LibraryQuery
import dev.droidtop.shell.gamepad.query.LibraryQueryScope
import dev.droidtop.shell.gamepad.query.LibrarySearchDialog
import dev.droidtop.shell.gamepad.query.LibrarySortKey
import dev.droidtop.shell.gamepad.requestFocusWhenAttached
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import dev.droidtop.shell.gamepad.theme.UiSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where the PC Games tab is and what it is on, held by the shell outside
 * the tab (the same reason [dev.droidtop.shell.gamepad.ShellBackStack]
 * exists): the section is rebuilt whenever another tab is drawn, and a
 * tab that owned its own cursor lost it on every L1/R1. Saveable, so the
 * Activity recreate the Text size setting triggers keeps the place too.
 */
internal class PcGamesState {
    /** Which of the section's three surfaces is showing ([PcView]). */
    var view by mutableStateOf(PcView.HOME)

    /** Home, the cross-library shelves the left menu's first row opens. */
    val home: Boolean get() = view == PcView.HOME

    /** Shelves of capsules (Home or PC Games' Overview), not the grid. */
    val onShelves: Boolean get() = view != PcView.GRID

    /** The grid's filter, sort and search; loaded from the list's prefs once, written back on change. */
    var query by mutableStateOf(LibraryQuery())
    var queryLoaded by mutableStateOf(false)

    /** The cursor is on the view strip above the content. */
    var stripFocused by mutableStateOf(false)
    var stripIndex by mutableIntStateOf(0)

    /** The shelf the cursor is on (home), and the capsule on that shelf or in the grid. */
    var shelfIndex by mutableIntStateOf(0)
    var itemIndex by mutableIntStateOf(0)

    /** Where the cursor was on each shelf, so Up/Down returns to it (the Deck keeps each row's place). */
    val shelfItems = mutableStateMapOf<String, Int>()

    /**
     * The cursor is on Home's destination row (Retro Games, PC Games) under
     * the shelves, and which of its tiles. Home only; the row is the last
     * stop of Home's one list, so Down from the last shelf reaches it and
     * Up leaves it.
     */
    var destFocused by mutableStateOf(false)
    var destIndex by mutableIntStateOf(0)

    var pageId by mutableStateOf<String?>(null)
    var menuId by mutableStateOf<String?>(null)
    /** The open menu starts on the Engine picker (the page's "Choose a runner"). */
    var menuEnginePicker by mutableStateOf(false)
    var setupOpen by mutableStateOf(false)
    var optionsOpen by mutableStateOf(false)
    var filterOpen by mutableStateOf(false)
    var sortOpen by mutableStateOf(false)
    var searchOpen by mutableStateOf(false)

    /**
     * Opens Home or PC Games, from the left menu's two rows. PC Games opens
     * on its Overview shelves; choosing it while one of its grid views shows
     * keeps that view (the row is already where the user is). Switching
     * resets the cursor, because the surfaces draw different lists.
     */
    fun open(home: Boolean) {
        if (home == this.home) return
        if (!home && view == PcView.GRID) return
        view = if (home) PcView.HOME else PcView.OVERVIEW
        stripFocused = false
        stripIndex = 0
        destFocused = false
        destIndex = 0
        shelfIndex = 0
        itemIndex = 0
        shelfItems.clear()
    }

    /** PC Games' Overview shelves: its strip's first chip, and B from a grid view. */
    fun showOverview() {
        if (view == PcView.OVERVIEW) return
        view = PcView.OVERVIEW
        shelfIndex = 0
        itemIndex = 0
        shelfItems.clear()
    }

    /** One of the strip's grid views, over [query]. */
    fun showGrid(query: LibraryQuery) {
        this.query = query
        view = PcView.GRID
        itemIndex = 0
    }

    /**
     * The grid filtered to one store (a store page's "Open library",
     * docs/SPEC.md 7j "Places"): the Source facet selected on the store's id,
     * nothing else. Marks the query loaded so the saved query is not read over it.
     */
    fun showSource(id: String) {
        query = LibraryQuery().withToggled(LibraryFacet.SOURCE, id, true)
        queryLoaded = true
        view = PcView.GRID
        stripFocused = false
        itemIndex = 0
        pageId = null
    }

    companion object {
        val Saver: Saver<PcGamesState, Any> = listSaver(
            save = { s -> listOf(s.view.ordinal, s.stripFocused, s.stripIndex, s.shelfIndex, s.itemIndex, s.pageId.orEmpty()) },
            restore = { v ->
                PcGamesState().apply {
                    view = PcView.entries.getOrNull(v.getOrNull(0) as? Int ?: 0) ?: PcView.HOME
                    stripFocused = v.getOrNull(1) as? Boolean ?: false
                    stripIndex = v.getOrNull(2) as? Int ?: 0
                    shelfIndex = v.getOrNull(3) as? Int ?: 0
                    itemIndex = v.getOrNull(4) as? Int ?: 0
                    pageId = (v.getOrNull(5) as? String)?.takeIf { it.isNotEmpty() }
                }
            },
        )
    }
}

/**
 * The PC_GAMES section's three surfaces (docs/SPEC.md 7i):
 *
 * - [HOME]: the left menu's "Home", recent activity across every library
 *   ([homeShelves]); no strip.
 * - [OVERVIEW]: what "PC Games" opens on, the PC library's own shelves
 *   ([pcShelves]) led by a hero card, under the view strip's first chip.
 * - [GRID]: one of the strip's grid views over the shared [LibraryQuery].
 */
internal enum class PcView { HOME, OVERVIEW, GRID }


/**
 * The PC Games tab (docs/SPEC.md 7i, 2026-10-01): droidtop's own library
 * of every PC and engine game, after the Steam Deck's library and Steam
 * Big Picture rather than after an ES-DE theme. Three parts, top to
 * bottom:
 *
 * - **The view strip** (PC Games only, not Home): Overview first, then the
 *   built-in views (All games, Installed, Updates, Favourites), one per
 *   store and the person's saved views, with the active filter as one pill
 *   at its end -- the Deck's library tabs.
 * - **Shelves**, each a horizontal row of large capsules led by a landscape
 *   hero card: Home's cross-library ones ([homeShelves]) or the PC
 *   library's own on Overview ([pcShelves]). **A grid view**: the same
 *   capsules as a grid over the one shared [LibraryQuery].
 * - **The hints**, declared into the shell footer: A names the focused
 *   game's primary action from [PcPlayState] (owner, 2026-10-01: "A is
 *   Primary Action. We can make it contextual using the pills"), X Filter,
 *   Y Sort By, Select the game's menu, B back to Overview from a grid view.
 *
 * ONE pad handler moves ONE cursor (docs/SPEC.md 6e): Up/Down between the
 * strip, the shelves and the grid's rows, Left/Right along a shelf, the
 * strip or a grid row, never wrapping; Up from the strip is consumed, so
 * the tab bar is never reached. A finger moves the same cursor by tapping
 * a capsule and presses it by tapping again; a chip is pressed at once.
 *
 * Nothing here reads a disk while drawing: the fold, the shelves and the
 * filtered grid are worked out off the main thread as the library
 * publishes, and the one lookup (the focused game's runner) runs for that
 * one game.
 */
@Composable
internal fun PcGamesSection(
    entries: List<LibraryEntry>,
    /** The Retro library and the launcher apps: Home's Continue playing and Recently added merge them in (docs/SPEC.md 7i, "Home art"). */
    retro: List<LibraryEntry>,
    apps: List<LibraryEntry>,
    library: Library,
    state: PcGamesState,
    onLaunch: (LibraryEntry) -> Unit,
    onToggleFavorite: (LibraryEntry) -> Unit,
    onShowDetail: (LibraryEntry) -> Unit,
    onFocusedEntryChanged: (LibraryEntry?) -> Unit,
    onHelpRowClaim: (HelpRowClaim) -> Unit,
    onCanGoBackChanged: (Boolean) -> Unit,
    onRequestRescan: () -> Unit,
    /** Home's destination row opens another section (Retro Games, PC Games). */
    onOpenSection: (GamingSection) -> Unit,
    /** The folded PC games plus [retro] and game apps that Home's shelves are built from, for the companion's Home. */
    onHomeActivityChanged: (List<LibraryEntry>) -> Unit = {},
) {
    val context = LocalContext.current
    val window = LocalShellWindow.current

    // ONE card per game, not per folder (docs/SPEC.md 7m), off the main
    // thread; null until the first fold so an empty library is never shown
    // for the moment a real one takes to fold.
    var folded by remember { mutableStateOf<FoldedPcLibrary?>(null) }
    // Bumped when the person marks a part finished, so Play moves on at once.
    var progressToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(entries, progressToken) {
        // A Windows setup Activity can briefly publish an empty library
        // while its providers resume. Keep the last usable snapshot until
        // the refreshed entries arrive instead of replacing the grid with
        // an empty state during that gap.
        if (entries.isEmpty() && folded?.games?.isNotEmpty() == true) return@LaunchedEffect
        folded = withContext(Dispatchers.Default) { foldPcLibrary(context, entries) }
    }
    val games = folded?.games
    // How many folders or store copies of one drawn game are on this device,
    // from the fold already made: a map read, never a lookup on disk. A store
    // row that is not installed is a game the person owns, not a copy they
    // have, so it is not counted (docs/SPEC.md 7m).
    fun partsOf(entry: LibraryEntry): Int =
        folded?.siblings?.get(entry.id)?.count { it.pcInfo?.installed != false } ?: 1

    // The person's game folders, the Source facet's folder values: a
    // preference read, once, off the main thread.
    var pcRoots by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(Unit) {
        pcRoots = withContext(Dispatchers.IO) {
            runCatching { dev.droidtop.library.GamesRoots.current(context).map { it.absolutePath } }.getOrDefault(emptyList())
        }
    }
    val scope = remember(pcRoots) {
        LibraryQueryScope(
            id = "pc",
            context = dev.droidtop.shell.gamepad.query.LibraryQueryContext(pcRoots = pcRoots),
            // Runner, ready and ProtonDB are left off: they cost a folder
            // walk or a network ask per entry, which a list never pays.
            facets = listOf(
                LibraryFacet.SOURCE, LibraryFacet.ENGINE, LibraryFacet.INSTALLED, LibraryFacet.FAVOURITES,
                LibraryFacet.PLAYED, LibraryFacet.RECENTLY_PLAYED, LibraryFacet.GENRE, LibraryFacet.DEVELOPER,
                LibraryFacet.YEAR, LibraryFacet.UPDATE, LibraryFacet.MISSING_ART, LibraryFacet.HIDDEN,
            ),
            sorts = listOf(
                LibrarySortKey.NAME, LibrarySortKey.RECENT, LibrarySortKey.ADDED, LibrarySortKey.PLAYTIME,
                LibrarySortKey.YEAR, LibrarySortKey.RATING, LibrarySortKey.SIZE,
            ),
        )
    }
    PersistQuery(scope.id, state.query, state.queryLoaded) {
        state.query = it
        state.queryLoaded = true
    }
    // The strip's counts (All, Installed, Updates, Favourites), worked out with
    // the shelves; Updates and Favourites show only when they hold something.
    var counts by remember { mutableStateOf(emptyMap<String, Int>()) }
    val savedViews = rememberSavedViews(scope.id)
    val views = pcStripViews(counts, savedViews.views)

    // What Home's Continue playing and Recently added take besides PC games:
    // Retro games that have a time to be placed by, and the launcher apps that
    // are games (the person's marks and Android's own flag, from the rules
    // loaded once with the system names the badges read). All off the main
    // thread; the entries already carry play history and added time, so
    // there is no per-card lookup.
    var systemNames by remember { mutableStateOf(emptyMap<String, String>()) }
    var others by remember { mutableStateOf(emptyList<LibraryEntry>()) }
    LaunchedEffect(retro, apps) {
        val loaded = withContext(Dispatchers.IO) {
            val rules = if (apps.isEmpty()) null else AppCategoryRules.load(context)
            val names = if (retro.isEmpty()) emptyMap<String, String>() else ConsoleSystemsRepository.allSystems(context).associate { it.id to it.displayName }
            val gameApps = if (rules == null) emptyList<LibraryEntry>() else apps.filter { rules.isGame(it.id, it.appFacts) }
            names to (retro.filter { !it.hidden && it.addedEpochMs() + (it.lastPlayedEpochMs ?: 0L) > 0L } + gameApps)
        }
        systemNames = loaded.first
        others = loaded.second
    }
    // Home's shelves (recent activity across every library) and PC Games'
    // Overview (the PC library's own), from the one shelf builder.
    var homeShelfList by remember { mutableStateOf(emptyList<PcShelf>()) }
    var pcShelfList by remember { mutableStateOf(emptyList<PcShelf>()) }
    // A scan still running republishes the library and the shelves move: the
    // cursor stays on its game ([cursorAfter]) instead of on a position.
    fun keepCursor(old: List<PcShelf>, new: List<PcShelf>) {
        val (shelf, item) = cursorAfter(old, new, state.shelfIndex, state.itemIndex)
        state.shelfIndex = shelf
        state.itemIndex = item
    }
    // Shelves plugins ask for, after Home's own (docs/plugin-api.md 3 C11, Droidtop/tracker#316). Home's own are
    // shown first with the plugin shelves it already had; the plugins' (from an answer kept for 15 minutes, so a
    // library change rarely asks them) follow when ready. Real entries only.
    var pluginShelfList by remember { mutableStateOf(emptyList<PcShelf>()) }
    LaunchedEffect(games, others) {
        val all = games ?: return@LaunchedEffect
        onHomeActivityChanged(all + others)
        val own = withContext(Dispatchers.Default) { withRetroHero(homeShelves(all, others)) }
        val next = own + pluginShelfList
        if (state.home && !state.stripFocused) keepCursor(homeShelfList, next)
        homeShelfList = next
        val home = all + others
        val fresh = withContext(Dispatchers.IO) {
            pluginHomeShelves(dev.droidtop.library.integrations.PluginShelves.shelvesFor(context, home), home)
        }
        if (fresh != pluginShelfList) {
            pluginShelfList = fresh
            if (state.home && !state.stripFocused) keepCursor(homeShelfList, own + fresh)
            homeShelfList = own + fresh
        }
    }
    LaunchedEffect(games) {
        val all = games ?: return@LaunchedEffect
        val next = withContext(Dispatchers.Default) { pcShelves(all) }
        if (state.view == PcView.OVERVIEW && !state.stripFocused) keepCursor(pcShelfList, next)
        pcShelfList = next
    }
    val shelves = if (state.home) homeShelfList else pcShelfList
    LaunchedEffect(games, scope) {
        val all = games ?: return@LaunchedEffect
        counts = withContext(Dispatchers.Default) { pcViewCounts(all, scope) }
    }
    var grid by remember { mutableStateOf(emptyList<LibraryEntry>()) }
    LaunchedEffect(games, state.query, scope) {
        val all = games ?: return@LaunchedEffect
        val query = state.query
        grid = withContext(Dispatchers.Default) { query.applyTo(all, scope) }
    }

    // The library, as this tab shows it right now, and the game under the cursor.
    val currentShelf = shelves.getOrNull(state.shelfIndex)
    val currentList: List<LibraryEntry> = if (state.onShelves) currentShelf?.entries.orEmpty() else grid
    LaunchedEffect(shelves.size, currentList.size) {
        state.shelfIndex = state.shelfIndex.coerceIn(0, (shelves.size - 1).coerceAtLeast(0))
        state.itemIndex = state.itemIndex.coerceIn(0, (currentList.size - 1).coerceAtLeast(0))
    }
    // Home's destination row is the list's last stop, and the only one when
    // nothing is on the shelves yet.
    val onDest = state.home && (state.destFocused || shelves.isEmpty())
    val focusedEntry = if (state.stripFocused || onDest) null else currentList.getOrNull(state.itemIndex)
    LaunchedEffect(focusedEntry?.id) { onFocusedEntryChanged(focusedEntry) }
    // Only a PC game has a runner to resolve; a Retro game or app just plays.
    val focusedPlay = focusedEntry?.takeIf { it.inPcFold }?.let { rememberPcPlayState(it) }

    // A store game's primary action is its store's own screen (install,
    // update, download), the same answer the capsule badge and the page
    // button read ([storeStageOf]); everything else launches, a multi-part
    // game's card launching the part to continue with, not always the first
    // (docs/SPEC.md 7n). An install or update stops on the free-space
    // offer first (Droidtop/tracker#227): the size and the room the chosen
    // game folder has are named before anything downloads; a download
    // already running goes straight to the store's queue.
    // The rule is shared with every host outside Gaming (rememberPcLaunch, Droidtop/tracker#349).
    val downloads by StoreDownloads.active.collectAsState()
    val pcLaunch = rememberPcLaunch(
        onLaunch = { entry -> onLaunch(folded?.continuing?.get(entry.id) ?: entry) },
        onOpenDownloads = { onOpenSection(GamingSection.DOWNLOADS) },
    )
    val launch: (LibraryEntry) -> Unit = pcLaunch.launch
    // A on a capsule plays it, except a Windows game whose environment is not
    // set up yet: that opens the game page, whose primary button is the setup
    // step, rather than a download offer over the grid (Droidtop/tracker#293).
    val activate: (LibraryEntry) -> Unit = { entry ->
        val needsWindowsSetup = entry.inPcFold && entry.id == focusedEntry?.id &&
            focusedPlay?.second?.option?.let { it.state != RunnerState.READY && it.action == RunnerAction.SET_UP_WINDOWS_GAMES } == true
        if (needsWindowsSetup) state.pageId = entry.id else launch(entry)
    }
    // Select and a long press on a PC game open its menu and page; on a Retro
    // game or an app from Home's mixed shelves, a small options menu that
    // launches through the same path as its own tab.
    var otherMenu by remember { mutableStateOf<LibraryEntry?>(null) }
    fun openOptions(entry: LibraryEntry) {
        if (entry.inPcFold) state.menuId = entry.id else otherMenu = entry
    }
    fun openPage(entry: LibraryEntry) {
        if (entry.inPcFold) state.pageId = entry.id else otherMenu = entry
    }
    // The backdrop follows the game under the cursor (docs/SPEC.md 7i, "Home
    // art"); while the cursor is on the strip it keeps the last game's, and
    // the games either side of the cursor are preloaded so a step shows the
    // next one at once.
    var backdropArt by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedEntry?.id) { focusedEntry?.let { backdropArt = it.backdropArt() } }
    PreloadBackdrops(remember(currentList, state.itemIndex) { neighbourBackdrops(currentList, state.itemIndex) })

    // The strip: Overview (the shelves) first, then one chip per grid view
    // (built-in, store, saved). Chip 0 is Overview and chip i is views[i - 1].
    // Home has no strip; B from a grid view returns to Overview.
    val stripCount = views.size + 1
    fun activateChip(index: Int) {
        if (index !in 0 until stripCount) return
        state.stripIndex = index
        if (index == 0) {
            state.showOverview()
            return
        }
        val view = views[index - 1]
        if (state.view == PcView.GRID && state.query == view.query) return
        state.showGrid(view.query)
    }
    val currentView = views.firstOrNull { it.query == state.query }
    // The chip that is lit: Overview on the shelves, the grid's view when one
    // stands for its query, none when only the pill describes it.
    val activeChip = when {
        state.onShelves -> 0
        currentView != null -> views.indexOf(currentView) + 1
        else -> -1
    }
    // The filters the person set that no strip view stands for, as the one
    // pill at the strip's end: its text and a count, cleared by one press.
    val filterPill = remember(grid, games, state.query, state.view, currentView != null) {
        if (state.onShelves || currentView != null) return@remember null
        state.query.pillText(scope, grid.size, state.query.totalIn(games.orEmpty(), scope))
    }

    // Which level B leaves (docs/SPEC.md 6e: B always goes back one level).
    // Home is the root: B does nothing there (Overview goes back to it).
    val storesScreen = remember { SettingsScreenRegistry.get(PC_STORES_SCREEN_ID) }
    val knownEmpty = games?.isEmpty() == true
    val showingSetup = (state.setupOpen || knownEmpty) && storesScreen != null
    val canGoBack = state.setupOpen || state.view != PcView.HOME
    LaunchedEffect(canGoBack) {
        // The shell's one footer draws this tab's hints too: the focused
        // element declares them ([declaresHints] below), so the tab claims
        // no row of its own.
        onHelpRowClaim(HelpRowClaim.NONE)
        onCanGoBackChanged(canGoBack)
    }
    BackHandler(enabled = canGoBack && !showingSetup) {
        EsDeNavigationSounds.play(UiSound.BACK)
        state.stripIndex = 0
        // A grid view goes back to Overview, Overview back to Home, the hub.
        if (state.view == PcView.GRID) state.showOverview() else state.open(home = true)
    }

    if (showingSetup) {
        // "PC setup" (game folders, the Windows system files, Downloads):
        // the level above the library, and what an empty library opens
        // on instead of nothing (docs/SPEC.md 7i). CatalogNavigator owns
        // its own B; at its root that leaves the level.
        CatalogNavigator(root = storesScreen!!, onExit = { state.setupOpen = false })
        return
    }

    // L1/R1 step the strip's views in order, never wrapping (menuStep). Home
    // has no strip, so they do nothing there. The strip owns the press even at its end, so the shoulders never move
    // the whole page to another tab from here (docs/SPEC.md 7j, "Gaming
    // controls"). The cursor stays where it is: stepping a view from the
    // grid does not pull the cursor up onto the strip.
    OwnShoulders { step ->
        if (state.home) return@OwnShoulders
        val active = activeChip.takeIf { it >= 0 } ?: state.stripIndex.coerceIn(0, stripCount - 1)
        val next = menuStep(active, stripCount, step)
        if (next != active) {
            EsDeNavigationSounds.play(UiSound.TAB)
            activateChip(next)
        } else {
            // A shoulder at the strip's end goes nowhere (its glyph is already at half strength).
            EsDeNavigationSounds.play(UiSound.BUMP)
        }
    }

    // What this tab's focus promises, X and Y being the list's Filter and
    // Sort By (docs/SPEC.md 7j), Select the focused game's menu: only what
    // dispatches, re-read as the cursor moves.
    val verb = focusedPlay?.first?.verb
    val hints = remember(verb, state.stripFocused, focusedEntry?.id, state.view, onDest) {
        // Steam's order: the list's own actions, then A and B. Start (Menu)
        // is the shell's, drawn at the row's left; L1/R1 are the glyphs at
        // the strip's ends, not hints.
        listOf(
            HintBinding(GamepadAction.X, "Filter"),
            HintBinding(GamepadAction.Y, "Sort By"),
            HintBinding(GamepadAction.SELECT, "Options"),
            HintBinding(GamepadAction.A, if (onDest) "Open" else if (state.stripFocused) "Select" else verb ?: "Play") {
                onDest || state.stripFocused || focusedEntry != null
            },
            HintBinding(GamepadAction.B, "Back") { state.view != PcView.HOME },
        )
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus, "PC Games") }
    var heldStep by remember { mutableStateOf(false) }
    val columnState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val stripState = rememberLazyListState()
    val rowStates = remember { mutableMapOf<String, LazyListState>() }
    fun rowState(id: String) = rowStates.getOrPut(id) { LazyListState() }
    fun gridColumns(): Int = (gridState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.column } ?: 0) + 1

    // The selection is always on screen: a strip, a shelf or the grid centres
    // it, eased on the first press and linear while a direction is held; the
    // page of shelves moves by as little as it takes (docs/SPEC.md 6e and
    // "Gaming motion and focus").
    LaunchedEffect(state.view, state.stripFocused, state.stripIndex, state.shelfIndex, state.itemIndex, shelves, grid, onDest) {
        when {
            state.stripFocused -> stripState.keepCentred(state.stripIndex, chained = heldStep)
            onDest -> columnState.keepInView(shelves.size, animate = !heldStep)
            state.onShelves -> {
                val shelf = shelves.getOrNull(state.shelfIndex) ?: return@LaunchedEffect
                columnState.keepInView(state.shelfIndex, animate = !heldStep)
                rowState(shelf.id).keepCentred(state.itemIndex, chained = heldStep)
            }
            else -> if (grid.isNotEmpty()) gridState.keepCentred(state.itemIndex, chained = heldStep)
        }
    }

    // L1/R1 change the view while the cursor is elsewhere: the chip they
    // landed on is kept in view too, not only when the cursor is on the strip.
    LaunchedEffect(state.stripIndex) {
        if (!state.stripFocused) stripState.keepInView(state.stripIndex, animate = true)
    }

    fun moveTo(shelf: Int, item: Int) {
        if (shelf != state.shelfIndex || item != state.itemIndex) EsDeNavigationSounds.play(UiSound.MOVE)
        currentShelf?.let { state.shelfItems[it.id] = state.itemIndex }
        state.shelfIndex = shelf
        state.itemIndex = item
    }

    Box(modifier = Modifier.fillMaxSize()) {
    PcBackdrop(art = backdropArt)
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusRequester(focus)
                .declaresHints(hints)
                .focusable()
                .onPad { press ->
                    heldStep = press.repeat
                    when (press.action) {
                        GamepadAction.UP -> when {
                            // Back from Home's destination row to the last shelf.
                            onDest -> if (shelves.isNotEmpty()) state.destFocused = false
                            // Never the tab bar (owner, 2026-09-27): the top
                            // of this tab is its strip, and Up there stays.
                            state.stripFocused -> Unit
                            // Overview's first shelf goes up to the strip;
                            // Home has no strip above its first shelf.
                            state.onShelves -> if (state.shelfIndex > 0) {
                                val next = state.shelfIndex - 1
                                moveTo(next, state.shelfItems[shelves[next].id] ?: 0)
                            } else if (!state.home) {
                                state.stripFocused = true
                            }
                            else -> {
                                val target = gridPadTarget(state.itemIndex, grid.size, gridColumns(), FocusDirection.Up)
                                if (target == null) state.stripFocused = true else moveTo(state.shelfIndex, target)
                            }
                        }
                        GamepadAction.DOWN -> when {
                            onDest -> Unit
                            state.stripFocused -> if (currentList.isNotEmpty()) state.stripFocused = false
                            state.onShelves -> if (state.shelfIndex < shelves.lastIndex) {
                                val next = state.shelfIndex + 1
                                moveTo(next, state.shelfItems[shelves[next].id] ?: 0)
                            } else if (state.home) {
                                EsDeNavigationSounds.play(UiSound.MOVE)
                                state.destFocused = true
                            }
                            else -> gridPadTarget(state.itemIndex, grid.size, gridColumns(), FocusDirection.Down)
                                ?.let { moveTo(state.shelfIndex, it) }
                        }
                        GamepadAction.LEFT, GamepadAction.RIGHT -> {
                            val step = if (press.action == GamepadAction.LEFT) -1 else 1
                            when {
                                onDest -> {
                                    val next = menuStep(state.destIndex, HOME_DESTINATIONS.size, step)
                                    if (next != state.destIndex) EsDeNavigationSounds.play(UiSound.MOVE)
                                    state.destIndex = next
                                }
                                state.stripFocused -> {
                                    val next = menuStep(state.stripIndex, stripCount, step)
                                    if (next != state.stripIndex) EsDeNavigationSounds.play(UiSound.MOVE)
                                    state.stripIndex = next
                                }
                                state.onShelves -> moveTo(state.shelfIndex, menuStep(state.itemIndex, currentList.size, step))
                                else -> gridPadTarget(
                                    state.itemIndex, grid.size, gridColumns(),
                                    if (step < 0) FocusDirection.Left else FocusDirection.Right,
                                )?.let { moveTo(state.shelfIndex, it) }
                            }
                        }
                        GamepadAction.A -> {
                            if (onDest) HOME_DESTINATIONS.getOrNull(state.destIndex)?.let { onOpenSection(it.section) }
                            else if (state.stripFocused) activateChip(state.stripIndex) else focusedEntry?.let(activate)
                        }
                        GamepadAction.X -> state.filterOpen = true
                        GamepadAction.Y -> state.sortOpen = true
                        // Options is the focused game's menu (L2 stays its
                        // alias); with no game under the cursor it is the
                        // list's own options.
                        GamepadAction.SELECT, GamepadAction.L2 ->
                            focusedEntry?.let(::openOptions) ?: run { state.optionsOpen = true }
                        else -> return@onPad false
                    }
                    true
                },
        ) {
            // The one strip above the shelves or the grid, with L1 and R1 at
            // its ends: it owns the shoulders while this tab is up
            // (OwnShoulders above), and the active filter is its last pill.
            if (!state.home) ViewStrip(
                labels = listOf(VIEW_OVERVIEW) + views.map { pcStripLabel(it, counts) },
                active = activeChip,
                focused = if (state.stripFocused) state.stripIndex else null,
                pill = filterPill,
                onSelect = { index ->
                    state.stripFocused = true
                    activateChip(index)
                },
                onClearPill = {
                    state.query = state.query.cleared
                    state.itemIndex = 0
                },
                state = stripState,
            )
            when {
                games == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MenuTokens.OnSurface)
                }
                state.onShelves -> PcShelvesHome(
                    shelves = shelves,
                    state = state,
                    onDest = onDest,
                    onOpenSection = { index ->
                        state.destIndex = index
                        onOpenSection(HOME_DESTINATIONS[index].section)
                    },
                    columnState = columnState,
                    rowState = ::rowState,
                    onTapCapsule = { shelf, item, entry ->
                        state.stripFocused = false
                        state.destFocused = false
                        if (state.shelfIndex == shelf && state.itemIndex == item) activate(entry) else moveTo(shelf, item)
                    },
                    onLongPressCapsule = ::openPage,
                    downloads = downloads,
                    partsOf = ::partsOf,
                    systemNames = systemNames,
                    mixed = state.home,
                )
                else -> {
                    if (grid.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No games match this view", color = MenuTokens.OnSurfaceMuted, style = TypeRole.body)
                        }
                    } else {
                        // Whole capsules at the tier width, 12dp apart, the
                        // row centred in what is left (Steam's library grid);
                        // a cell is never stretched past the capsule.
                        val width = capsuleWidth()
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.FixedSize(width),
                            contentPadding = PaddingValues(start = window.edgePadding, end = window.edgePadding, top = Space.Sm, bottom = Space.Lg),
                            horizontalArrangement = Arrangement.spacedBy(Space.Md, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(Space.Md),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            gridItemsIndexed(grid, key = { _, entry -> entry.id }) { index, entry ->
                                PcCapsule(
                                    entry = entry,
                                    selected = !state.stripFocused && state.itemIndex == index,
                                    width = width,
                                    onTap = {
                                        state.stripFocused = false
                                        if (state.itemIndex == index) activate(entry) else moveTo(state.shelfIndex, index)
                                    },
                                    onLongPress = { state.pageId = entry.id },
                                    download = entry.downloadKey()?.let { downloads[it] },
                                    parts = partsOf(entry),
                                )
                            }
                        }
                    }
                }
            }
        }
        // The focused game's name and facts, said once (docs/SPEC.md 7i)
        // instead of on every capsule.
        focusedEntry?.let { entry ->
            Text(
                focusLine(entry, focusedPlay?.first, partsOf(entry)),
                color = MenuTokens.OnSurfaceMuted,
                style = TypeRole.supporting,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = window.edgePadding, vertical = Space.Xs),
            )
        }
    }
    }

    // The windows this tab opens over itself. Each is its own window and
    // takes the pad through the pipeline's front (docs/SPEC.md 6e).
    if (state.filterOpen) {
        LibraryFilterSheet(
            scope = scope,
            base = games.orEmpty(),
            query = state.query,
            savedViews = views,
            onQueryChange = { query -> state.showGrid(query) },
            onSearch = {
                state.filterOpen = false
                state.searchOpen = true
            },
            onSaveView = { name -> savedViews.save(name, state.query) },
            onForgetView = { name -> savedViews.forget(name) },
            onDismiss = { state.filterOpen = false },
            // The list's own options (jump to a letter, scrape, PC setup and
            // the stores) are one row from here, and from Select when no
            // game is under the cursor.
            footerActions = listOf(
                SheetAction("List options", "Jump to a letter, scrape, game folders and stores") {
                    state.filterOpen = false
                    state.optionsOpen = true
                },
            ),
        )
    }
    if (state.sortOpen) {
        LibrarySortSheet(
            scope = scope,
            query = state.query,
            onQueryChange = { query -> state.showGrid(query) },
            onDismiss = { state.sortOpen = false },
        )
    }
    if (state.searchOpen) {
        val all = games.orEmpty()
        // Recommendations for the empty field, worked out off the main
        // thread while the dialog is open (docs/SPEC.md 12a).
        val suggestions by produceState(emptyList<dev.droidtop.library.integrations.Recommendation>(), all) {
            value = dev.droidtop.library.integrations.LocalSimilarityRecommendations({ all })
                .recommend(context, dev.droidtop.library.integrations.RecommendationScope.Overall, 5)
        }
        LibrarySearchDialog(
            query = state.query,
            matchCount = grid.size,
            totalCount = all.size,
            onTextChange = { state.showGrid(state.query.copy(text = it)) },
            onDismiss = { state.searchOpen = false },
            suggestions = suggestions,
        )
    }
    if (state.optionsOpen) {
        // The list's own options (jump to letter, random, get games, the
        // PC scrape, PC setup): the same menu every gamelist's Select
        // opens, over the list as it is shown right now.
        val listed = if (state.onShelves) games.orEmpty() else grid
        GamelistOptionsMenu(
            groupKey = "PC",
            groupLabel = "PC Games",
            systemId = PC_SYSTEM_ID,
            onScraped = onRequestRescan,
            onDismiss = { state.optionsOpen = false },
            games = listed,
            onJumpTo = { index ->
                if (state.onShelves) {
                    // The letters were counted over the whole library in
                    // name order, which is the All games view.
                    state.showGrid(LibraryQuery())
                }
                state.stripFocused = false
                state.itemIndex = index.coerceIn(0, (listed.size - 1).coerceAtLeast(0))
            },
            onOpenStores = { state.setupOpen = true },
        )
    }
    // A page or menu open on a game a rescan no longer has closes itself
    // instead of showing a game that is not there any more.
    val pageEntry = state.pageId?.let { id -> games?.firstOrNull { it.id == id } }
    val menuEntry = state.menuId?.let { id -> entries.firstOrNull { it.id == id } }
    LaunchedEffect(pageEntry, menuEntry, games) {
        if (games != null && state.pageId != null && pageEntry == null) state.pageId = null
        if (state.menuId != null && menuEntry == null) state.menuId = null
    }
    if (pageEntry != null) {
        PcGamePage(
            entry = pageEntry,
            siblings = folded?.siblings?.get(pageEntry.id) ?: listOf(pageEntry),
            onPlay = { launch(pageEntry) },
            onToggleFavorite = { onToggleFavorite(pageEntry) },
            onOpenOptions = { state.menuId = pageEntry.id },
            onChooseEngine = {
                state.menuEnginePicker = true
                state.menuId = pageEntry.id
            },
            onClose = { state.pageId = null },
            library = library,
        )
    }
    if (menuEntry != null) {
        PcGameMenu(
            entry = menuEntry,
            library = library,
            onLaunch = {
                state.pageId = null
                launch(menuEntry)
            },
            onProgressChanged = { progressToken++ },
            onClose = { state.menuId = null; state.menuEnginePicker = false },
            openEnginePicker = state.menuEnginePicker,
            // Every PC/engine game the shell has, so the menu can offer the
            // other folders of the same game (docs/SPEC.md 7m).
            siblings = entries.filter { it.isPcOrEngineGame },
            // Sideways, not deeper: another folder of the same game
            // replaces which entry this SAME menu is showing.
            onOpenOther = { state.menuId = it.id },
            onOpenPage = {
                state.menuId = null
                state.pageId = menuEntry.id
            },
            onOpenDownloads = {
                state.menuId = null
                onOpenSection(GamingSection.DOWNLOADS)
            },
        )
    }
    otherMenu?.let { entry ->
        AppOptionsMenu(
            entry = entry,
            detailsLabel = if (entry.appFacts != null) "App details" else "Game details",
            onDetails = {
                otherMenu = null
                onShowDetail(entry)
            },
            onToggleFavorite = {
                otherMenu = null
                onToggleFavorite(entry)
            },
            onDismiss = { otherMenu = null },
        )
    }
    // The free-space offer before a store install or update.
    PcLaunchOfferSheet(pcLaunch)
}

/**
 * The shelves, one horizontal row of capsules each, with the cursor's shelf
 * heading drawn brighter: Home's and PC Games' Overview alike. [mixed] is
 * Home, which also has the destination row; every card carries a kind badge.
 */
@Composable
private fun PcShelvesHome(
    shelves: List<PcShelf>,
    state: PcGamesState,
    onDest: Boolean,
    onOpenSection: (Int) -> Unit,
    columnState: LazyListState,
    rowState: (String) -> LazyListState,
    onTapCapsule: (shelf: Int, item: Int, entry: LibraryEntry) -> Unit,
    onLongPressCapsule: (LibraryEntry) -> Unit,
    downloads: Map<String, StoreDownloads.Progress>,
    partsOf: (LibraryEntry) -> Int,
    systemNames: Map<String, String>,
    mixed: Boolean,
) {
    val window = LocalShellWindow.current
    if (shelves.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.Lg)) {
                Text("Nothing on the shelves yet", color = MenuTokens.OnSurfaceMuted, style = TypeRole.body)
                if (mixed) HomeDestinations(selected = state.destIndex.takeIf { onDest }, onOpen = onOpenSection)
                dev.droidtop.shell.gamepad.GetGamesChip(dev.droidtop.library.integrations.GetGamesContext.EMPTY_STATE)
            }
        }
        return
    }
    val width = capsuleWidth()
    val heroCardWidth = heroWidth(width)
    // The shelves rise in one after another when the page first appears
    // (docs/SPEC.md "Gaming motion and focus"); a shelf scrolled back into
    // view later is simply there.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(Motion.ms(Motion.RiseMs + Motion.riseDelay(Motion.RiseMaxSteps)))
        entered = true
    }
    LazyColumn(
        state = columnState,
        modifier = Modifier.fillMaxSize(),
        // Home has no strip: its first row starts under the floating status
        // cluster, the band Steam's top bar takes, never beneath it.
        contentPadding = PaddingValues(
            top = if (mixed) maxOf(Space.Sm, StatusClusterRoom.size.height) else Space.Sm,
            bottom = Space.Lg,
        ),
        // Steam's home spacing: 24dp between shelves, 12dp between capsules.
        verticalArrangement = Arrangement.spacedBy(Space.Xl),
    ) {
        itemsIndexed(shelves, key = { _, shelf -> shelf.id }) { shelfIndex, shelf ->
            val onThisShelf = !state.stripFocused && !onDest && state.shelfIndex == shelfIndex
            Column(if (entered) Modifier else Modifier.rise(shelfIndex)) {
                // The hero row of what was being played carries no heading,
                // as the Deck's recent row has none: the hero card's own
                // caption ("Played today · 2 h") says what the row is.
                // Steam's shelf heading: the heading weight, quieter until
                // the cursor is on the shelf.
                if (!(shelfIndex == 0 && shelf.id == SHELF_CONTINUE)) Text(
                    shelf.heading,
                    color = if (onThisShelf) MenuTokens.OnSurface else MenuTokens.OnSurfaceMuted,
                    style = TypeRole.screenTitle,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        start = window.edgePadding,
                        end = window.edgePadding,
                        bottom = Space.Sm,
                    ),
                )
                LazyRow(
                    state = rowState(shelf.id),
                    contentPadding = PaddingValues(horizontal = window.edgePadding),
                    horizontalArrangement = Arrangement.spacedBy(Space.Md),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    itemsIndexed(shelf.entries, key = { _, entry -> entry.id }) { itemIndex, entry ->
                        // The game most likely wanted leads the first shelf as a
                        // landscape hero card (docs/SPEC.md 7i, "Home art").
                        val hero = isHeroCard(shelfIndex, itemIndex)
                        PcCapsule(
                            entry = entry,
                            selected = onThisShelf && state.itemIndex == itemIndex,
                            width = if (hero) heroCardWidth else width,
                            onTap = { onTapCapsule(shelfIndex, itemIndex, entry) },
                            onLongPress = { onLongPressCapsule(entry) },
                            download = entry.downloadKey()?.let { downloads[it] },
                            parts = partsOf(entry),
                            hero = hero,
                            // Every shelf says what each game is (PC, Retro, App, Engine) and where it is from.
                            badge = kindBadgeOf(entry, systemNames),
                        )
                    }
                }
            }
        }
        // Home's way to the libraries: the Retro and PC menus, the last stop of
        // the one list rather than a bar above it.
        if (mixed) item(key = "home-destinations") {
            HomeDestinations(
                selected = state.destIndex.takeIf { onDest },
                onOpen = onOpenSection,
                modifier = if (entered) Modifier else Modifier.rise(shelves.size),
            )
        }
    }
}

/** A tile on Home's destination row: where it goes and what it is called. */
internal class HomeDestination(val section: GamingSection, val label: String)

/** Home's destination row: the Retro and PC game menus, in the left menu's order. */
internal val HOME_DESTINATIONS = listOf(
    HomeDestination(GamingSection.GAMES, "Retro Games"),
    HomeDestination(GamingSection.PC_GAMES, "PC Games"),
)

/**
 * Home's destination row (Droidtop/tracker#273): one large tile per library.
 * Drawn by the page that owns the cursor, so [selected] says which tile the
 * pad is on; a tap opens at once, as a strip chip does.
 */
@Composable
private fun HomeDestinations(selected: Int?, onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    val window = LocalShellWindow.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(Space.Md),
        modifier = modifier.fillMaxWidth().padding(horizontal = window.edgePadding),
    ) {
        HOME_DESTINATIONS.forEachIndexed { index, destination ->
            dev.droidtop.shell.gamepad.ShellChip(
                destination.label,
                large = true,
                selected = selected == index,
                onClick = { onOpen(index) },
            )
        }
    }
}
