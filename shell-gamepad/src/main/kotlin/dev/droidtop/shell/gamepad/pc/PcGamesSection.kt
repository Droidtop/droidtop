package dev.droidtop.shell.gamepad.pc

import dev.droidtop.shell.gamepad.padRow
import dev.droidtop.shell.gamepad.R
import androidx.compose.ui.res.stringResource
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
import dev.droidtop.library.stores.StoreInstallJob
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.scraper.isPcOrEngineGame
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.shell.gamepad.ActionRow
import dev.droidtop.shell.gamepad.ActionSheet
import dev.droidtop.shell.gamepad.AppOptionsMenu
import dev.droidtop.shell.gamepad.GamelistOptionsMenu
import dev.droidtop.shell.gamepad.ListOption
import dev.droidtop.shell.gamepad.HelpRowClaim
import dev.droidtop.shell.gamepad.GamingSection
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.ShellLoading
import dev.droidtop.shell.gamepad.rise
import dev.droidtop.shell.gamepad.OwnShoulders
import dev.droidtop.shell.gamepad.StatusClusterRoom
import dev.droidtop.shell.gamepad.ViewStrip
import dev.droidtop.shell.gamepad.Space
import dev.droidtop.shell.gamepad.TypeRole
import dev.droidtop.shell.gamepad.gridPadTarget
import dev.droidtop.shell.gamepad.focusRing
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
import androidx.compose.foundation.layout.width
import dev.droidtop.library.PcSource
import dev.droidtop.library.CollectionMembership
import dev.droidtop.library.CollectionScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.runtime.rememberCoroutineScope
import dev.droidtop.ui.shared.Motion
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
import dev.droidtop.shell.gamepad.query.matchesSearchText
import dev.droidtop.library.GameNaming
import dev.droidtop.library.integrations.LocalSearchRow
import dev.droidtop.library.integrations.SearchRowKind
import dev.droidtop.shell.gamepad.query.LibrarySortKey
import dev.droidtop.shell.gamepad.query.SearchEntry
import dev.droidtop.shell.gamepad.requestFocusWhenAttached
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import dev.droidtop.shell.gamepad.theme.UiSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.droidtop.shell.gamepad.query.addedEpochMs
import dev.droidtop.shell.gamepad.ShellDialog

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
     * The cursor is on the shelf's heading, not a capsule: Left from a
     * shelf's first capsule reaches a heading that opens something (Update
     * available's opens the Updates tab), so the pad reaches what a tap on
     * the heading does. Any other move leaves it.
     */
    var headingFocused by mutableStateOf(false)

    /**
     * The cursor is on Home's destination row (Retro Games, PC Games) under
     * the shelves, and which of its tiles. Home only; the row is the last
     * stop of Home's one list, so Down from the last shelf reaches it and
     * Up leaves it.
     */
    var destFocused by mutableStateOf(false)
    var destIndex by mutableIntStateOf(0)

    /**
     * The cursor is on Home's strip (What's new, Friends, Downloads; docs/SPEC.md 7i, "Home"), under the
     * games row: which tab is showing and which card. The tab is stepped with L1/R1 wherever the cursor is.
     */
    var homeStrip by mutableStateOf(false)
    var homeTab by mutableIntStateOf(0)
    var homeCard by mutableIntStateOf(0)

    /** The Needs attention page is open over Home. */
    var attentionOpen by mutableStateOf(false)

    var pageId by mutableStateOf<String?>(null)
    var menuId by mutableStateOf<String?>(null)
    /** The open menu starts on the Engine picker (the page's "Choose a runner"). */
    var menuEnginePicker by mutableStateOf(false)
    var optionsOpen by mutableStateOf(false)
    var filterOpen by mutableStateOf(false)
    var sortOpen by mutableStateOf(false)
    var searchOpen by mutableStateOf(false)

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
    fun showSource(id: String, holding: dev.droidtop.library.stores.StoreHolding? = null) {
        val bySource = LibraryQuery().withToggled(LibraryFacet.SOURCE, id, true)
        // A store page's holding row ("Shared with you · 573") adds its Ownership value; the pill says both.
        showQuery(if (holding == null) bySource else bySource.withToggled(LibraryFacet.OWNERSHIP, holding.name, true))
    }

    /** Every game that came through another launcher: Game sources' "Imported" row. */
    fun showImported() {
        showQuery(
            dev.droidtop.library.PcLaunchers.all.fold(LibraryQuery()) { query, launcher ->
                query.withToggled(LibraryFacet.IMPORTED_FROM, launcher.id, true)
            },
        )
    }

    /** The grid over [filtered], opened from outside the tab (Game sources); B returns to where the person came from. */
    private fun showQuery(filtered: LibraryQuery) {
        query = filtered
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
    /** Opens the Quick Menu: Home's band (its status facts and the bell). */
    onOpenQuickMenu: () -> Unit = {},
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
    // A copy chosen for a game ("Use this copy for this game") folds the library again.
    val copyChoices by dev.droidtop.library.CopyChoices.changes.collectAsState()
    LaunchedEffect(entries, progressToken, copyChoices) {
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
    // How much a capsule's badge says (List options > Capsule badge, docs/SPEC.md 7i).
    var badgeStyle by remember { mutableStateOf(dev.droidtop.library.CapsuleBadgeStyle.FULL) }
    // Whether a capsule shows its game's name under its art (List options > Show names under images, #578).
    var namesUnder by remember { mutableStateOf(true) }
    // How big the capsules are (List options > Card size).
    var cardSize by remember { mutableStateOf(CardSize.STANDARD) }
    // Which games came through a launcher (Lutris): one read of the game settings.
    var viaByEntry by remember { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(Unit) {
        val (roots, options) = withContext(Dispatchers.IO) {
            runCatching { dev.droidtop.library.GamesRoots.current(context).map { it.absolutePath } }.getOrDefault(emptyList()) to
                LibraryViewPrefs.ownershipOptions(context)
        }
        ownership = options
        stripTabs = withContext(Dispatchers.IO) { LibraryViewPrefs.stripTabs(context) }
        badgeStyle = withContext(Dispatchers.IO) { LibraryViewPrefs.capsuleBadge(context) }
        namesUnder = withContext(Dispatchers.IO) { LibraryViewPrefs.namesUnderImages(context) }
        cardSize = withContext(Dispatchers.IO) { LibraryViewPrefs.cardSize(context) }
        viaByEntry = withContext(Dispatchers.IO) { runCatching { dev.droidtop.library.PcLaunchers.viaByEntry(context) }.getOrDefault(emptyMap()) }
        pcRoots = roots
    }
    fun setStripTabs(next: StripTabs) {
        stripTabs = next
        coroutines.launch { withContext(Dispatchers.IO) { LibraryViewPrefs.setStripTabs(context, next) } }
    }
    fun setBadgeStyle(next: dev.droidtop.library.CapsuleBadgeStyle) {
        badgeStyle = next
        coroutines.launch { withContext(Dispatchers.IO) { LibraryViewPrefs.setCapsuleBadge(context, next) } }
    }
    fun setNamesUnder(next: Boolean) {
        namesUnder = next
        coroutines.launch { withContext(Dispatchers.IO) { LibraryViewPrefs.setNamesUnderImages(context, next) } }
    }
    fun setCardSize(next: CardSize) {
        cardSize = next
        coroutines.launch { withContext(Dispatchers.IO) { LibraryViewPrefs.setCardSize(context, next) } }
    }
    // How many sources the library has rows from: a PC capsule names its source only when there
    // is more than one (docs/SPEC.md 7i, "Badges"). One pass over the fold, off the main thread.
    var origins by remember { mutableIntStateOf(1) }
    LaunchedEffect(games, pcRoots) {
        val roots = pcRoots.orEmpty()
        origins = withContext(Dispatchers.Default) {
            games.orEmpty().mapNotNullTo(HashSet()) { PcSource.of(it, roots)?.id }.size.coerceAtLeast(1)
        }
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
    fun badgeOf(entry: LibraryEntry): KindBadge? = kindBadgeOf(entry, systemNames, origins, badgeStyle, pcRoots.orEmpty())
    // Home's strip and band (docs/SPEC.md 7i, "Home"; Droidtop/tracker#485 F7a), built off the main thread from what
    // the shell already holds. Only while Home is showing.
    val homeAll = remember(games, others) { games.orEmpty() + others }
    val homeStrip = if (state.home) rememberHomeStrip(homeAll, systemNames, keep = null) else HomeStripData()
    val homeTabs = homeStrip.tabs
    LaunchedEffect(homeTabs.size) { state.homeTab = state.homeTab.coerceIn(0, (homeTabs.size - 1).coerceAtLeast(0)) }
    val homeTab = homeTabs.getOrNull(state.homeTab)
    LaunchedEffect(homeTab?.cards?.size) { state.homeCard = state.homeCard.coerceIn(0, ((homeTab?.cards?.size ?: 1) - 1).coerceAtLeast(0)) }
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
    // Which Home rows started the most games, read once each time Home opens, so the order is worked out then and never
    // shifts while the person is on it (Droidtop/tracker#611).
    val homeUse by produceState(initialValue = emptyMap<String, Int>(), state.home) {
        value = if (state.home) withContext(Dispatchers.IO) { dev.droidtop.library.settings.HomeRows.use(context) } else emptyMap()
    }
    // What the person pinned and hid (Settings > Shell > Home rows, or a row heading's menu), read when Home opens and
    // followed after: a change is the person's own, so the row moves with the cursor on it.
    LaunchedEffect(state.home) { withContext(Dispatchers.IO) { dev.droidtop.library.settings.HomeRows.load(context) } }
    val homeRules by dev.droidtop.library.settings.HomeRows.rules.collectAsState()
    // The Home row whose heading menu is open (Droidtop/tracker#611): Pin to top, Move up and down, Hide.
    var rowMenu by remember { mutableStateOf<PcShelf?>(null) }
    LaunchedEffect(games, others, ownership, recentlyAdded, homeUse, homeRules) {
        val all = games ?: return@LaunchedEffect
        onHomeActivityChanged(all + others)
        val own = withContext(Dispatchers.Default) { homeShelves(all, others, options = ownership, isRecentlyAdded = recentlyAdded) }
        // The first paint shows Home's own rows before the slower ones below. After that the list is set once per
        // change: set twice (without Recommended, then with it) the shelves shifted under the cursor and the
        // scroll that follows it restarted on every update (Droidtop/tracker#616).
        if (homeShelfList.isEmpty()) homeShelfList = own + pluginShelfList
        val home = all + others
        // Droidtop's own recommendations, after Home's own rows (Droidtop/tracker#611): the recommender names games the
        // person has and has not played that look like what they do play. Home only; worked out off the main thread.
        val recommended = if (!state.home) {
            null
        } else {
            withContext(Dispatchers.Default) {
                val titles = dev.droidtop.library.integrations.LocalSimilarityRecommendations({ home })
                    .recommend(context, dev.droidtop.library.integrations.RecommendationScope.Overall, SHELF_LIMIT)
                    .map { it.title }
                recommendedShelf(titles, home)
            }
        }
        // The games this person plays at this time of day, from the launch log (Droidtop/tracker#611). Home only.
        val usualNow = if (!state.home) {
            null
        } else {
            val starts = library.recentStarts()
            withContext(Dispatchers.Default) {
                val zone = java.util.TimeZone.getDefault()
                val hourOf = { ms: Long -> java.util.Calendar.getInstance(zone).apply { timeInMillis = ms }.get(java.util.Calendar.HOUR_OF_DAY) }
                usualNowShelf(starts, home, hourOf(System.currentTimeMillis()), hourOf)
            }
        }
        val ownRows = listOfNotNull(usualNow) + own + listOfNotNull(recommended)
        val fresh = withContext(Dispatchers.IO) {
            pluginHomeShelves(dev.droidtop.library.integrations.PluginShelves.shelvesFor(context, home), home)
        }
        pluginShelfList = fresh
        val candidates = ownRows + fresh
        if (state.home) withContext(Dispatchers.IO) { dev.droidtop.library.settings.HomeRows.noteSeen(context, candidates.map { it.id to it.title }) }
        val next = rankHomeShelves(candidates, homeUse, homeRules)
        if (next != homeShelfList) {
            if (state.home && !state.stripFocused) keepCursor(homeShelfList, next)
            homeShelfList = next
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
    // A tab of an imported collection the store no longer has goes with it (Droidtop/tracker#756). Read from the
    // membership as it is stored, never from the empty value shown before the first read.
    LaunchedEffect(Unit) {
        CollectionMembership.flow(context).collect { stored ->
            val stale = staleImportedViewIds(savedViews.views.map { it.id }, stored.collections.mapTo(HashSet()) { it.id })
            if (stale.isNotEmpty()) savedViews.replaceAll(savedViews.views.filterNot { it.id in stale })
        }
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
    val onDest = state.home && (state.destFocused || (shelves.isEmpty() && !state.homeStrip))
    val onHomeStrip = state.home && state.homeStrip && !onDest
    val focusedEntry = if (state.stripFocused || onDest || onHomeStrip) null else currentList.getOrNull(state.itemIndex)
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
    val downloads by StoreInstallJob.live.collectAsState()
    val pcLaunch = rememberPcLaunch(
        onLaunch = { entry ->
            // A start from a Home row counts for that row's place on Home (Droidtop/tracker#611).
            if (state.home && !state.stripFocused) currentShelf?.id?.let { dev.droidtop.library.settings.HomeRows.noteStart(context, it) }
            onLaunch(folded?.continuing?.get(entry.id) ?: entry)
        },
        onOpenDownloads = { onOpenSection(GamingSection.DOWNLOADS) },
    )
    val launch: (LibraryEntry) -> Unit = pcLaunch.launch
    // A on a game opens its page, never a launch (owner, 2026-10-24, Droidtop/tracker#641: "hitting A on the page
    // shouldn't immediately launch the game, it should open the game's page"): a PC game's page with Play focused, a
    // Retro game's details; an app, which has no game page, opens. X is Play now ([playsNow]).
    val activate: (LibraryEntry) -> Unit = { entry ->
        when (cardPress(entry)) {
            CardPress.PAGE -> state.pageId = entry.id
            CardPress.DETAILS -> onShowDetail(entry)
            CardPress.LAUNCH -> launch(entry)
        }
    }
    // X plays the focused game as it is: only where Play works without a setup step first.
    val playsNow = focusedEntry != null && (focusedPlay == null || focusedPlay.second?.option?.state == RunnerState.READY)
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
    // Home's destination row: a library's menu, or Random game, which opens a game's page (a PC game) or its
    // options (a Retro game or a game app), so Play is one press from there. The pick is from every game Home
    // holds, PC, Retro and game apps, hidden ones left out (Droidtop/tracker#507).
    fun openDestination(index: Int) {
        state.destIndex = index
        val destination = HOME_DESTINATIONS.getOrNull(index) ?: return
        destination.section?.let { onOpenSection(it); return }
        val pick = HomeModel.randomGame(games.orEmpty() + retro + others)
        if (pick == null) {
            EsDeNavigationSounds.play(UiSound.BUMP)
        } else {
            EsDeNavigationSounds.play(UiSound.CONFIRM)
            openPage(pick)
        }
    }

    // A strip card's one verb (docs/SPEC.md 7i, "Home"): a game update opens the game's page, an addition the
    // place it went to, plugin updates Plugins, the attention card its page, a friend the chat, a download
    // the job.
    fun openCard(card: HomeCard) {
        EsDeNavigationSounds.play(UiSound.CONFIRM)
        when (card) {
            is HomeCard.GameUpdate -> openPage(card.entry)
            is HomeCard.Added -> when {
                card.single != null -> openPage(card.single)
                card.systemId != null -> onOpenSection(GamingSection.GAMES)
                card.place == "Apps" -> onOpenSection(GamingSection.APPS)
                else -> onOpenSection(GamingSection.PC_GAMES)
            }
            is HomeCard.PluginUpdates -> onOpenSection(GamingSection.PLUGINS)
            is HomeCard.More -> onOpenSection(GamingSection.UPDATES)
            is HomeCard.Attention -> state.attentionOpen = true
            is HomeCard.Friend -> onOpenSection(GamingSection.SOCIAL)
            is HomeCard.Download -> onOpenSection(GamingSection.DOWNLOADS)
        }
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
    // A shelf heading that opens something: Update available's, on Overview. The pad reaches it with Left.
    fun headingOpens(shelf: PcShelf): Boolean = shelf.id == SHELF_UPDATES && !state.home

    // Every Home row's heading has the row's menu, so the pad reaches each with Left too.
    fun headingReached(shelf: PcShelf): Boolean = headingOpens(shelf) || state.home

    // Update available's heading opens the Updates tab (the view everyone was given), else the same filter.
    fun openUpdates() {
        val updates = savedViews.views.firstOrNull { it.id == LibraryViewPrefs.UPDATES_VIEW_ID }?.query
            ?: LibraryQuery(facets = mapOf(LibraryFacet.UPDATE.key to setOf(dev.droidtop.shell.gamepad.query.UPDATE_YES)))
        state.stripFocused = false
        state.showGrid(updates)
        state.stripIndex = tabs.indexOfFirst { it is PcTab.Grid && it.view.query == updates }.coerceAtLeast(0)
    }

    fun openShelf(shelf: PcShelf) {
        if (headingOpens(shelf)) {
            state.headingFocused = false
            openUpdates()
        } else if (state.home) {
            rowMenu = shelf
        }
    }

    // Which level B leaves (docs/SPEC.md 6e: B always goes back one level).
    // Home is the root: B does nothing there (Overview goes back to it).
    val knownEmpty = games?.isEmpty() == true
    val canGoBack = state.view != PcView.HOME
    LaunchedEffect(canGoBack) {
        // The shell's one footer draws this tab's hints too: the focused
        // element declares them ([declaresHints] below), so the tab claims
        // no row of its own.
        onHelpRowClaim(HelpRowClaim.NONE)
        onCanGoBackChanged(canGoBack)
    }
    BackHandler(enabled = canGoBack) {
        EsDeNavigationSounds.play(UiSound.BACK)
        state.stripIndex = 0
        // A grid view or Collections goes back to Overview, Overview back to Home, the hub (a section of its own).
        if (state.view == PcView.GRID || state.view == PcView.COLLECTIONS) state.showOverview() else onOpenSection(GamingSection.HOME)
    }

    // Home also shows the Retro library and the launcher apps: it is empty only when they are too.
    if (knownEmpty && (!state.home || (retro.isEmpty() && apps.isEmpty()))) {
        // An empty library is its own place's empty state, with Game sources as its button (Droidtop/tracker#533); it
        // opens the Game sources place itself, the "PC setup" screen of links being gone (Droidtop/tracker#571 row 4).
        dev.droidtop.shell.gamepad.EmptyLibrary(
            line = if (state.home) "No games yet" else "No PC games yet",
            actionLabel = "Game sources",
            onAction = { onOpenSection(GamingSection.STORES) },
            pc = !state.home,
        )
        return
    }

    // L1/R1 step the strip's views in order, never wrapping (menuStep). Home
    // has no strip, so they do nothing there. The strip owns the press even at its end, so the shoulders never move
    // the whole page to another tab from here (docs/SPEC.md 7j, "Gaming
    // controls"). The cursor stays where it is: stepping a view from the
    // grid does not pull the cursor up onto the strip.
    OwnShoulders { step ->
        if (state.home) {
            val next = menuStep(state.homeTab, homeTabs.size, step)
            if (next != state.homeTab) {
                EsDeNavigationSounds.play(UiSound.TAB)
                state.homeTab = next
                state.homeCard = 0
            } else {
                EsDeNavigationSounds.play(UiSound.BUMP)
            }
            return@OwnShoulders
        }
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
    // Sort By (docs/SPEC.md 7j), L2 the focused thing's menu and Select the
    // list's options: only what dispatches, re-read as the cursor moves.
    val verb = focusedPlay?.first?.verb
    val hints = remember(verb, playsNow, state.stripFocused, focusedEntry?.id, state.view, onDest, onFreeRow, focusedTile, state.headingFocused, onHomeStrip, homeTab?.cards?.size) {
        // Steam's order: the list's own actions, then A and B. Start (Menu)
        // is the shell's, drawn at the row's left; L1/R1 are the glyphs at
        // the strip's ends, not hints.
        if (state.home) {
            // Home is not a list: no Filter or Sort By (docs/SPEC.md 7i, "Home"). Y is Search; L1/R1 step the strip.
            listOf(
                HintBinding(GamepadAction.L, "Switch tab"),
                HintBinding(GamepadAction.Y, "Search"),
                HintBinding(GamepadAction.L2, "Options") { focusedEntry != null },
                HintBinding(GamepadAction.X, "Play now") { playsNow && !onDest && !onHomeStrip },
                HintBinding(GamepadAction.A, if (onDest || onHomeStrip || focusedEntry != null) "Open" else verb ?: "Play") {
                    onDest || focusedEntry != null || (onHomeStrip && homeTab?.cards?.isNotEmpty() == true)
                },
            )
        } else listOf(
            HintBinding(GamepadAction.X, "Play now") { playsNow && !onDest && !state.stripFocused },
            HintBinding(GamepadAction.Y, "Search"),
            HintBinding(GamepadAction.L2, "Options") {
                focusedEntry != null || focusedTile != null ||
                    (state.stripFocused && (tabs.getOrNull(state.stripIndex) as? PcTab.Grid)?.builtIn == false)
            },
            HintBinding(GamepadAction.SELECT, "List options"),
            HintBinding(GamepadAction.A, if (onDest || focusedTile != null || (state.onShelves && state.headingFocused) || focusedEntry != null) "Open" else if (state.stripFocused || onFreeRow) "Select" else verb ?: "Play") {
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
    val homeCardsState = rememberLazyListState()
    LaunchedEffect(onHomeStrip, state.homeCard, state.homeTab) {
        if (onHomeStrip) {
            columnState.keepInView(HomeColumn.strip(shelves.size), animate = !heldStep)
            if (homeTab?.cards?.isNotEmpty() == true) homeCardsState.keepCentred(state.homeCard, chained = heldStep)
        }
    }
    LaunchedEffect(state.view, state.stripFocused, state.stripIndex, state.shelfIndex, state.itemIndex, shelves, grid, onDest, tiles) {
        when {
            onHomeStrip -> Unit
            state.stripFocused -> stripState.keepCentred(state.stripIndex, chained = heldStep)
            onDest -> columnState.keepInView(HomeColumn.destinations(state.home, shelves.size), animate = !heldStep)
            onTiles -> if (tiles.isNotEmpty()) tilesState.keepCentred(gridIndexOfTile(collectionGroupList, state.itemIndex), chained = heldStep)
            state.onShelves -> {
                val shelf = shelves.getOrNull(state.shelfIndex) ?: return@LaunchedEffect
                columnState.keepInView(HomeColumn.shelf(state.home, state.shelfIndex), animate = !heldStep)
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
        state.headingFocused = false
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
                    if (press.action == GamepadAction.UP || press.action == GamepadAction.DOWN) state.headingFocused = false
                    when (press.action) {
                        GamepadAction.UP -> when {
                            // Back from Home's destination row to the last shelf (or the strip, when no shelf follows it).
                            onDest -> if (shelves.size > 1) state.destFocused = false else {
                                state.destFocused = false
                                state.homeStrip = true
                            }
                            // From the strip up to the games row; Up from the games row does nothing (the band is touch only).
                            onHomeStrip -> if (shelves.isNotEmpty()) {
                                EsDeNavigationSounds.play(UiSound.MOVE)
                                state.homeStrip = false
                                state.shelfIndex = 0
                                state.itemIndex = state.shelfItems[shelves[0].id] ?: 0
                            }
                            // Home's shelf under the strip goes up to the strip.
                            state.home && state.onShelves && state.shelfIndex == 1 -> {
                                EsDeNavigationSounds.play(UiSound.MOVE)
                                currentShelf?.let { state.shelfItems[it.id] = state.itemIndex }
                                state.homeStrip = true
                            }
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
                            // From the strip to the shelves under it, else to Your library.
                            onHomeStrip -> {
                                EsDeNavigationSounds.play(UiSound.MOVE)
                                state.homeStrip = false
                                if (shelves.size > 1) {
                                    state.shelfIndex = 1
                                    state.itemIndex = state.shelfItems[shelves[1].id] ?: 0
                                } else {
                                    state.destFocused = true
                                }
                            }
                            // Down from Home's games row goes to the active tab's first card.
                            state.home && state.onShelves && state.shelfIndex == 0 -> {
                                EsDeNavigationSounds.play(UiSound.MOVE)
                                currentShelf?.let { state.shelfItems[it.id] = state.itemIndex }
                                state.homeStrip = true
                            }
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
                                onHomeStrip -> {
                                    val size = homeTab?.cards?.size ?: 0
                                    val next = menuStep(state.homeCard, size, step)
                                    if (next != state.homeCard) EsDeNavigationSounds.play(UiSound.MOVE) else EsDeNavigationSounds.play(UiSound.BUMP)
                                    state.homeCard = next
                                }
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
                                state.onShelves -> when {
                                    state.headingFocused -> if (step > 0) {
                                        EsDeNavigationSounds.play(UiSound.MOVE)
                                        state.headingFocused = false
                                    }
                                    step < 0 && state.itemIndex == 0 && currentShelf?.let(::headingReached) == true -> {
                                        EsDeNavigationSounds.play(UiSound.MOVE)
                                        state.headingFocused = true
                                    }
                                    else -> moveTo(state.shelfIndex, menuStep(state.itemIndex, currentList.size, step))
                                }
                                onTiles -> tileStep(tileRowList, state.itemIndex, press.action)?.let { moveTo(state.shelfIndex, it) }
                                onFreeRow -> Unit
                                else -> gridPadTarget(
                                    state.itemIndex, grid.size, gridColumns(),
                                    if (step < 0) FocusDirection.Left else FocusDirection.Right,
                                )?.let { moveTo(state.shelfIndex, it) }
                            }
                        }
                        GamepadAction.A -> {
                            if (onHomeStrip) homeTab?.cards?.getOrNull(state.homeCard)?.let(::openCard)
                            else if (state.onShelves && state.headingFocused) currentShelf?.let(::openShelf)
                            else if (onDest) openDestination(state.destIndex)
                            else if (state.stripFocused) activateChip(state.stripIndex)
                            else if (onFreeRow) toggleFree()
                            else if (focusedTile != null) state.showGrid(focusedTile.query)
                            else focusedEntry?.let(activate)
                        }
                        // X is Play now on a game (Droidtop/tracker#641); Filter is in the list options. Y is Search on Home.
                        GamepadAction.X -> if (playsNow && !onDest && !onHomeStrip && !state.stripFocused) focusedEntry?.let(launch) else return@onPad false
                        // Y is Search on Home and on every list (proposal 6.A, Droidtop/tracker#644 slice 4); Sort By
                        // is in the list options with Filter.
                        GamepadAction.Y -> state.searchOpen = true
                        // L2 is the focused thing's menu, as R2 is the global
                        // one; a long press opens the same menu. Select is the
                        // list's options, whatever is focused (owner, 2026-10-23,
                        // Droidtop/tracker#621). A pinned tab's menu is Edit,
                        // Unpin and Move.
                        GamepadAction.L2 -> {
                            val pinned = (tabs.getOrNull(state.stripIndex) as? PcTab.Grid)?.takeIf { state.stripFocused && !it.builtIn }
                            when {
                                pinned != null -> tabMenu = pinned.view
                                state.home && state.onShelves && state.headingFocused -> rowMenu = currentShelf
                                focusedTile != null -> tileMenu = focusedTile
                                focusedEntry != null -> openOptions(focusedEntry)
                                else -> return@onPad false
                            }
                        }
                        // Home is not a list, so it has no list options.
                        GamepadAction.SELECT -> if (state.home) return@onPad false else state.optionsOpen = true
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
                // A pinned tab's menu, the one L2 opens on it (Droidtop/tracker#621).
                onLongPress = { index ->
                    (tabs.getOrNull(index) as? PcTab.Grid)?.takeIf { !it.builtIn }?.let { pinned ->
                        state.stripFocused = true
                        state.stripIndex = index
                        tabMenu = pinned.view
                    }
                },
                onClearPill = {
                    state.query = state.query.cleared
                    state.itemIndex = 0
                },
                state = stripState,
            )
            when {
                games == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ShellLoading()
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
                    // The tile's menu, the one L2 and Select open (owner, 2026-10-23, Droidtop/tracker#621).
                    onLongPress = { index ->
                        state.stripFocused = false
                        moveTo(state.shelfIndex, index)
                        tiles.getOrNull(index)?.let { tileMenu = it }
                    },
                    empty = COLLECTIONS_EMPTY,
                )
                state.onShelves -> PcShelvesHome(
                    shelves = shelves,
                    state = state,
                    namesUnder = namesUnder,
                    cardSize = cardSize,
                    onDest = onDest,
                    home = if (state.home) {
                        HomeParts(
                            strip = homeStrip,
                            onStrip = onHomeStrip,
                            cardsState = homeCardsState,
                            onTab = { index ->
                                state.homeTab = index
                                state.homeCard = 0
                            },
                            onTapCard = { index ->
                                state.destFocused = false
                                if (onHomeStrip && state.homeCard == index) {
                                    homeTab?.cards?.getOrNull(index)?.let(::openCard)
                                } else {
                                    state.homeStrip = true
                                    state.homeCard = index
                                }
                            },
                            // Every way into Updates opens the Quick Menu's Downloads tab, where the jobs are (Droidtop/tracker#749).
                            onActivity = { onOpenSection(GamingSection.UPDATES) },
                            onBell = onOpenQuickMenu,
                            onFriends = { onOpenSection(GamingSection.SOCIAL) },
                            onQuickMenu = onOpenQuickMenu,
                        )
                    } else {
                        null
                    },
                    onOpenSection = ::openDestination,
                    columnState = columnState,
                    rowState = ::rowState,
                    onTapCapsule = { shelf, item, entry ->
                        state.stripFocused = false
                        state.destFocused = false
                        if (state.shelfIndex == shelf && state.itemIndex == item) activate(entry) else moveTo(shelf, item)
                    },
                    // A long press opens the thing's menu, the one L2 opens (owner, 2026-10-23, Droidtop/tracker#610, #621).
                    onLongPressCapsule = ::openOptions,
                    downloads = downloads,
                    partsOf = ::partsOf,
                    systemNames = systemNames,
                    badgeOf = ::badgeOf,
                    mixed = state.home,
                    onOpenShelf = ::openShelf,
                )
                else -> {
                    if (grid.isEmpty() && !hasFreeRow) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.no_games_match_view), color = MenuTokens.OnSurfaceMuted, style = TypeRole.body)
                        }
                    } else {
                        // Whole capsules at the tier width, 12dp apart, the
                        // row centred in what is left (Steam's library grid);
                        // a cell is never stretched past the capsule.
                        val width = capsuleWidth(cardSize)
                        // A badge part every card here shares says nothing (#496).
                        val gridBadges = remember(grid, systemNames, origins, badgeStyle) { ViewBadges(grid.map { badgeOf(it) }) }
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
                                    onLongPress = { openOptions(entry) },
                                    download = entry.downloadKey()?.let { downloads[it] },
                                    parts = partsOf(entry),
                                    badge = gridBadges.of(badgeOf(entry)),
                                    nameUnder = namesUnder,
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
        focusedEntry?.takeIf { !state.home }?.let { entry ->
            Text(
                focusLine(entry, focusedPlay?.first, partsOf(entry), pcRoots.orEmpty(), viaByEntry[entry.id]),
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
            // the stores) are one row from here, and Select.
            footerActions = listOf(
                SheetAction("List options", "Shared and free-to-play games, jump to a letter, scrape, game folders and stores") {
                    state.filterOpen = false
                    state.optionsOpen = true
                },
            ),
        )
    }
    if (state.attentionOpen) {
        val screen = remember(homeStrip.attention) { needsAttentionScreen(homeStrip.attention) }
        dev.droidtop.shell.gamepad.CatalogSheet(root = screen, onExit = { state.attentionOpen = false })
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
        // The console systems' names, read once while the dialog is open: a source's result that names one of them
        // (a PSP or Vita torrent) is not a PC game and is left out of this list.
        val consoleNames by produceState<Set<String>?>(null) {
            value = withContext(Dispatchers.IO) {
                dev.droidtop.library.integrations.SourceDestinations.consoleNamesOf(ConsoleSystemsRepository.allSystems(context))
            }
        }
        if (state.home) {
            // Home's search is the global one (docs/SPEC.md 12a, "One search: where it opens"): every library and the
            // launcher's game apps, the system names ("snes" finds the system), and every source, with no system scope.
            var text by remember { mutableStateOf("") }
            LibrarySearchDialog(
                query = LibraryQuery(text = text),
                matchCount = 0,
                totalCount = homeAll.size,
                onTextChange = { text = it },
                onDismiss = { state.searchOpen = false },
                suggestions = suggestions,
                localKey = homeAll to systemNames,
                local = { q ->
                    SearchEntry.matchingSystems(q, systemNames).map { (_, name) ->
                        LocalSearchRow("system:$name", SearchRowKind.GAME, name, "System") {
                            state.searchOpen = false
                            onOpenSection(GamingSection.GAMES)
                        }
                    } + homeAll.filter { matchesSearchText(it, q) }.take(SEARCH_GAME_ROWS).map { game ->
                        LocalSearchRow(game.id, SearchRowKind.GAME, GameNaming.displayName(game.title), badgeOf(game)?.text ?: "Game") {
                            state.searchOpen = false
                            openPage(game)
                        }
                    }
                },
            )
        } else LibrarySearchDialog(
            query = state.query,
            matchCount = grid.size,
            totalCount = all.size,
            onTextChange = { state.showGrid(state.query.copy(text = it)) },
            onDismiss = { state.searchOpen = false },
            suggestions = suggestions,
            // The PC library's own games are rows of the one list ("One search"), as the launcher's are; the grid
            // behind still narrows to the same text. Matched in memory over the list already loaded.
            localKey = all to consoleNames,
            local = { text ->
                all.filter { matchesSearchText(it, text) }.take(SEARCH_GAME_ROWS).map { game ->
                    LocalSearchRow(game.id, SearchRowKind.GAME, GameNaming.displayName(game.title), "Game") { openPage(game) }
                }
            },
            sourceFilter = { result ->
                consoleNames?.let { dev.droidtop.library.integrations.SourceDestinations.isPcResult(result, it) } ?: true
            },
            // PC games are what the sources are asked for, so a source narrows its own answer before the filter does.
            sourcePlatform = dev.droidtop.library.integrations.SourceDestinations.PC_PLATFORM,
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
                // X is Play now on a game (Droidtop/tracker#641), so the list's filter is here.
                ListOption("Filter") {
                    state.optionsOpen = false
                    state.filterOpen = true
                },
                // Y is Search (Droidtop/tracker#644 slice 4), so Sort By is here beside Filter.
                ListOption("Sort by") {
                    state.optionsOpen = false
                    state.sortOpen = true
                },
                ListOption("Favourites tab", on = stripTabs.favourites) {
                    setStripTabs(stripTabs.copy(favourites = !stripTabs.favourites))
                },
                ListOption("Collections tab", on = stripTabs.collections) {
                    setStripTabs(stripTabs.copy(collections = !stripTabs.collections))
                },
                ListOption("Show names under images", on = namesUnder) { setNamesUnder(!namesUnder) },
                // Smaller fits more games on a screen; larger shows more of each one's art.
                ListOption("Card size", value = cardSize.label) {
                    val sizes = CardSize.entries
                    setCardSize(sizes[(sizes.indexOf(cardSize) + 1) % sizes.size])
                },
                // Full ("PC · GOG", "Engine · Ren'Py"), Kind only ("PC"), Off: what a capsule's badge says.
                ListOption("Capsule badge", value = badgeStyle.label) {
                    val styles = dev.droidtop.library.CapsuleBadgeStyle.entries
                    setBadgeStyle(styles[(styles.indexOf(badgeStyle) + 1) % styles.size])
                },
                ListOption("Show games shared with you", on = ownership.showShared) {
                    setOwnership(ownership.copy(showShared = !ownership.showShared))
                },
                ListOption("Show free-to-play games not in your library", on = ownership.showFree) {
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
            onOpenStores = { onOpenSection(GamingSection.STORES) },
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
            // A copy's own row on Versions: its Install, Update or Play.
            onPlayCopy = { copy -> launch(copy) },
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
            onPrimary = {
                otherMenu = null
                onLaunch(entry)
            },
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

    rowMenu?.let { shelf ->
        HomeRowMenu(
            shelf = shelf,
            rules = homeRules,
            onChange = { change ->
                rowMenu = null
                coroutines.launch(Dispatchers.IO) {
                    dev.droidtop.library.settings.HomeRows.set(context, change(dev.droidtop.library.settings.HomeRows.rules.value))
                }
            },
            onDismiss = { rowMenu = null },
        )
    }
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
    // On PC Games only: its Choose in Collections opens PC Games' own Collections, which Home has not got.
    promptStore?.takeIf { !state.home && tileMenu == null && tabMenu == null && !state.filterOpen }?.let { storeId ->
        val count = membership.collections.count { CollectionScope.importedFrom(it.id) == storeId }
        ImportedCollectionsPrompt(
            storeLabel = PcSource.Store(storeId).label(),
            count = count,
            onChoose = {
                answerPrompt(storeId)
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
    "Add a game to a collection from its menu (L2). Collections a store keeps appear here after it syncs."

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
    ShellDialog(onDismissRequest = onDismiss) {
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
    // Each card's name under its art (List options > Show names under images, #578).
    namesUnder: Boolean,
    // How big the cards are (List options > Card size).
    cardSize: CardSize,
    onDest: Boolean,
    onOpenSection: (Int) -> Unit,
    columnState: LazyListState,
    rowState: (String) -> LazyListState,
    onTapCapsule: (shelf: Int, item: Int, entry: LibraryEntry) -> Unit,
    onLongPressCapsule: (LibraryEntry) -> Unit,
    downloads: Map<String, PluginJobsCenter.Entry>,
    partsOf: (LibraryEntry) -> Int,
    systemNames: Map<String, String>,
    // What each card's badge says (kindBadgeOf with the library's origins and the Capsule badge option).
    badgeOf: (LibraryEntry) -> KindBadge? = { kindBadgeOf(it, systemNames) },
    mixed: Boolean,
    // A tap on a shelf's heading (Update available's opens the Updates tab).
    onOpenShelf: (PcShelf) -> Unit = {},
    // Home's band and strip (docs/SPEC.md 7i, "Home"); null on PC Games' Overview.
    home: HomeParts? = null,
) {
    val window = LocalShellWindow.current
    // The page is the view: a badge part every card on its shelves shares says nothing (#496).
    val pageBadges = remember(shelves, badgeOf) { ViewBadges(shelves.flatMap { it.entries }.distinctBy { it.id }.map { badgeOf(it) }) }
    if (shelves.isEmpty() && home == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.Lg)) {
                Text(stringResource(R.string.shelves_empty), color = MenuTokens.OnSurfaceMuted, style = TypeRole.body)
                if (mixed) HomeDestinations(selected = state.destIndex.takeIf { onDest }, onOpen = onOpenSection)
                dev.droidtop.shell.gamepad.GetGamesChip()
            }
        }
        return
    }
    val width = capsuleWidth(cardSize)
    val heroCardWidth = heroWidth(width)
    // The shelves rise in one after another when the page first appears
    // (docs/SPEC.md "Gaming motion and focus"); a shelf scrolled back into
    // view later is simply there.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(Motion.ms(Motion.RiseMs + Motion.riseDelay(Motion.RiseMaxSteps)))
        entered = true
    }
    Column(Modifier.fillMaxSize()) {
    // Home's band: touch only, never a stop of the pad; the list scrolls under nothing.
    if (home != null) {
        HomeTopBand(
            data = home.strip,
            onActivity = home.onActivity,
            onBell = home.onBell,
            bellCount = home.strip.unreadMessages,
            onFriends = home.onFriends,
            onQuickMenu = home.onQuickMenu,
        )
    }
    LazyColumn(
        state = columnState,
        // What is left under the band, not the whole page: the full height ran the list's end off the screen, where
        // a row the cursor reached counted as shown and was never scrolled to (Droidtop/tracker#616).
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(
            top = Space.Sm,
            bottom = Space.Lg,
        ),
        // Steam's home spacing: 24dp between shelves, 12dp between capsules.
        verticalArrangement = Arrangement.spacedBy(Space.Xl),
    ) {
        // Home with nothing played or added yet: the strip and Your library still hold the cursor.
        if (home != null && shelves.isEmpty()) {
            item(key = "home-strip") { HomeStripItem(home, state) }
        }
        shelves.forEachIndexed { shelfIndex, shelf ->
            item(key = shelf.id) {
                val onThisShelf = !state.stripFocused && !onDest && !(home?.onStrip ?: false) && state.shelfIndex == shelfIndex
                Column(if (entered) Modifier else Modifier.rise(shelfIndex)) {
                    // The hero row of what was being played carries no heading,
                    // as the Deck's recent row has none: the hero card's own
                    // caption ("Played today · 2 h") says what the row is.
                    // Steam's shelf heading: the heading weight, quieter until
                    // the cursor is on the shelf.
                    // Home's games row is headed "Recent games" (the final design on tracker#485, B.2).
                    if (home != null || !(shelfIndex == 0 && shelf.id == SHELF_CONTINUE)) Text(
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
                            .focusRing(onThisShelf && state.headingFocused, dev.droidtop.shell.gamepad.Corners.Crisp)
                            .padRow { onOpenShelf(shelf) },
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
                                selected = onThisShelf && !state.headingFocused && state.itemIndex == itemIndex,
                                width = if (hero) heroCardWidth else width,
                                onTap = { onTapCapsule(shelfIndex, itemIndex, entry) },
                                onLongPress = { onLongPressCapsule(entry) },
                                download = entry.downloadKey()?.let { downloads[it] },
                                parts = partsOf(entry),
                                hero = hero,
                                // What each game is and where it is from, where that tells the cards apart (#496).
                                badge = pageBadges.of(badgeOf(entry)),
                                nameUnder = namesUnder,
                            )
                        }
                    }
                }
            }
            // Home's strip sits under the games row (docs/SPEC.md 7i, "Home"), as an item of its own, so following
            // the cursor along the games row never scrolls to the strip and back ([HomeColumn]).
            if (home != null && shelfIndex == 0) item(key = "home-strip") { HomeStripItem(home, state) }
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
}

/**
 * Where each stop of the shelves page is in its one column (Droidtop/tracker#616). Home's strip is an item of its
 * own right after the first shelf, or first on an empty Home; the destinations end the column. PC Games' Overview
 * has no strip. Pure, for the tests.
 */
internal object HomeColumn {
    fun shelf(home: Boolean, shelf: Int): Int = if (home && shelf > 0) shelf + 1 else shelf

    fun strip(shelfCount: Int): Int = if (shelfCount == 0) 0 else 1

    fun destinations(home: Boolean, shelfCount: Int): Int = if (home) shelfCount + 1 else shelfCount
}

/** What Home adds to the shelves page: the band's data and taps, and the strip's cursor and taps. */
internal class HomeParts(
    val strip: HomeStripData,
    val onStrip: Boolean,
    val cardsState: LazyListState,
    val onTab: (Int) -> Unit,
    val onTapCard: (Int) -> Unit,
    val onActivity: () -> Unit,
    val onBell: () -> Unit,
    val onFriends: () -> Unit,
    val onQuickMenu: () -> Unit,
)

@Composable
private fun HomeStripItem(home: HomeParts, state: PcGamesState) {
    HomeStripRow(
        tabs = home.strip.tabs,
        active = state.homeTab.coerceIn(0, (home.strip.tabs.size - 1).coerceAtLeast(0)),
        selected = state.homeCard.takeIf { home.onStrip },
        state = home.cardsState,
        onTab = home.onTab,
        onTapCard = home.onTapCard,
    )
}

/**
 * The Needs attention page (docs/SPEC.md 7i, "Home"): one row per item, each with its one verb as the row's
 * value. A BIOS item opens that system's settings (its BIOS files are there); missing artwork starts the
 * scrape job, followed under Downloads.
 */
internal fun needsAttentionScreen(items: List<AttentionItem>): dev.droidtop.library.settings.CatalogScreen =
    dev.droidtop.library.settings.CatalogScreen(
        id = "home_needs_attention",
        title = "Needs attention",
        groups = { _ ->
            listOf(
                dev.droidtop.library.settings.CatalogGroup(
                    id = "needs_attention",
                    title = null,
                    items = items.map { item ->
                        when (val target = item.target) {
                            is AttentionTarget.Bios -> {
                                val screen = dev.droidtop.library.settings.SettingsScreenRegistry.get("console_systems", target.systemId)
                                if (screen != null) {
                                    dev.droidtop.library.settings.NestedScreenItem(
                                        id = item.id,
                                        title = item.title,
                                        subtitle = item.body,
                                        inline = screen,
                                        valueLabel = { item.verb },
                                    )
                                } else {
                                    dev.droidtop.library.settings.ActionItem(id = item.id, title = item.title, subtitle = item.body)
                                }
                            }
                            AttentionTarget.Artwork -> dev.droidtop.library.settings.ActionItem(
                                id = item.id,
                                title = item.title,
                                subtitle = item.body,
                                value = item.verb,
                                run = { context -> dev.droidtop.library.scraper.LibraryScrapeJob.start(context, item.verb, null) {} },
                            )
                        }
                    },
                ),
            )
        },
    )

/** A tile on Home's destination row: where it goes ([section]; null for Random game) and what it is called. */
internal class HomeDestination(val section: GamingSection?, val label: String)

/**
 * Home's destination row: the Retro and PC game menus, in the left menu's order, then Random game
 * (Droidtop/tracker#507), which opens one game picked at random from everything Home holds.
 */
internal val HOME_DESTINATIONS = listOf(
    HomeDestination(GamingSection.GAMES, "Retro Games"),
    HomeDestination(GamingSection.PC_GAMES, "PC Games"),
    HomeDestination(null, "Random game"),
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

/** The most PC games the search lists as rows; the grid behind it narrows to all of them. */
private const val SEARCH_GAME_ROWS = 20

/** What A does on a game card in Gaming (owner, 2026-10-24, Droidtop/tracker#641): open it, never launch it. */
internal enum class CardPress { PAGE, DETAILS, LAUNCH }

/** A PC game opens its page, a Retro game its details, and an app, which has no game page, opens. Pure. */
internal fun cardPress(entry: LibraryEntry): CardPress = when {
    entry.inPcFold -> CardPress.PAGE
    entry.appFacts != null -> CardPress.LAUNCH
    else -> CardPress.DETAILS
}

/**
 * A Home row heading's menu (Droidtop/tracker#611, "A row heading's menu has Pin to top, Move up / Move down, and
 * Hide"): the same choices Settings > Shell > Home rows has for the row, on the row itself. Move is among the pinned
 * rows, the only ones with a place of their own; the others go where use puts them.
 */
@Composable
private fun HomeRowMenu(
    shelf: PcShelf,
    rules: dev.droidtop.library.settings.HomeRows.Rules,
    onChange: ((dev.droidtop.library.settings.HomeRows.Rules) -> dev.droidtop.library.settings.HomeRows.Rules) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = dev.droidtop.library.settings.HomeRows
    val slot = rules.pinned.indexOf(shelf.id)
    ActionSheet(
        title = shelf.title,
        rows = buildList {
            if (slot < 0) {
                add(ActionRow("Pin to top", "Home leads with it, whatever you play") { onChange { rows.with(it, shelf.id, rows.PINNED) } })
            } else {
                add(ActionRow("Unpin", "It goes where your use puts it") { onChange { rows.with(it, shelf.id, rows.SHOWN) } })
                if (slot > 0) add(ActionRow("Move up") { onChange { rows.move(it, shelf.id, -1) } })
                if (slot < rules.pinned.lastIndex) add(ActionRow("Move down") { onChange { rows.move(it, shelf.id, 1) } })
            }
            add(ActionRow("Hide", "Settings > Shell > Home rows shows it again") { onChange { rows.with(it, shelf.id, rows.HIDDEN) } })
            add(ActionRow("Close", onSelect = onDismiss))
        },
        onDismiss = onDismiss,
    )
}
