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
import dev.droidtop.shell.gamepad.query.LibraryViewPrefs
import dev.droidtop.shell.gamepad.query.NamedLibraryView
import dev.droidtop.shell.gamepad.query.OwnershipOptions
import dev.droidtop.shell.gamepad.query.StripTabs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import dev.droidtop.library.PcSource
import dev.droidtop.library.CollectionMembership
import dev.droidtop.library.CollectionScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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

    /** Shelves of capsules (Home or PC Games' Overview), not the grid or the collections. */
    val onShelves: Boolean get() = view == PcView.HOME || view == PcView.OVERVIEW

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
        if (!home && (view == PcView.GRID || view == PcView.COLLECTIONS)) return
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

    /** The Collections tab: the person's collections and saved views as tiles. */
    fun showCollections() {
        view = PcView.COLLECTIONS
        itemIndex = 0
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
 * - [COLLECTIONS]: the Collections tab.
 */
internal enum class PcView { HOME, OVERVIEW, GRID, COLLECTIONS }


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
    val coroutines = rememberCoroutineScope()

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

    // The person's game folders, the Source facet's folder values, and the
    // ownership List options (docs/SPEC.md 7j): preference reads, once, off
    // the main thread. Null until read.
    var pcRoots by remember { mutableStateOf<List<String>?>(null) }
    var ownership by remember { mutableStateOf(OwnershipOptions()) }
    var stripTabs by remember { mutableStateOf(StripTabs()) }
    // Which games came through a launcher (Lutris): one read of the game settings.
    var viaByEntry by remember { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(Unit) {
        val (roots, options) = withContext(Dispatchers.IO) {
            runCatching { dev.droidtop.library.GamesRoots.current(context).map { it.absolutePath } }.getOrDefault(emptyList()) to
                LibraryViewPrefs.ownershipOptions(context)
        }
        ownership = options
        stripTabs = withContext(Dispatchers.IO) { LibraryViewPrefs.stripTabs(context) }
        viaByEntry = withContext(Dispatchers.IO) { runCatching { dev.droidtop.library.PcLaunchers.viaByEntry(context) }.getOrDefault(emptyMap()) }
        pcRoots = roots
    }
    fun setStripTabs(next: StripTabs) {
        stripTabs = next
        coroutines.launch { withContext(Dispatchers.IO) { LibraryViewPrefs.setStripTabs(context, next) } }
    }
    fun setOwnership(next: OwnershipOptions) {
        ownership = next
        coroutines.launch { withContext(Dispatchers.IO) { LibraryViewPrefs.setOwnershipOptions(context, next) } }
    }
    // Which games are in which collections (docs/SPEC.md 7i, "Collections"):
    // two whole-table reads, again whenever a collection changes, never a
    // read per game. A merged card is a member if any of its copies is.
    val membership by remember { CollectionMembership.flow(context) }.collectAsState(CollectionMembership())
    val copiesOf: (LibraryEntry) -> List<String> = remember(folded) {
        val siblings = folded?.siblings.orEmpty();
        { card -> siblings[card.id]?.map { it.id } ?: listOf(card.id) }
    }
    val scope = remember(pcRoots, ownership, viaByEntry, membership, copiesOf) {
        val names = membership.collections.associate { collection ->
            collection.id to CollectionScope.shortName(collection, CollectionScope.importedFrom(collection.id)?.let { PcSource.Store(it).label() })
        }
        LibraryQueryScope(
            id = LibraryViewPrefs.PC_SCOPE_ID,
            context = dev.droidtop.shell.gamepad.query.LibraryQueryContext(
                pcRoots = pcRoots.orEmpty(),
                viaOf = { viaByEntry[it.id] },
                collectionsOf = { membership.collectionsOf(copiesOf(it)) },
                collectionName = { names[it] },
            ),
            ownership = ownership,
            // Runner, ready and ProtonDB are left off: they cost a folder
            // walk or a network ask per entry, which a list never pays.
            facets = listOf(
                LibraryFacet.SOURCE, LibraryFacet.OWNERSHIP, LibraryFacet.KIND, LibraryFacet.COLLECTION, LibraryFacet.IMPORTED_FROM,
                LibraryFacet.ENGINE, LibraryFacet.INSTALLED, LibraryFacet.FAVOURITES,
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
    // The strip (docs/SPEC.md 7i): the fixed built-in tabs, then the person's
    // pinned views, each grid tab with its count, worked out with the shelves.
    var counts by remember { mutableStateOf(emptyMap<String, Int>()) }
    val savedViews = rememberSavedViews(scope.id)
    val tabs = remember(savedViews.views, stripTabs) { pcStripTabs(savedViews.views, stripTabs) }
    val gridViews = remember(tabs) { tabs.filterIsInstance<PcTab.Grid>().map { it.view } }
    // A pinned tab being edited (Select > Edit): its new name, saved in place from the Filter sheet.
    var editing by remember { mutableStateOf<NamedLibraryView?>(null) }
    var tabMenu by remember { mutableStateOf<NamedLibraryView?>(null) }
    var renaming by remember { mutableStateOf<NamedLibraryView?>(null) }

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
    // Recently added is what a source added since its first sync or scan
    // (docs/SPEC.md 7g): the baselines, read and moved off the main thread
    // as the library publishes, once the game folders are known.
    var baselines by remember { mutableStateOf<Map<String, dev.droidtop.library.SyncBaselines.Baseline>?>(null) }
    LaunchedEffect(games, pcRoots) {
        val all = games ?: return@LaunchedEffect
        val roots = pcRoots ?: return@LaunchedEffect
        baselines = withContext(Dispatchers.IO) {
            val kept = dev.droidtop.library.SyncBaselines.load(context)
            val rows = all.mapNotNull { entry -> PcSource.of(entry, roots)?.let { it.id to entry.firstSeenEpochMs } }
            dev.droidtop.library.SyncBaselines.update(kept, rows, System.currentTimeMillis())
                .also { if (it != kept) dev.droidtop.library.SyncBaselines.save(context, it) }
        }
    }
    val recentlyAdded: (LibraryEntry) -> Boolean = remember(baselines, pcRoots) {
        val known = baselines.orEmpty()
        val roots = pcRoots.orEmpty();
        { entry ->
            if (!entry.inPcFold) {
                true
            } else {
                val source = PcSource.of(entry, roots)
                source != null && dev.droidtop.library.SyncBaselines.isRecentlyAdded(source.id, entry.firstSeenEpochMs, known)
            }
        }
    }
    LaunchedEffect(games, others, ownership, recentlyAdded) {
        val all = games ?: return@LaunchedEffect
        onHomeActivityChanged(all + others)
        val own = withContext(Dispatchers.Default) { withRetroHero(homeShelves(all, others, options = ownership, isRecentlyAdded = recentlyAdded)) }
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
    // The collections pinned as tabs, by id and name: Overview's shelves when the library has one source.
    val pinnedCollections = remember(savedViews.views) {
        savedViews.views.filter { it.pinned }.mapNotNull { view ->
            view.query.selected(LibraryFacet.COLLECTION).singleOrNull()?.takeIf { view.query.facets.size == 1 }?.let { it to view.name }
        }
    }
    LaunchedEffect(games, ownership, recentlyAdded, pcRoots, pinnedCollections, scope) {
        val all = games ?: return@LaunchedEffect
        val next = withContext(Dispatchers.Default) {
            pcShelves(
                all, options = ownership, isRecentlyAdded = recentlyAdded, updatesFirst = true, roots = pcRoots.orEmpty(),
                pinnedCollections = pinnedCollections, collectionsOf = scope.context.collectionsOf,
            )
        }
        if (state.view == PcView.OVERVIEW && !state.stripFocused) keepCursor(pcShelfList, next)
        pcShelfList = next
    }
    val shelves = if (state.home) homeShelfList else pcShelfList
    LaunchedEffect(games, scope, gridViews) {
        val all = games ?: return@LaunchedEffect
        counts = withContext(Dispatchers.Default) { pcViewCounts(all, scope, gridViews) }
    }
    var grid by remember { mutableStateOf(emptyList<LibraryEntry>()) }
    // The free-to-play rows not in the library that this view keeps out (or,
    // with the List option on, shows): the grid's last row says so, so they
    // never disappear in silence (docs/SPEC.md 7j).
    var freeRows by remember { mutableIntStateOf(0) }
    LaunchedEffect(games, state.query, scope) {
        val all = games ?: return@LaunchedEffect
        val query = state.query
        val (shown, free) = withContext(Dispatchers.Default) { query.applyTo(all, scope) to query.freeNotInLibrary(all, scope) }
        grid = shown
        freeRows = free
    }
    val hasFreeRow = state.view == PcView.GRID && freeRows > 0
    val onFreeRow = hasFreeRow && !state.stripFocused && state.itemIndex == grid.size
    fun toggleFree() {
        EsDeNavigationSounds.play(UiSound.CONFIRM)
        setOwnership(ownership.copy(showFree = !ownership.showFree))
    }

    // The library, as this tab shows it right now, and the game under the cursor.
    val currentShelf = shelves.getOrNull(state.shelfIndex)
    val currentList: List<LibraryEntry> = when {
        state.onShelves -> currentShelf?.entries.orEmpty()
        state.view == PcView.COLLECTIONS -> emptyList()
        else -> grid
    }
    // The Collections tab: its groups of tiles, worked out off the main thread
    // as the library, the membership or the saved views change.
    var collectionGroupList by remember { mutableStateOf(emptyList<CollectionGroup>()) }
    LaunchedEffect(games, membership, savedViews.views, scope) {
        val all = games ?: return@LaunchedEffect
        collectionGroupList = withContext(Dispatchers.Default) { collectionGroups(all, membership, copiesOf, savedViews.views, scope) }
    }
    val tiles = remember(collectionGroupList) { collectionGroupList.tiles() }
    val columns = collectionColumns()
    val tileRowList = remember(collectionGroupList, columns) { tileRows(collectionGroupList, columns) }
    val onTiles = state.view == PcView.COLLECTIONS
    val focusedTile = if (onTiles && !state.stripFocused) tiles.getOrNull(state.itemIndex) else null
    var tileMenu by remember { mutableStateOf<CollectionTile?>(null) }
    LaunchedEffect(shelves.size, currentList.size, hasFreeRow, tiles.size, onTiles) {
        state.shelfIndex = state.shelfIndex.coerceIn(0, (shelves.size - 1).coerceAtLeast(0))
        // The grid's free-to-play row is one more stop after the last game.
        val last = when {
            onTiles -> tiles.size - 1
            hasFreeRow -> currentList.size
            else -> currentList.size - 1
        }
        state.itemIndex = state.itemIndex.coerceIn(0, last.coerceAtLeast(0))
    }
    // The imported collections a store brought, and whether to make them tabs: asked once per store.
    var promptStore by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(membership) {
        val stores = membership.collections.mapNotNull { CollectionScope.importedFrom(it.id) }.distinct()
        if (stores.isEmpty()) return@LaunchedEffect
        promptStore = withContext(Dispatchers.IO) { stores.firstOrNull { !LibraryViewPrefs.importPrompted(context, it) } }
    }
    fun importedCollectionTiles(storeId: String? = null): List<CollectionTile> = tiles.filter { tile ->
        tile.collectionId?.let { id -> CollectionScope.importedFrom(id)?.let { storeId == null || it == storeId } } == true
    }
    fun pinCollections(pin: List<CollectionTile>) {
        val have = savedViews.views.map { it.id }.toSet()
        savedViews.replaceAll(
            savedViews.views + pin.filter { collectionViewId(it.collectionId!!) !in have }
                .map { NamedLibraryView(it.name, it.query, id = collectionViewId(it.collectionId!!), pinned = true) },
        )
    }
    fun unpinCollections(ids: Set<String>) {
        savedViews.replaceAll(savedViews.views.filterNot { view -> ids.any { view.id == collectionViewId(it) } })
    }
    fun togglePin(tile: CollectionTile) {
        val collectionId = tile.collectionId
        when {
            collectionId != null && tile.pinned -> savedViews.replaceAll(
                savedViews.views.filterNot { it.id == collectionViewId(collectionId) }
                    .map { if (it.pinned && it.query == tile.query) it.copy(pinned = false) else it },
            )
            collectionId != null -> pinCollections(listOf(tile))
            else -> savedViews.replaceAll(savedViews.views.map { if (it.id == tile.viewId) it.copy(pinned = !it.pinned) else it })
        }
    }
    fun answerPrompt(storeId: String) {
        promptStore = null
        coroutines.launch { withContext(Dispatchers.IO) { LibraryViewPrefs.setImportPrompted(context, storeId) } }
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

    // The strip: one chip per tab ([pcStripTabs]). Home has no strip; B from a
    // grid view or Collections returns to Overview.
    val stripCount = tabs.size
    fun activateChip(index: Int) {
        if (index !in 0 until stripCount) return
        state.stripIndex = index
        when (val tab = tabs[index]) {
            PcTab.Overview -> state.showOverview()
            PcTab.Collections -> state.showCollections()
            is PcTab.Grid -> if (state.view != PcView.GRID || state.query != tab.view.query) state.showGrid(tab.view.query)
        }
    }
    // The chip that is lit: the surface's own tab, the grid's view when one
    // stands for its query, none when only the pill describes it.
    val activeChip = when (state.view) {
        PcView.HOME -> -1
        PcView.OVERVIEW -> tabs.indexOf(PcTab.Overview)
        PcView.COLLECTIONS -> tabs.indexOf(PcTab.Collections)
        PcView.GRID -> tabs.indexOfFirst { it is PcTab.Grid && it.view.query == state.query }
    }
    // The filters the person set that no strip view stands for, as the one
    // pill at the strip's end: every filter, shortened past two, and a
    // count, cleared by one press.
    val filterPill = remember(grid, games, state.query, state.view, activeChip, scope) {
        if (state.view != PcView.GRID || activeChip >= 0) return@remember null
        state.query.pillText(scope, grid.size, state.query.totalIn(games.orEmpty(), scope))
    }
    // Update available's heading opens the Updates tab (the view everyone was given), else the same filter.
    fun openUpdates() {
        val updates = savedViews.views.firstOrNull { it.id == LibraryViewPrefs.UPDATES_VIEW_ID }?.query
            ?: LibraryQuery(facets = mapOf(LibraryFacet.UPDATE.key to setOf(dev.droidtop.shell.gamepad.query.UPDATE_YES)))
        state.stripFocused = false
        state.showGrid(updates)
        state.stripIndex = tabs.indexOfFirst { it is PcTab.Grid && it.view.query == updates }.coerceAtLeast(0)
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
        // A grid view or Collections goes back to Overview, Overview back to Home, the hub.
        if (state.view == PcView.GRID || state.view == PcView.COLLECTIONS) state.showOverview() else state.open(home = true)
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
    val hints = remember(verb, state.stripFocused, focusedEntry?.id, state.view, onDest, onFreeRow, focusedTile) {
        // Steam's order: the list's own actions, then A and B. Start (Menu)
        // is the shell's, drawn at the row's left; L1/R1 are the glyphs at
        // the strip's ends, not hints.
        listOf(
            HintBinding(GamepadAction.X, "Filter"),
            HintBinding(GamepadAction.Y, "Sort By"),
            HintBinding(GamepadAction.SELECT, "Options"),
            HintBinding(GamepadAction.A, if (onDest || focusedTile != null) "Open" else if (state.stripFocused || onFreeRow) "Select" else verb ?: "Play") {
                onDest || state.stripFocused || onFreeRow || focusedTile != null || focusedEntry != null
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
    val tilesState = rememberLazyGridState()
    LaunchedEffect(state.view, state.stripFocused, state.stripIndex, state.shelfIndex, state.itemIndex, shelves, grid, onDest, tiles) {
        when {
            state.stripFocused -> stripState.keepCentred(state.stripIndex, chained = heldStep)
            onDest -> columnState.keepInView(shelves.size, animate = !heldStep)
            onTiles -> if (tiles.isNotEmpty()) tilesState.keepCentred(gridIndexOfTile(collectionGroupList, state.itemIndex), chained = heldStep)
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
                            onTiles -> tileStep(tileRowList, state.itemIndex, GamepadAction.UP)?.let { moveTo(state.shelfIndex, it) }
                                ?: run { state.stripFocused = true }
                            // From the free-to-play row back to the last game.
                            onFreeRow -> if (grid.isNotEmpty()) moveTo(state.shelfIndex, grid.lastIndex) else state.stripFocused = true
                            else -> {
                                val target = gridPadTarget(state.itemIndex, grid.size, gridColumns(), FocusDirection.Up)
                                if (target == null) state.stripFocused = true else moveTo(state.shelfIndex, target)
                            }
                        }
                        GamepadAction.DOWN -> when {
                            onDest -> Unit
                            state.stripFocused -> if (currentList.isNotEmpty() || hasFreeRow || (onTiles && tiles.isNotEmpty())) state.stripFocused = false
                            state.onShelves -> if (state.shelfIndex < shelves.lastIndex) {
                                val next = state.shelfIndex + 1
                                moveTo(next, state.shelfItems[shelves[next].id] ?: 0)
                            } else if (state.home) {
                                EsDeNavigationSounds.play(UiSound.MOVE)
                                state.destFocused = true
                            }
                            onTiles -> tileStep(tileRowList, state.itemIndex, GamepadAction.DOWN)?.let { moveTo(state.shelfIndex, it) }
                            onFreeRow -> Unit
                            // Down from the last row reaches the free-to-play row; nothing wraps onto it.
                            else -> gridPadTarget(state.itemIndex, grid.size, gridColumns(), FocusDirection.Down)
                                ?.let { moveTo(state.shelfIndex, it) }
                                ?: run { if (hasFreeRow) moveTo(state.shelfIndex, grid.size) }
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
                                onTiles -> tileStep(tileRowList, state.itemIndex, press.action)?.let { moveTo(state.shelfIndex, it) }
                                onFreeRow -> Unit
                                else -> gridPadTarget(
                                    state.itemIndex, grid.size, gridColumns(),
                                    if (step < 0) FocusDirection.Left else FocusDirection.Right,
                                )?.let { moveTo(state.shelfIndex, it) }
                            }
                        }
                        GamepadAction.A -> {
                            if (onDest) HOME_DESTINATIONS.getOrNull(state.destIndex)?.let { onOpenSection(it.section) }
                            else if (state.stripFocused) activateChip(state.stripIndex)
                            else if (onFreeRow) toggleFree()
                            else if (focusedTile != null) state.showGrid(focusedTile.query)
                            else focusedEntry?.let(activate)
                        }
                        GamepadAction.X -> state.filterOpen = true
                        GamepadAction.Y -> state.sortOpen = true
                        // Options is the focused game's menu (L2 stays its
                        // alias); with no game under the cursor it is the
                        // list's own options.
                        // On a pinned tab, Select is that tab's Options (Edit, Unpin, Move).
                        GamepadAction.SELECT, GamepadAction.L2 -> {
                            val pinned = (tabs.getOrNull(state.stripIndex) as? PcTab.Grid)?.takeIf { state.stripFocused && !it.builtIn }
                            if (pinned != null) {
                                tabMenu = pinned.view
                            } else if (focusedTile != null) {
                                tileMenu = focusedTile
                            } else {
                                focusedEntry?.let(::openOptions) ?: run { state.optionsOpen = true }
                            }
                        }
                        else -> return@onPad false
                    }
                    true
                },
        ) {
            // The one strip above the shelves or the grid, with L1 and R1 at
            // its ends: it owns the shoulders while this tab is up
            // (OwnShoulders above), and the active filter is its last pill.
            if (!state.home) ViewStrip(
                labels = tabs.map { pcTabLabel(it, counts) },
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
                onTiles -> PcCollectionsView(
                    groups = collectionGroupList,
                    selected = state.itemIndex.takeIf { !state.stripFocused },
                    columns = columns,
                    state = tilesState,
                    onTap = { index ->
                        state.stripFocused = false
                        if (state.itemIndex == index) tiles.getOrNull(index)?.let { state.showGrid(it.query) } else moveTo(state.shelfIndex, index)
                    },
                    empty = COLLECTIONS_EMPTY,
                )
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
                    onOpenShelf = { shelf -> if (shelf.id == SHELF_UPDATES && !state.home) openUpdates() },
                )
                else -> {
                    if (grid.isEmpty() && !hasFreeRow) {
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
                            // The last item, across the whole width: what the
                            // List option keeps out, and the way to change it.
                            if (hasFreeRow) item(key = "free-to-play-row", span = { GridItemSpan(maxLineSpan) }) {
                                dev.droidtop.shell.gamepad.MenuRow(
                                    title = freeRowText(freeRows, ownership.showFree),
                                    selected = onFreeRow,
                                    onClick = {
                                        state.stripFocused = false
                                        if (onFreeRow) toggleFree() else moveTo(state.shelfIndex, grid.size)
                                    },
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
            savedViews = pcBuiltInViews + savedViews.views,
            onQueryChange = { query -> state.showGrid(query) },
            onSearch = {
                state.filterOpen = false
                state.searchOpen = true
            },
            onSaveView = { name ->
                val edited = editing
                if (edited != null) {
                    savedViews.put(edited.copy(query = state.query))
                    editing = null
                    state.filterOpen = false
                } else {
                    savedViews.put(NamedLibraryView(name, state.query, id = java.util.UUID.randomUUID().toString(), pinned = true))
                }
            },
            onSaveOnly = { name ->
                savedViews.put(NamedLibraryView(name, state.query, id = java.util.UUID.randomUUID().toString(), pinned = false))
            },
            editingName = editing?.name,
            onForgetView = { name -> savedViews.forget(name) },
            onDismiss = {
                state.filterOpen = false
                editing = null
            },
            // The list's own options (jump to a letter, scrape, PC setup and
            // the stores) are one row from here, and from Select when no
            // game is under the cursor.
            footerActions = listOf(
                SheetAction("List options", "Shared and free-to-play games, jump to a letter, scrape, game folders and stores") {
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
            // The ownership List options (docs/SPEC.md 7j) and the strip's
            // optional tabs (7i): global, not part of a view.
            listOptions = listOf(
                ("Favourites tab: " + if (stripTabs.favourites) "Shown" else "Hidden") to {
                    setStripTabs(stripTabs.copy(favourites = !stripTabs.favourites))
                },
                ("Collections tab: " + if (stripTabs.collections) "Shown" else "Hidden") to {
                    setStripTabs(stripTabs.copy(collections = !stripTabs.collections))
                },
                ("Show games shared with you: " + if (ownership.showShared) "On" else "Off") to {
                    setOwnership(ownership.copy(showShared = !ownership.showShared))
                },
                ("Show free-to-play games not in your library: " + if (ownership.showFree) "On" else "Off") to {
                    setOwnership(ownership.copy(showFree = !ownership.showFree))
                },
            ),
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

    tileMenu?.let { tile ->
        CollectionTileMenu(
            tile = tile,
            hasImported = importedCollectionTiles().isNotEmpty(),
            onTogglePin = {
                tileMenu = null
                togglePin(tile)
            },
            onPinAllImported = {
                tileMenu = null
                pinCollections(importedCollectionTiles())
            },
            onUnpinAllImported = {
                tileMenu = null
                unpinCollections(importedCollectionTiles().mapNotNull { it.collectionId }.toSet())
            },
            onDismiss = { tileMenu = null },
        )
    }
    promptStore?.takeIf { tileMenu == null && tabMenu == null && !state.filterOpen }?.let { storeId ->
        val count = membership.collections.count { CollectionScope.importedFrom(it.id) == storeId }
        ImportedCollectionsPrompt(
            storeLabel = PcSource.Store(storeId).label(),
            count = count,
            onChoose = {
                answerPrompt(storeId)
                state.open(home = false)
                state.showCollections()
                state.stripFocused = false
                state.stripIndex = tabs.indexOf(PcTab.Collections).coerceAtLeast(0)
            },
            onAddAll = {
                answerPrompt(storeId)
                pinCollections(importedCollectionTiles(storeId))
            },
            onNotNow = { answerPrompt(storeId) },
        )
    }
    // A pinned tab's Options (Select on it): Edit, Unpin, Move.
    tabMenu?.let { view ->
        PinnedTabMenu(
            view = view,
            onEdit = {
                tabMenu = null
                renaming = view
            },
            onUnpin = {
                tabMenu = null
                savedViews.replaceAll(savedViews.views.map { if (it.id == view.id) it.copy(pinned = false) else it })
                state.stripIndex = state.stripIndex.coerceAtMost((tabs.size - 2).coerceAtLeast(0))
            },
            onMove = { step, toFirst ->
                tabMenu = null
                val moved = movePinned(savedViews.views, view.id, step, toFirst)
                savedViews.replaceAll(moved)
                val tab = pcStripTabs(moved, stripTabs).indexOfFirst { it is PcTab.Grid && it.view.id == view.id }
                if (tab >= 0) state.stripIndex = tab
            },
            onDismiss = { tabMenu = null },
        )
    }
    // Edit: the name first, then the Filter sheet over the tab's own filters and sort.
    renaming?.let { view ->
        dev.droidtop.shell.gamepad.TextEditDialog(
            title = "Rename tab",
            subtitle = "Then change its filters and sort, and save",
            initial = view.name,
            onCommit = { name ->
                renaming = null
                editing = view.copy(name = name.trim().ifBlank { view.name })
                state.showGrid(view.query)
                state.filterOpen = true
            },
            onDismiss = { renaming = null },
        )
    }
}

/** What the Collections tab says while it holds nothing (docs/SPEC.md 7i). */
internal const val COLLECTIONS_EMPTY =
    "Add a game to a collection from its menu (Select). Collections a store keeps appear here after it syncs."

/**
 * A pinned tab's Options (docs/SPEC.md 7i): Edit (a new name, then the Filter
 * sheet over its filters, saved in place), Unpin (it stays a saved view in
 * Collections), Move left, Move right, Move to first.
 */
@Composable
private fun PinnedTabMenu(
    view: NamedLibraryView,
    onEdit: () -> Unit,
    onUnpin: () -> Unit,
    onMove: (step: Int, toFirst: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows: List<Pair<String, () -> Unit>> = listOf(
        "Edit" to onEdit,
        "Unpin" to onUnpin,
        "Move left" to { onMove(-1, false) },
        "Move right" to { onMove(1, false) },
        "Move to first" to { onMove(0, true) },
        "Close" to onDismiss,
    )
    var focusIndex by remember { mutableIntStateOf(0) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        dev.droidtop.shell.gamepad.MenuPanel(
            modifier = Modifier.width(LocalShellWindow.current.panelWidth(420.dp)),
            focusLabel = "Tab options",
            title = view.name,
            onPad = { press ->
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> focusIndex = dev.droidtop.shell.gamepad.menuMove(focusIndex, rows.size, press)
                    GamepadAction.A -> rows.getOrNull(focusIndex)?.second?.invoke()
                    GamepadAction.B, GamepadAction.SELECT -> onDismiss()
                    else -> Unit
                }
                true
            },
        ) {
            rows.forEachIndexed { index, (title, action) ->
                dev.droidtop.shell.gamepad.MenuRow(title = title, selected = index == focusIndex, onClick = action)
            }
        }
    }
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
    // A tap on a shelf's heading (Update available's opens the Updates tab).
    onOpenShelf: (PcShelf) -> Unit = {},
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
                    modifier = Modifier
                        .padding(
                            start = window.edgePadding,
                            end = window.edgePadding,
                            bottom = Space.Sm,
                        )
                        .clickable { onOpenShelf(shelf) },
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
