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
import dev.droidtop.library.GamesRoots
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryGrouping
import dev.droidtop.library.PartProgress
import dev.droidtop.library.StoreDownloads
import dev.droidtop.library.scraper.isPcOrEngineGame
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.shell.gamepad.CatalogNavigator
import dev.droidtop.shell.gamepad.GamelistOptionsMenu
import dev.droidtop.shell.gamepad.HelpRowClaim
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.OwnShoulders
import dev.droidtop.shell.gamepad.ShellChip
import dev.droidtop.shell.gamepad.ShoulderGlyph
import dev.droidtop.shell.gamepad.Space
import dev.droidtop.shell.gamepad.TypeRole
import dev.droidtop.shell.gamepad.gridPadTarget
import dev.droidtop.shell.gamepad.showsShoulderGlyphs
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.declaresHints
import dev.droidtop.shell.gamepad.input.menuStep
import dev.droidtop.shell.gamepad.input.onPad
import dev.droidtop.shell.gamepad.keepCentred
import dev.droidtop.shell.gamepad.keepInView
import dev.droidtop.shell.gamepad.query.LibraryFacet
import dev.droidtop.shell.gamepad.query.LibraryFilterSheet
import dev.droidtop.shell.gamepad.query.LibrarySortSheet
import dev.droidtop.shell.gamepad.query.PersistQuery
import dev.droidtop.shell.gamepad.query.QueryChipRow
import dev.droidtop.shell.gamepad.query.SheetAction
import dev.droidtop.shell.gamepad.query.rememberSavedViews
import dev.droidtop.shell.gamepad.query.LibraryQuery
import dev.droidtop.shell.gamepad.query.LibraryQueryScope
import dev.droidtop.shell.gamepad.query.LibrarySearchDialog
import dev.droidtop.shell.gamepad.query.LibrarySortKey
import dev.droidtop.shell.gamepad.requestFocusWhenAttached
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
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
    /** The shelves (true), or the grid over [query]. */
    var home by mutableStateOf(true)

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

    var pageId by mutableStateOf<String?>(null)
    var menuId by mutableStateOf<String?>(null)
    var setupOpen by mutableStateOf(false)
    var optionsOpen by mutableStateOf(false)
    var filterOpen by mutableStateOf(false)
    var sortOpen by mutableStateOf(false)
    var searchOpen by mutableStateOf(false)

    /**
     * The grid filtered to one store (a store page's "Open library",
     * docs/SPEC.md 7j "Places"): the Store facet selected, nothing else.
     * Marks the query loaded so the saved query is not read over it.
     */
    fun showStore(label: String) {
        query = LibraryQuery().withToggled(LibraryFacet.STORE, label, true)
        queryLoaded = true
        home = false
        stripFocused = false
        itemIndex = 0
        pageId = null
    }

    companion object {
        val Saver: Saver<PcGamesState, Any> = listSaver(
            save = { s -> listOf(s.home, s.stripFocused, s.stripIndex, s.shelfIndex, s.itemIndex, s.pageId.orEmpty()) },
            restore = { v ->
                PcGamesState().apply {
                    home = v.getOrNull(0) as? Boolean ?: true
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

/** The folded library and, per drawn game, every folder and store row behind it (docs/SPEC.md 7m). */
private class FoldedPcLibrary(
    val games: List<LibraryEntry>,
    val siblings: Map<String, List<LibraryEntry>>,
    /** A multi-part game's card id to the entry Play starts (the first part not finished, docs/SPEC.md 7n); only games where that differs from the card. */
    val continuing: Map<String, LibraryEntry>,
)

/**
 * The PC Games tab (docs/SPEC.md 7i, 2026-10-01): droidtop's own library
 * of every PC and engine game, after the Steam Deck's library and Steam
 * Big Picture rather than after an ES-DE theme. Three parts, top to
 * bottom:
 *
 * - **The view strip**: Home, the built-in views (All games, Installed,
 *   Continue playing), the person's saved views, and Filters and sort --
 *   the Deck's library tabs, with the filter one press away.
 * - **Home**: shelves, each a horizontal row of large capsules
 *   ([pcShelves]). **A view**: the same capsules as a grid over the one
 *   shared [LibraryQuery].
 * - **The hint row**, the tab's own: A names the focused game's primary
 *   action from [PcPlayState] (owner, 2026-10-01: "A is Primary Action.
 *   We can make it contextual using the pills"), Y its page, X favourite,
 *   L2 its menu, Select the list's options, B back to Home.
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
    library: Library,
    state: PcGamesState,
    onLaunch: (LibraryEntry) -> Unit,
    onToggleFavorite: (LibraryEntry) -> Unit,
    onFocusedEntryChanged: (LibraryEntry?) -> Unit,
    contextMenuRequest: Int,
    onHelpRowClaim: (HelpRowClaim) -> Unit,
    onCanGoBackChanged: (Boolean) -> Unit,
    onRequestRescan: () -> Unit,
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
        folded = withContext(Dispatchers.Default) {
            val groups = LibraryGrouping.group(entries, PartProgress.finished(context), GamesRoots.current(context).map { it.absolutePath })
            FoldedPcLibrary(
                games = groups.map { it.displayEntry },
                siblings = groups.associate { group -> group.displayEntry.id to group.entriesByPath.values.toList() },
                continuing = groups
                    .mapNotNull { group -> group.continueEntry?.takeIf { it.id != group.displayEntry.id }?.let { group.displayEntry.id to it } }
                    .toMap(),
            )
        }
    }
    val games = folded?.games
    // How many folders or store copies one drawn game stands for, from the
    // fold already made: a map read, never a lookup on disk.
    fun partsOf(entry: LibraryEntry): Int = folded?.siblings?.get(entry.id)?.size ?: 1

    val scope = remember {
        LibraryQueryScope(
            id = "pc",
            // Runner, ready and ProtonDB are left off: they cost a folder
            // walk or a network ask per entry, which a list never pays.
            facets = listOf(
                LibraryFacet.STORE, LibraryFacet.ENGINE, LibraryFacet.INSTALLED, LibraryFacet.FAVOURITES,
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

    var shelves by remember { mutableStateOf(emptyList<PcShelf>()) }
    LaunchedEffect(games) {
        val all = games ?: return@LaunchedEffect
        shelves = withContext(Dispatchers.Default) { pcShelves(all) }
        counts = withContext(Dispatchers.Default) { pcViewCounts(all, scope) }
    }
    var grid by remember { mutableStateOf(emptyList<LibraryEntry>()) }
    LaunchedEffect(games, state.query) {
        val all = games ?: return@LaunchedEffect
        val query = state.query
        grid = withContext(Dispatchers.Default) { query.applyTo(all, scope) }
    }

    // The library, as this tab shows it right now, and the game under the cursor.
    val currentShelf = shelves.getOrNull(state.shelfIndex)
    val currentList: List<LibraryEntry> = if (state.home) currentShelf?.entries.orEmpty() else grid
    LaunchedEffect(shelves.size, currentList.size) {
        state.shelfIndex = state.shelfIndex.coerceIn(0, (shelves.size - 1).coerceAtLeast(0))
        state.itemIndex = state.itemIndex.coerceIn(0, (currentList.size - 1).coerceAtLeast(0))
    }
    val focusedEntry = if (state.stripFocused) null else currentList.getOrNull(state.itemIndex)
    LaunchedEffect(focusedEntry?.id) { onFocusedEntryChanged(focusedEntry) }
    val focusedPlay = focusedEntry?.let { rememberPcPlayState(it) }

    // A store game's primary action is its store's own screen (install,
    // update, download), the same answer the capsule badge and the page
    // button read ([storeStageOf]); everything else launches, a multi-part
    // game's card launching the part to continue with, not always the first
    // (docs/SPEC.md 7n). An install or update stops on the free-space
    // offer first (Droidtop/tracker#227): the size and the room the chosen
    // volume has are named before the store's screen opens; a download
    // already running goes straight to the store's queue.
    val downloads by StoreDownloads.active.collectAsState()
    var storeOffer by remember { mutableStateOf<StoreInstallOffer?>(null) }
    fun openStore(entry: LibraryEntry) {
        openStoreScreen(context, entry)?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val launch: (LibraryEntry) -> Unit = { entry ->
        when (val stage = storeStageOf(entry, entry.downloadKey()?.let { downloads[it] })) {
            StoreStage.INSTALL, StoreStage.UPDATE -> storeOffer = StoreInstallOffer(entry, stage)
            null -> onLaunch(folded?.continuing?.get(entry.id) ?: entry)
            else -> openStore(entry)
        }
    }
    // The backdrop follows the game under the cursor (docs/SPEC.md 7i, "Home
    // art"); while the cursor is on the strip it keeps the last game's, and
    // the games either side of the cursor are preloaded so a step shows the
    // next one at once.
    var backdropArt by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedEntry?.id) { focusedEntry?.let { backdropArt = it.backdropArt() } }
    PreloadBackdrops(remember(currentList, state.itemIndex) { neighbourBackdrops(currentList, state.itemIndex) })

    // The strip: Home, every view, Filters and sort.
    val stripCount = views.size + 2
    val filtersChip = stripCount - 1
    fun activateChip(index: Int) {
        state.stripIndex = index
        when {
            index == 0 -> state.home = true
            index == filtersChip -> state.filterOpen = true
            else -> {
                val view = views[index - 1]
                if (!state.home && state.query == view.query) return
                state.query = view.query
                state.home = false
                state.itemIndex = 0
            }
        }
    }
    val currentView = views.firstOrNull { it.query == state.query }

    // Which level B leaves (docs/SPEC.md 6e: B always goes back one level).
    val storesScreen = remember { SettingsScreenRegistry.get(PC_STORES_SCREEN_ID) }
    val knownEmpty = games?.isEmpty() == true
    val showingSetup = (state.setupOpen || knownEmpty) && storesScreen != null
    val canGoBack = state.setupOpen || !state.home
    LaunchedEffect(canGoBack) {
        // The shell's one footer draws this tab's hints too: the focused
        // element declares them ([declaresHints] below), so the tab claims
        // no row of its own.
        onHelpRowClaim(HelpRowClaim.NONE)
        onCanGoBackChanged(canGoBack)
    }
    BackHandler(enabled = canGoBack && !showingSetup) {
        EsDeNavigationSounds.play("back")
        state.home = true
    }

    // The header's L2 pill: the focused game's own menu.
    var handledContextMenuRequest by remember { mutableIntStateOf(contextMenuRequest) }
    LaunchedEffect(contextMenuRequest) {
        if (contextMenuRequest != handledContextMenuRequest) {
            handledContextMenuRequest = contextMenuRequest
            focusedEntry?.let { state.menuId = it.id }
        }
    }

    if (showingSetup) {
        // "PC setup" (game folders, the Windows system files, Downloads):
        // the level above the library, and what an empty library opens
        // on instead of nothing (docs/SPEC.md 7i). CatalogNavigator owns
        // its own B; at its root that leaves the level.
        CatalogNavigator(root = storesScreen!!, onExit = { state.setupOpen = false })
        return
    }

    // L1/R1 step the strip's views: Home, then each view in order, not the
    // Filters chip (a dialog, not a view) and never wrapping (menuStep). The
    // strip owns the press even at its end, so the shoulders never move the
    // whole page to another tab from here (docs/SPEC.md 7j, "Gaming
    // controls"). The cursor stays where it is: stepping a view from the
    // grid does not pull the cursor up onto the strip.
    OwnShoulders { step ->
        val active = when {
            state.home -> 0
            currentView != null -> views.indexOf(currentView) + 1
            else -> state.stripIndex.coerceIn(0, filtersChip - 1)
        }
        val next = menuStep(active, filtersChip, step)
        if (next != active) {
            EsDeNavigationSounds.play("scroll")
            activateChip(next)
        }
    }

    // What this tab's focus promises, X and Y being the list's Filter and
    // Sort By (docs/SPEC.md 7j), Select the focused game's menu: only what
    // dispatches, re-read as the cursor moves.
    val verb = focusedPlay?.first?.verb
    val hints = remember(verb, state.stripFocused, focusedEntry?.id, state.home) {
        listOf(
            HintBinding(GamepadAction.A, if (state.stripFocused) "Select" else verb ?: "Play") { state.stripFocused || focusedEntry != null },
            HintBinding(GamepadAction.X, "Filter"),
            HintBinding(GamepadAction.Y, "Sort By"),
            HintBinding(GamepadAction.SELECT, "Options"),
            HintBinding(GamepadAction.B, "Back") { !state.home },
            // L1/R1 step this strip's views (OwnShoulders), so the row names
            // what they do HERE; Start is the shell's, drawn by the footer.
            HintBinding(GamepadAction.L, "Previous view"),
            HintBinding(GamepadAction.R, "Next view"),
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
    LaunchedEffect(state.home, state.stripFocused, state.stripIndex, state.shelfIndex, state.itemIndex, shelves, grid) {
        when {
            state.stripFocused -> stripState.keepCentred(state.stripIndex, chained = heldStep)
            state.home -> {
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
        if (shelf != state.shelfIndex || item != state.itemIndex) EsDeNavigationSounds.play("scroll")
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
                            // Never the tab bar (owner, 2026-09-27): the top
                            // of this tab is its strip, and Up there stays.
                            state.stripFocused -> Unit
                            state.home -> if (state.shelfIndex == 0) {
                                state.stripFocused = true
                            } else {
                                val next = state.shelfIndex - 1
                                moveTo(next, state.shelfItems[shelves[next].id] ?: 0)
                            }
                            else -> {
                                val target = gridPadTarget(state.itemIndex, grid.size, gridColumns(), FocusDirection.Up)
                                if (target == null) state.stripFocused = true else moveTo(state.shelfIndex, target)
                            }
                        }
                        GamepadAction.DOWN -> when {
                            state.stripFocused -> if (currentList.isNotEmpty()) state.stripFocused = false
                            state.home -> if (state.shelfIndex < shelves.lastIndex) {
                                val next = state.shelfIndex + 1
                                moveTo(next, state.shelfItems[shelves[next].id] ?: 0)
                            }
                            else -> gridPadTarget(state.itemIndex, grid.size, gridColumns(), FocusDirection.Down)
                                ?.let { moveTo(state.shelfIndex, it) }
                        }
                        GamepadAction.LEFT, GamepadAction.RIGHT -> {
                            val step = if (press.action == GamepadAction.LEFT) -1 else 1
                            when {
                                state.stripFocused -> {
                                    val next = menuStep(state.stripIndex, stripCount, step)
                                    if (next != state.stripIndex) EsDeNavigationSounds.play("scroll")
                                    state.stripIndex = next
                                }
                                state.home -> moveTo(state.shelfIndex, menuStep(state.itemIndex, currentList.size, step))
                                else -> gridPadTarget(
                                    state.itemIndex, grid.size, gridColumns(),
                                    if (step < 0) FocusDirection.Left else FocusDirection.Right,
                                )?.let { moveTo(state.shelfIndex, it) }
                            }
                        }
                        GamepadAction.A -> {
                            if (state.stripFocused) activateChip(state.stripIndex) else focusedEntry?.let(launch)
                        }
                        GamepadAction.X -> state.filterOpen = true
                        GamepadAction.Y -> state.sortOpen = true
                        // Options is the focused game's menu (L2 stays its
                        // alias); with no game under the cursor it is the
                        // list's own options.
                        GamepadAction.SELECT, GamepadAction.L2 ->
                            focusedEntry?.let { state.menuId = it.id } ?: run { state.optionsOpen = true }
                        else -> return@onPad false
                    }
                    true
                },
        ) {
            // The view strip, with L1 and R1 at its ends: it owns the
            // shoulders while this tab is up (OwnShoulders above).
            val shoulderGlyphs = window.showsShoulderGlyphs()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = window.edgePadding),
            ) {
                if (shoulderGlyphs) ShoulderGlyph("L1", badge = true, modifier = Modifier.padding(end = Space.Sm))
                LazyRow(
                    state = stripState,
                    contentPadding = PaddingValues(vertical = Space.Sm),
                    horizontalArrangement = Arrangement.spacedBy(Space.Sm),
                    modifier = Modifier.weight(1f),
                ) {
                    items(count = stripCount, key = { "chip:$it" }) { index ->
                        run {
                            val label = when (index) {
                                0 -> "Home"
                                filtersChip -> "Filters and sort"
                                else -> pcStripLabel(views[index - 1], counts)
                            }
                            val on = when (index) {
                                0 -> state.home
                                filtersChip -> !state.home && currentView == null
                                else -> !state.home && currentView === views[index - 1]
                            }
                            ShellChip(
                                label,
                                on = on,
                                selected = state.stripFocused && state.stripIndex == index,
                                onClick = {
                                    state.stripFocused = true
                                    activateChip(index)
                                },
                            )
                        }
                    }
                }
                if (shoulderGlyphs) ShoulderGlyph("R1", badge = true, modifier = Modifier.padding(start = Space.Sm))
            }
            when {
                games == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MenuTokens.OnSurface)
                }
                state.home -> PcShelvesHome(
                    shelves = shelves,
                    state = state,
                    columnState = columnState,
                    rowState = ::rowState,
                    onTapCapsule = { shelf, item, entry ->
                        state.stripFocused = false
                        if (state.shelfIndex == shelf && state.itemIndex == item) launch(entry) else moveTo(shelf, item)
                    },
                    onLongPressCapsule = { state.pageId = it.id },
                    downloads = downloads,
                    partsOf = ::partsOf,
                )
                else -> {
                    QueryChipRow(
                        scope = scope,
                        query = state.query,
                        shown = grid.size,
                        total = state.query.totalIn(games.orEmpty(), scope),
                        onChange = {
                            state.query = it
                            state.itemIndex = 0
                        },
                        modifier = Modifier.padding(horizontal = window.edgePadding, vertical = Space.Xs),
                    )
                    if (grid.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No games match this view", color = MenuTokens.OnSurfaceMuted, style = TypeRole.body)
                        }
                    } else {
                        val width = capsuleWidth()
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(minSize = width),
                            contentPadding = PaddingValues(start = window.edgePadding, end = window.edgePadding, top = Space.Sm, bottom = Space.Lg),
                            horizontalArrangement = Arrangement.spacedBy(Space.Md),
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
                                        if (state.itemIndex == index) launch(entry) else moveTo(state.shelfIndex, index)
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
            onQueryChange = { query ->
                state.query = query
                state.home = false
                state.itemIndex = 0
            },
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
            onQueryChange = { query ->
                state.query = query
                state.home = false
                state.itemIndex = 0
            },
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
            onTextChange = {
                state.query = state.query.copy(text = it)
                state.home = false
                state.itemIndex = 0
            },
            onDismiss = { state.searchOpen = false },
            suggestions = suggestions,
        )
    }
    if (state.optionsOpen) {
        // The list's own options (jump to letter, random, get games, the
        // PC scrape, PC setup): the same menu every gamelist's Select
        // opens, over the list as it is shown right now.
        val listed = if (state.home) games.orEmpty() else grid
        GamelistOptionsMenu(
            groupKey = "PC",
            groupLabel = "PC Games",
            systemId = PC_SYSTEM_ID,
            onScraped = onRequestRescan,
            onDismiss = { state.optionsOpen = false },
            games = listed,
            onJumpTo = { index ->
                if (state.home) {
                    // The letters were counted over the whole library in
                    // name order, which is the All games view.
                    state.query = LibraryQuery()
                    state.home = false
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
            onClose = { state.pageId = null },
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
            onClose = { state.menuId = null },
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
        )
    }
    // The free-space offer before a store install or update: its own
    // window over the tab, the one place the volume is chosen
    // (Droidtop/tracker#227).
    StoreInstallOfferSheet(
        offer = storeOffer,
        onProceed = { o ->
            storeOffer = null
            openStore(o.entry)
        },
        onDismiss = { storeOffer = null },
    )
}

/** The shelves, one horizontal row of capsules each, with the cursor's shelf heading drawn brighter. */
@Composable
private fun PcShelvesHome(
    shelves: List<PcShelf>,
    state: PcGamesState,
    columnState: LazyListState,
    rowState: (String) -> LazyListState,
    onTapCapsule: (shelf: Int, item: Int, entry: LibraryEntry) -> Unit,
    onLongPressCapsule: (LibraryEntry) -> Unit,
    downloads: Map<String, StoreDownloads.Progress>,
    partsOf: (LibraryEntry) -> Int,
) {
    val window = LocalShellWindow.current
    if (shelves.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.Lg)) {
                Text("Nothing on the shelves yet", color = MenuTokens.OnSurfaceMuted, style = TypeRole.body)
                dev.droidtop.shell.gamepad.GetGamesChip(dev.droidtop.library.integrations.GetGamesContext.EMPTY_STATE)
            }
        }
        return
    }
    val width = capsuleWidth()
    val heroCardWidth = heroWidth(width)
    LazyColumn(
        state = columnState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = Space.Sm, bottom = Space.Lg),
        verticalArrangement = Arrangement.spacedBy(Space.Lg),
    ) {
        itemsIndexed(shelves, key = { _, shelf -> shelf.id }) { shelfIndex, shelf ->
            val onThisShelf = !state.stripFocused && state.shelfIndex == shelfIndex
            Column {
                Text(
                    shelf.heading,
                    color = if (onThisShelf) MenuTokens.OnSurface else MenuTokens.OnSurfaceMuted,
                    style = TypeRole.rowTitle,
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
                        val hero = shelf.id == SHELF_CONTINUE && itemIndex == 0
                        PcCapsule(
                            entry = entry,
                            selected = onThisShelf && state.itemIndex == itemIndex,
                            width = if (hero) heroCardWidth else width,
                            onTap = { onTapCapsule(shelfIndex, itemIndex, entry) },
                            onLongPress = { onLongPressCapsule(entry) },
                            download = entry.downloadKey()?.let { downloads[it] },
                            parts = partsOf(entry),
                            hero = hero,
                        )
                    }
                }
            }
        }
    }
}
