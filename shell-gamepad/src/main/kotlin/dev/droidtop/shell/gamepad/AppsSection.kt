package dev.droidtop.shell.gamepad

import androidx.compose.ui.res.stringResource
import dev.droidtop.runtime.systemstatus.SettingsLaunch
import android.util.Log
import dev.droidtop.library.userFacingErrorMessage
import dev.droidtop.shell.gamepad.input.PadCadence
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.droidtop.runtime.tasks.text
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.droidtop.library.settings.CatalogPrefs
import dev.droidtop.library.settings.GamingSettingsCatalog
import dev.droidtop.library.EngineGameProvider
import dev.droidtop.library.Library
import dev.droidtop.runtime.tasks.TaskManager
import dev.droidtop.library.AppCategoryRules
import dev.droidtop.library.AppUsageAccess
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.PcSource
import dev.droidtop.shell.gamepad.query.APPS_SCOPE_ID
import dev.droidtop.shell.gamepad.query.LibraryFacet
import dev.droidtop.shell.gamepad.query.LibraryFilterSheet
import dev.droidtop.shell.gamepad.query.LibraryQuery
import dev.droidtop.shell.gamepad.query.retroQueryScope
import dev.droidtop.shell.gamepad.query.LibrarySortSheet
import dev.droidtop.shell.gamepad.query.PersistQuery
import dev.droidtop.shell.gamepad.query.LibrarySearchDialog
import dev.droidtop.shell.gamepad.query.appsActiveView
import dev.droidtop.shell.gamepad.query.appsEmptyMessage
import dev.droidtop.shell.gamepad.query.appsLandingQuery
import dev.droidtop.shell.gamepad.query.appsStripLabel
import dev.droidtop.shell.gamepad.query.appsStripViews
import dev.droidtop.shell.gamepad.query.appsViewCounts
import dev.droidtop.shell.gamepad.query.appsViewQuery
import dev.droidtop.shell.gamepad.query.pillText
import dev.droidtop.shell.gamepad.query.SheetAction
import dev.droidtop.shell.gamepad.query.appsQueryScope
import dev.droidtop.shell.gamepad.query.rememberSavedViews
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.needsInstall
import dev.droidtop.library.scraper.isPcOrEngineGame
import dev.droidtop.shell.gamepad.pc.CapsuleStatusBadge
import dev.droidtop.shell.gamepad.pc.onPcGamesTab
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.consoles.PlatformsDatabase
import dev.droidtop.library.displayName
import dev.droidtop.library.itemName
import dev.droidtop.library.kindLine
import dev.droidtop.library.integrations.Integration
import dev.droidtop.library.integrations.IntegrationCapability
import dev.droidtop.library.integrations.IntegrationStore
import dev.droidtop.library.integrations.OpenWithTarget
import dev.droidtop.library.integrations.openWithChipLabel
import dev.droidtop.library.integrations.openWithTargetsFor
import dev.droidtop.library.theme.SystemThemeColors
import dev.droidtop.library.theme.EsDeCollectionKind
import dev.droidtop.library.theme.ThemeAssets
import dev.droidtop.library.scanFollowingGamesRoots
import dev.droidtop.library.theme.EsDeTransitionAnimation
import dev.droidtop.library.theme.boolOrNull
import dev.droidtop.library.theme.primaryListElement
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.ControllerLayouts
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.FocusedHintRow
import dev.droidtop.shell.gamepad.input.FocusedHints
import dev.droidtop.shell.gamepad.input.LocalFocusedHints
import dev.droidtop.shell.gamepad.input.LocalShellMenus
import dev.droidtop.shell.gamepad.input.ShellMenuHints
import dev.droidtop.shell.gamepad.input.ShellMenus
import dev.droidtop.shell.gamepad.input.declaresHints
import dev.droidtop.shell.gamepad.input.menuStep
import dev.droidtop.shell.gamepad.input.onPad
import dev.droidtop.shell.gamepad.input.ownPadButtons
import dev.droidtop.shell.gamepad.input.rememberHintList
import dev.droidtop.shell.gamepad.theme.EsDeListItem
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import dev.droidtop.shell.gamepad.theme.UiSound
import dev.droidtop.shell.gamepad.theme.EsDeSystemListView
import dev.droidtop.shell.gamepad.theme.EsDeThemedView
import dev.droidtop.shell.gamepad.theme.EsDeSystemElementLayer
import dev.droidtop.shell.gamepad.theme.EsDeSystemSlot
import dev.droidtop.shell.gamepad.theme.EsDeViewLayer
import dev.droidtop.library.theme.EsDeSystemSlide
import dev.droidtop.library.theme.strOrNull
import androidx.compose.animation.core.Animatable
import dev.droidtop.library.theme.EsDeViewTransition
import dev.droidtop.shell.gamepad.theme.ES_DE_SYSTEM_FADE_MS
import dev.droidtop.shell.gamepad.theme.esDeSystemFadeSpec
import dev.droidtop.shell.gamepad.theme.esDeTransitionContext
import dev.droidtop.shell.gamepad.theme.esDeTransitionKind
import dev.droidtop.shell.gamepad.theme.esDeViewTransition
import dev.droidtop.shell.gamepad.theme.ThemePrefs
import dev.droidtop.ui.shared.Motion
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What an app tile promises while it has the focus: A opens, Y searches and
 * Select opens the list's Filter sheet (with Sort by in it), as every list
 * does (proposal 6.A, Droidtop/tracker#644 slice 4), and L2 is the app's own
 * options. The list around the tile answers Y and Select. The one footer
 * draws these ([declaresHints]).
 */
internal val APP_TILE_HINTS = listOf(
    HintBinding(GamepadAction.A, "Open"),
    HintBinding(GamepadAction.Y, "Search"),
    HintBinding(GamepadAction.SELECT, "Filter"),
    HintBinding(GamepadAction.L2, "Options"),
)

/**
 * The Apps tab (docs/SPEC.md 7j, "Filters, sort and the hint bar"): every
 * launchable thing that is not a game library, as one list over the same
 * query model the game libraries use. All apps, A to Z until the person
 * says otherwise; X opens the Filter sheet (Category, Running, Recently
 * used, Recently installed, Favourites, Hidden, Source, each with counts),
 * Y Sort By, Select the focused app's options, and a long press marks an
 * app as a game. One strip sits above the list ([ViewStrip]): All, Games,
 * Emulators, Tools and Recently used with their counts, stepped by L1/R1,
 * and the filters no view stands for as its one pill. The view's filters
 * and sort are remembered, and nothing here reads a disk while drawing:
 * the category rules, the usage log, the counts and the filtered list are
 * worked out off the main thread.
 */
@Composable
internal fun AppsSection(
    entries: List<LibraryEntry>,
    onLaunch: (LibraryEntry) -> Unit,
    onShowDetail: (LibraryEntry) -> Unit,
    onFocusedEntryChanged: (LibraryEntry?) -> Unit,
    onToggleFavorite: (LibraryEntry) -> Unit = {},
    // An app changed here (hidden or shown), for the list the shell holds, as the app's page reports its own changes.
    onEntryChanged: (LibraryEntry) -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Which apps are running, from the task manager's one list (read here
    // once, and again whenever the Filter sheet opens, so its Running count
    // is current); nothing polls while this tab is not showing.
    val running by TaskManager.snapshot.collectAsState()
    val runningPackages = remember(running) { running?.apps?.mapTo(HashSet()) { it.packageName }.orEmpty() }

    var query by remember { mutableStateOf(LibraryQuery()) }
    var queryLoaded by remember { mutableStateOf(false) }
    PersistQuery(APPS_SCOPE_ID, query, queryLoaded) {
        query = it
        queryLoaded = true
    }
    val savedViews = rememberSavedViews(APPS_SCOPE_ID)

    // What the rules and the usage log say, loaded off the main thread and
    // reloaded when the person changes a mark or comes back from granting
    // Usage access.
    var marksTick by remember { mutableIntStateOf(0) }
    var usageTick by remember { mutableIntStateOf(0) }
    val rules by produceState(AppCategoryRules(), marksTick) {
        value = withContext(Dispatchers.IO) { AppCategoryRules.load(context) }
    }
    val usage by produceState(emptyMap<String, Long>(), usageTick) {
        value = withContext(Dispatchers.IO) { AppUsageAccess.lastUsed(context) }
    }
    val usageGranted by produceState(false, usageTick) {
        value = withContext(Dispatchers.IO) { AppUsageAccess.granted(context) }
    }
    val scope = remember(rules, usage, runningPackages) { appsQueryScope(rules, usage, runningPackages) }

    val shown by produceState<List<LibraryEntry>?>(null, entries, query, scope) {
        value = withContext(Dispatchers.Default) { query.applyTo(entries, scope) }
    }
    val total = remember(entries, query, scope) { query.totalIn(entries, scope) }
    // The strip's views and their counts (docs/SPEC.md 7j).
    val views = remember(savedViews.views) { appsStripViews(savedViews.views) }
    val counts by produceState(emptyMap<String, Int>(), entries, scope, views) {
        value = withContext(Dispatchers.Default) { appsViewCounts(entries, scope, views) }
    }
    // Opening on a view that holds nothing lands on All instead, once the counts are in.
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(counts, queryLoaded) {
        if (landed || !queryLoaded || counts.isEmpty()) return@LaunchedEffect
        landed = true
        appsLandingQuery(views, query, counts)?.let { query = it }
    }

    var filterOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    LaunchedEffect(filterOpen) { TaskManager.refresh(context) }
    var optionsFor by remember { mutableStateOf<LibraryEntry?>(null) }
    // Where the tile whose menu is open is on the screen, so the menu opens beside it (Droidtop/tracker#827).
    var optionsAnchor by remember { mutableStateOf<AnchorRect?>(null) }

    // Hidden or shown from the options menu: the shell's list follows at once ([onEntryChanged]), and the launcher's
    // own hidden list, written off the main thread, is what the next scan reads.
    val toggleHidden: (LibraryEntry) -> Unit = { entry ->
        val nowHidden = !entry.hidden
        onEntryChanged(entry.copy(hidden = nowHidden))
        coroutineScope.launch { setAppHidden(context, entry, nowHidden) }
    }

    // "Mark as game" from a long press and from the app's options: the
    // one answer is stored, then the category rules reload.
    val markGame: (LibraryEntry) -> Unit = { entry ->
        if (entry.appFacts != null) {
            val nowGame = !rules.isGame(entry.id, entry.appFacts)
            coroutineScope.launch {
                setAppIsGame(context, entry, nowGame)
                marksTick++
            }
        }
    }

    val list = shown
    if (list == null || !queryLoaded) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ShellLoading()
        }
        return
    }

    val sections = remember(list) { buildAppSections(list) }
    val firstFocus = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }
    // The first tile takes the focus when the list first arrives, and the
    // empty state when a filter leaves nothing, so X and Y always have a
    // focused element to answer them.
    // On open, and again when L1/R1 changes the view: the items under the focus are replaced, so the focus goes to the
    // first one (Droidtop/tracker#820).
    LaunchedEffect(list.isEmpty(), query) {
        if (list.isEmpty()) requestFocusWhenAttached(emptyFocus, "Apps") else requestFocusWhenAttached(firstFocus, "Sections")
    }

    // L1/R1 step the strip's views, never wrapping; the strip owns the
    // press even at its end (docs/SPEC.md 7j, "Gaming controls"). With a
    // pill showing (no view lit) the first press lands on All.
    val activeView = appsActiveView(views, query)
    OwnShoulders { step ->
        val next = if (activeView < 0) 0 else menuStep(activeView, views.size, step)
        if (next != activeView) {
            EsDeNavigationSounds.play(UiSound.MOVE)
            query = appsViewQuery(views[next], query)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onPad { press ->
                when (press.action) {
                    GamepadAction.SELECT -> {
                        filterOpen = true
                        true
                    }
                    GamepadAction.Y -> {
                        searchOpen = true
                        true
                    }
                    else -> false
                }
            },
    ) {
        ViewStrip(
            labels = views.map { appsStripLabel(it, counts) },
            active = activeView,
            focused = null,
            pill = if (activeView >= 0) null else query.pillText(scope, list.size, total),
            onSelect = { query = appsViewQuery(views[it], query) },
            onClearPill = { query = query.cleared },
            modifier = Modifier.padding(top = MenuTokens.SectionListTopGap),
        )
        if (list.isEmpty()) {
            // A lit view is not a filter: only what no view stands for is cleared, and an empty view
            // offers All (Droidtop/tracker#500).
            val emptyHints = remember(activeView) {
                listOf(
                    HintBinding(GamepadAction.A, if (activeView > 0) "Show all apps" else "Clear filters") { activeView > 0 || !query.isEmpty },
                    HintBinding(GamepadAction.Y, "Search"),
                    HintBinding(GamepadAction.SELECT, "Filter"),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .focusRequester(emptyFocus)
                    .declaresHints(emptyHints)
                    .onPad { press ->
                        if (press.action == GamepadAction.A && activeView > 0) {
                            query = appsViewQuery(views[0], query)
                            true
                        } else if (press.action == GamepadAction.A && !query.isEmpty) {
                            query = query.cleared
                            true
                        } else {
                            false
                        }
                    }
                    .focusable(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        appsEmptyMessage(entries.isNotEmpty(), views.getOrNull(activeView)?.name),
                        color = MenuTokens.OnSurfaceMuted,
                    )
                    if (entries.isEmpty()) GetAppsChip()
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp),
                // The hint bar's own room (MenuTokens.HintBarRoom).
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = MenuTokens.HintBarRoom),
            ) {
                // The first section's first item holds the requester, by position: a flag set while items compose
                // left it on no item once item 0 composed again on its own, and the grid opened with nothing focused
                // (Droidtop/tracker#820).
                itemsIndexed(sections, key = { _, section -> section.title }) { sectionIndex, homeSection ->
                    // Native Android apps get their own dense, icon-first grid
                    // (columns configurable via SettingsGamingFragment's
                    // "Apps grid columns" -- see GamingPrefs.appsGridColumns),
                    // separate from the artwork-carousel HomeSectionRow every other
                    // kind still uses: apps have square launcher icons, not
                    // portrait artwork, so the same 220x260 GameCard layout wastes
                    // most of a real handheld's screen on empty card background.
                    if (homeSection.entries.firstOrNull()?.kind == LibraryEntryKind.NATIVE_ANDROID_APP) {
                        AppIconGrid(
                            homeSection,
                            columns = GamingPrefs.appsGridColumns(context),
                            firstTileFocus = if (sectionIndex == 0) firstFocus else null,
                            onLaunch = onLaunch,
                            onOptions = { entry, anchor ->
                                optionsAnchor = anchor
                                optionsFor = entry
                            },
                            onFocusedEntryChanged = onFocusedEntryChanged,
                        )
                    } else {
                        HomeSectionRow(
                            homeSection,
                            firstCardFocus = if (sectionIndex == 0) firstFocus else null,
                            onShowDetail = onShowDetail,
                            onOptions = {
                                optionsAnchor = null
                                optionsFor = it
                            },
                            onFocusedEntryChanged = onFocusedEntryChanged,
                            onToggleFavorite = onToggleFavorite,
                        )
                    }
                }
                // Games are not apps, but this is where a person on the Apps tab looks for more of anything.
                // The app catalogs are one screen with two doors, here and under Updates (tracker#501).
                item(key = "get_games") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(horizontal = LocalShellWindow.current.edgePadding),
                    ) {
                        GetAppsChip()
                        GetGamesChip()
                    }
                }
            }
        }
    }

    if (filterOpen) {
        val usageAction = SheetAction(
            title = "Include apps opened outside droidtop",
            subtitle = "Needs usage access",
            onClick = { runCatching { SettingsLaunch.start(context, AppUsageAccess.settingsIntent()) } },
        )
        LibraryFilterSheet(
            scope = scope,
            base = entries,
            query = query,
            savedViews = savedViews.views,
            onQueryChange = { query = it },
            onSaveView = { savedViews.save(it, query) },
            onForgetView = { savedViews.forget(it) },
            // The same Search row every library list's Filter opens with (tracker#498).
            onSearch = {
                filterOpen = false
                searchOpen = true
            },
            onDismiss = {
                filterOpen = false
                // Back from the system's Usage access screen lands here.
                usageTick++
            },
            facetActions = if (usageGranted) emptyMap() else mapOf(LibraryFacet.RECENTLY_USED to listOf(usageAction)),
            // Sort by lives here now that Y is Search (Droidtop/tracker#644 slice 4).
            footerActions = listOf(
                SheetAction("Sort by", null) {
                    filterOpen = false
                    sortOpen = true
                },
            ) + if (usageGranted) emptyList() else listOf(usageAction),
        )
    }
    if (sortOpen) {
        LibrarySortSheet(scope = scope, query = query, onQueryChange = { query = it }, onDismiss = { sortOpen = false })
    }
    if (searchOpen) {
        LibrarySearchDialog(
            query = query,
            matchCount = list.size,
            totalCount = total,
            onTextChange = { query = query.copy(text = it) },
            onDismiss = { searchOpen = false },
        )
    }
    optionsFor?.let { entry ->
        AppOptionsMenu(
            entry = entry,
            isGame = rules.isGame(entry.id, entry.appFacts),
            onPrimary = {
                optionsFor = null
                onLaunch(entry)
            },
            onDetails = {
                optionsFor = null
                onShowDetail(entry)
            },
            onToggleFavorite = {
                optionsFor = null
                onToggleFavorite(entry)
            },
            onMarkGame = {
                optionsFor = null
                markGame(entry)
            },
            hidden = entry.hidden,
            onToggleHidden = {
                optionsFor = null
                toggleHidden(entry)
            },
            onUninstall = {
                optionsFor = null
                startUninstall(context, entry.id)
            },
            onDismiss = { optionsFor = null },
            anchor = optionsAnchor,
        )
    }
}

/**
 * Dense, non-scrolling-per-row icon grid for the Apps tab — Android app
 * drawer style (icon + label, no artwork card), unlike [HomeSectionRow]'s
 * portrait-artwork carousel. [columns] comes from
 * [GamingPrefs.appsGridColumns] so density is user-configurable
 * independent of :shell-default's own app-drawer grid width.
 */
@Composable
internal fun AppIconGrid(
    section: HomeSection,
    columns: Int,
    firstTileFocus: FocusRequester?,
    onLaunch: (LibraryEntry) -> Unit,
    // The tile's entry, and where the tile is on the screen for its menu to open beside.
    onOptions: (LibraryEntry, AnchorRect?) -> Unit,
    onFocusedEntryChanged: (LibraryEntry?) -> Unit,
) {
    Column {
        Text(
            section.title,
            color = MenuTokens.OnSurface,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(
                start = LocalShellWindow.current.edgePadding,
                end = LocalShellWindow.current.edgePadding,
                top = MenuTokens.SectionHeadingTopGap,
                bottom = MenuTokens.SectionHeadingGap,
            ),
        )
        // Sized to fit every row with no internal scrolling of its own --
        // this grid lives inside AppsSection's outer LazyColumn (one item
        // per kind-section), and a LazyVerticalGrid can't nest inside
        // another scrollable without a fixed height. Row count is exact
        // (no clamping), so every app is always reachable, just via the
        // outer LazyColumn's scroll rather than this grid's own.
        val rowCount = (section.entries.size + columns - 1) / columns.coerceAtLeast(1)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns.coerceAtLeast(1)),
            userScrollEnabled = false,
            modifier = Modifier
                .fillMaxWidth()
                // One tile's whole anatomy, measured rather than guessed:
                // 8dp of focus-ring padding, the 64dp icon, 6dp, the name
                // and the kind line under it, and 8dp again. The grid has
                // no scroll of its own, so a height that is short by a
                // line clips the line rather than scrolling to it.
                .height(APP_TILE_HEIGHT * rowCount.coerceAtLeast(1))
                .padding(horizontal = LocalShellWindow.current.edgePadding),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            gridItemsIndexed(section.entries, key = { _, entry -> entry.id }) { index, entry ->
                AppIconTile(
                    entry = entry,
                    modifier = if (index == 0 && firstTileFocus != null) Modifier.focusRequester(firstTileFocus) else Modifier,
                    onLaunch = { onLaunch(entry) },
                    onOptions = { anchor -> onOptions(entry, anchor) },
                    onFocused = { onFocusedEntryChanged(entry) },
                )
            }
        }
    }
}

internal val APP_TILE_HEIGHT = 136.dp

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AppIconTile(
    entry: LibraryEntry,
    modifier: Modifier = Modifier,
    onLaunch: () -> Unit,
    // Given where the tile is on the screen, so its menu opens beside it (Droidtop/tracker#827).
    onOptions: (AnchorRect?) -> Unit = {},
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val bounds = rememberAnchor()
    val openOptions = { onOptions(bounds.value) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .anchorFor(bounds)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            // Ahead of the focus targets, not after them: see [GameCard].
            .onPad { press ->
                when (press.action) {
                    GamepadAction.A -> {
                        onLaunch()
                        true
                    }
                    // L2 is the focused thing's own menu (docs/SPEC.md 7j):
                    // its details, favourite and Mark as game; a long press
                    // opens the same one (owner, 2026-10-23,
                    // Droidtop/tracker#621). X and Y are the list's (Filter
                    // and Sort By) and bubble to the section around the grid.
                    GamepadAction.L2 -> {
                        openOptions()
                        true
                    }
                    else -> false
                }
            }
            .declaresHints(APP_TILE_HINTS)
            .focusable()
            // Taps work the same way as on GameCard (see its own comment).
            // A long press opens the app's menu, as a long press does on
            // every thing (owner, 2026-10-23, Droidtop/tracker#610); Mark as
            // game is a row of that menu.
            .padRow(onClick = onLaunch, onLongClick = openOptions)
            // The shell's ONE selection idiom, the same one [GameCard]
            // and the menus draw: the accent ring over a brightened
            // surface, so both grids of the shell give one answer to "what
            // does selected look like" (rig, build 546).
            .selectionFrame(
                selected = focused,
                shape = Corners.Plate,
                rest = Color.Transparent,
                restOutline = MenuTokens.CardOutline,
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(MenuTokens.Card, Corners.Plate),
        ) {
            if (entry.artworkUri != null) {
                AsyncImage(
                    model = entry.artworkUri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                )
            }
        }
        // The tile's own anatomy, the same one every card in this shell
        // has: the name, then the one line that says what the thing IS.
        // This grid holds more than one kind (`REMOTE_STREAM` is an Apps
        // kind too), so the second line matters (rig, build 546).
        //
        // It says what the TILE is, never the heading it sits under: the
        // app's own declared category when it has one, and what one entry
        // of its kind is called when it does not. Never the tab's name,
        // which the heading above already says (rig, build 547).
        Text(
            entry.title,
            color = MenuTokens.OnSurface,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp).focusMarquee(focused),
        )
        // An app that declares no category says nothing: "Android app" under every tile said nothing
        // about this one (tracker#500). Its line is still kept, empty, so every tile in a row is one height and the
        // grid's rows line up (Droidtop/tracker#820: "Calendar" was a line shorter than "Azahar / Games").
        Text(
            entry.genre?.takeIf { it.isNotBlank() }.orEmpty(),
            color = MenuTokens.OnSurfaceMuted,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            minLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.focusMarquee(focused),
        )
    }
}
