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
import dev.droidtop.library.AppGameMarks
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
 * System-first, then per-system game grid — ES-DE's System → Game
 * hierarchy, applied both to droidtop's own engine [LibraryEntryKind]s and
 * to real console systems (see [GameGroup]). `selectedGroup == null` shows
 * the system list; picking one drills into that system's games. Back
 * (D-pad-focused "Back" card, or the controller's B/Back key) returns to
 * the system list.
 */
@Composable
internal fun GamesSection(
    entries: List<LibraryEntry>,
    library: Library,
    nav: ShellBackStack,
    onLaunch: (LibraryEntry) -> Unit,
    onShowDetail: (LibraryEntry) -> Unit,
    onDrillDownChanged: (Boolean) -> Unit,
    onFocusedEntryChanged: (LibraryEntry?) -> Unit,
    onHelpRowClaim: (HelpRowClaim) -> Unit = {},
    onToggleFavorite: (LibraryEntry) -> Unit = {},
    onRequestRescan: () -> Unit = {},
) {
    // The in-gamelist options overlay (sort, scrape, import) -- see
    // GamelistOptionsMenu. sortVersion invalidates the games ordering
    // below when the overlay cycles the stored sort; scrapeVersion does
    // the same for artwork after an in-place scrape.
    var gamelistOptionsOpen by remember { mutableStateOf(false) }
    var sortVersion by remember { mutableIntStateOf(0) }
    // The focused game's own menu, from L2 or a long press on its card (Droidtop/tracker#621 M6).
    var optionsFor by remember { mutableStateOf<LibraryEntry?>(null) }
    // Where that game's card is on the screen, so the menu opens beside it (Droidtop/tracker#827); null centres it.
    var optionsAnchor by remember { mutableStateOf<AnchorRect?>(null) }
    val firstFocus = remember { FocusRequester() }
    val context = LocalContext.current

    // Real custom collections + membership, loaded once and refreshed
    // whenever collectionsVersion bumps (mirrors ThemePrefs.version's own
    // "force a real refresh, not just recomposition-by-luck" pattern) --
    // CollectionMembershipEditor calls CollectionsRefresh.bump() after
    // any real create/toggle.
    var customCollections by remember { mutableStateOf<List<dev.droidtop.library.consoles.CollectionEntity>>(emptyList()) }
    var customCollectionMembership by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    LaunchedEffect(CollectionsRefresh.version) {
        customCollections = library.getCollections()
        customCollectionMembership = library.getCollectionMembership()
    }
    val entriesById = remember(entries) { entries.associateBy { it.id } }
    // Real ES-DE auto-collections -- computed on the fly, never stored
    // (see GameGroup.Collection's own doc comment). Only shown when
    // non-empty, same "present-and-non-empty groups" convention the
    // real system/engine groups below already use.
    val autoCollectionGroups = remember(entries) {
        buildList {
            if (entries.isNotEmpty()) add(GameGroup.Collection(AutoCollections.ALL_GAMES_ID, "All games", "auto-allgames"))
            if (entries.any { it.favorite }) add(GameGroup.Collection(AutoCollections.FAVORITES_ID, "Favorites", "auto-favorites"))
            if (entries.any { it.lastPlayedEpochMs != null }) add(GameGroup.Collection(AutoCollections.LAST_PLAYED_ID, "Last played", "auto-lastplayed"))
        }
    }
    // One rule with PC Games (docs/SPEC.md 7g, "A store's collections"): a
    // collection is a Retro one only while it has a Retro member, so a store's
    // collections of PC games never show here, and it lists its Retro members only.
    val retroMembership = remember(customCollections, customCollectionMembership) {
        dev.droidtop.library.CollectionMembership(customCollections, customCollectionMembership)
    }
    val customCollectionGroups = remember(retroMembership, entriesById) {
        dev.droidtop.library.CollectionScope.retroCollections(retroMembership) { it in entriesById }
            .map { GameGroup.Collection(it.id, it.name, AutoCollections.CUSTOM_THEME_FOLDER) }
    }
    val collectionGroups = autoCollectionGroups + customCollectionGroups
    // Real cross-cutting membership -- a game can be in several
    // collections at once, unlike the strict system/engine partition
    // below (see GameGroup.Collection's own doc comment).
    val collectionGroupMembers = remember(collectionGroups, entries, retroMembership) {
        collectionGroups.associateWith { group ->
            when (group.id) {
                AutoCollections.ALL_GAMES_ID -> entries
                AutoCollections.FAVORITES_ID -> entries.filter { it.favorite }
                AutoCollections.LAST_PLAYED_ID -> entries
                    .filter { it.lastPlayedEpochMs != null }
                    .sortedByDescending { it.lastPlayedEpochMs }
                    .take(AutoCollections.LAST_PLAYED_LIMIT)
                else -> dev.droidtop.library.CollectionScope.retroMembers(group.id, retroMembership, entriesById)
            }
        }
    }
    // Present-and-non-empty groups -- engines first (in GAME_KINDS'
    // declaration order), then real console systems (alphabetical by
    // display name) -- hoisted so both the system-list view and the
    // per-system grid view share one ordering (needed for ES-DE-style
    // Left/Right sibling-system switching below).
    val byGroup = entries.filter { it.systemId != null }.groupBy { it.gameGroup() }
    // Keyed on the platform database's load version as well as the
    // groups: labels resolve through its cache, which warms on a
    // background thread -- sorting before the warm lands used raw
    // system ids and froze "switch" at the end of the carousel
    // (observed live) instead of "Nintendo Switch" among the Nintendos.
    val platformsLoadVersion by dev.droidtop.library.consoles.PlatformsDatabase.loadVersion.collectAsState()
    val orderedSystemGroups = remember(entries, platformsLoadVersion) {
        retroGamesSystemIds(entries).map { GameGroup.System(it) }.sortedBy { it.label.lowercase() }
    }
    // Carousel order, per direction (2026-08-31): "All games" leads
    // straight into the real systems -- Favorites/Last played/custom
    // collections were wedged between them, which meant scrolling past
    // chrome to reach content. They trail at the end instead.
    val leadingCollections = collectionGroups.filter { it.id == AutoCollections.ALL_GAMES_ID }
    val trailingCollections = collectionGroups.filterNot { it.id == AutoCollections.ALL_GAMES_ID }
    val orderedGroups: List<GameGroup> =
        leadingCollections + orderedSystemGroups + trailingCollections

    // WHERE the shell is, asked rather than owned (see ShellBackStack):
    // this screen is rebuilt from scratch every time something else is
    // drawn over it, so a group it remembered itself would be lost every
    // time a game detail opened.
    val selectedGroup = orderedGroups.firstOrNull { it.key == nav.groupKey }
    val selectGroup: (GameGroup?) -> Unit = { group -> nav.openGroup(group?.key) }
    // Real per-game navigation index for the drilled-into-a-system
    // "gamelist" screen, ONLY used when the active theme's own real
    // gamelist view has no <carousel>/<grid>/<textlist> of its own to
    // delegate D-pad focus-tracking to (DEcaffe's real gamelist view is
    // exactly this case -- see EsDeThemeView.primaryListElement's own
    // updated doc comment). Reset whenever the drilled-into system
    // changes, matching real ES-DE's own "selection resets per gamelist"
    // convention.
    val selectedGroupLabel = selectedGroup?.label
    val gamelistTheme = rememberActiveTheme(selectedGroup, wanted = selectedGroup != null)
    // Real navigation-sound (re)binding -- <sound> elements are parsed
    // like any other themed element (declared under the special `all`
    // view, expanded into system+gamelist by the parser), so whichever
    // theme parse this screen just loaded carries the current sound set.
    // Keyed on the theme object itself: reparses for a different focused
    // system rebind to identical paths (cache hits, see
    // EsDeNavigationSounds.load's own doc comment).
    LaunchedEffect(gamelistTheme) { EsDeNavigationSounds.load(gamelistTheme) }
    val gamelistView = gamelistTheme?.views?.get("gamelist")
    val gamelistHasListWidget = remember(gamelistView) { gamelistView?.primaryListElement() != null }
    // Alphabetical -- real ES-DE's own default gamelist sort order, and a
    // real, stable Up/Down order for the headless (no list widget) case
    // below, unlike allGames' own natural Library order.
    // The group's own filter and sort: the same query model, sheets and
    // remembered state every library list uses (docs/SPEC.md 7j). The
    // stored view is read once per group and written back, both off the
    // main thread (PersistQuery).
    val retroScope = remember(selectedGroup?.label) { retroQueryScope(selectedGroup?.label.orEmpty()) }
    // Real ES-DE's Last Played collection opens in recency order, the one list where A to Z would defeat its
    // purpose; the person can still pick another sort and it is remembered like any list's.
    val retroDefault = remember(selectedGroup) {
        if ((selectedGroup as? GameGroup.Collection)?.id == AutoCollections.LAST_PLAYED_ID) {
            LibraryQuery(sort = dev.droidtop.shell.gamepad.query.LibrarySortKey.RECENT)
        } else {
            LibraryQuery()
        }
    }
    // The group's own list state, one holder per group: moving to another system starts its own. The default
    // query draws until the stored view, read off the main thread, lands.
    val gamelist = remember(selectedGroup, retroScope.id) { GamelistState(retroDefault) }
    PersistQuery(retroScope.id, gamelist.query, gamelist.queryLoaded, default = retroDefault) {
        gamelist.query = it
        gamelist.queryLoaded = true
    }
    val retroSaved = rememberSavedViews(retroScope.id)
    val retroBase = remember(entries, selectedGroup, collectionGroupMembers) {
        when (val group = selectedGroup) {
            null -> emptyList()
            // A collection's members are not a system's games: its own list, through the same model.
            is GameGroup.Collection -> collectionGroupMembers[group].orEmpty()
            else -> entries.filter { it.gameGroup() == group }
        }
    }
    val systemGamesBeforeSearch = remember(selectedGroup, sortVersion, gamelist.query, retroScope, retroBase) {
        // The stored per-group sort and filter: NAME by default, which is real ES-DE's own gamelist
        // default, and no filter. A collection is a list like any other.
        if (selectedGroup == null) emptyList() else gamelist.query.applyTo(retroBase, retroScope)
    }
    // The console gamelist's search (Select menu -> Search, the shared
    // LibrarySearchDialog, docs/SPEC.md 12a "Search fan-out"): narrows the
    // list by the one shared text rule.
    val systemGamesForGroup = remember(systemGamesBeforeSearch, gamelist.searchText) {
        if (gamelist.searchText.isBlank()) {
            systemGamesBeforeSearch
        } else {
            systemGamesBeforeSearch.filter { dev.droidtop.shell.gamepad.query.matchesSearchText(it, gamelist.searchText) }
        }
    }
    // Which game the gamelist is on. Restored from the stack, so coming
    // back from a game's detail lands on that game rather than at the top
    // of the list; 0 -- ES-DE's own "selection resets per gamelist" -- for
    // a group this session has not been in.
    var focusedGameIndex by remember(selectedGroup) {
        mutableStateOf(systemGamesForGroup.indexOfFirst { it.id == nav.focusHere }.coerceAtLeast(0))
    }
    if (gamelistOptionsOpen) {
        val group = selectedGroup
        run {
            GamelistOptionsMenu(
                // An empty key is the library scope: no group is open.
                groupKey = group?.label.orEmpty(),
                groupLabel = group?.label ?: "Library",
                systemId = (group as? GameGroup.System)?.systemId,
                // A finished scrape/import refreshes the REAL library
                // scan -- cached rows now live-resolve media, so the
                // refresh is what makes new art visible immediately.
                onScraped = {
                    sortVersion += 1
                    onRequestRescan()
                },
                onDismiss = { gamelistOptionsOpen = false },
                games = systemGamesForGroup,
                searchText = gamelist.searchText,
                totalGames = systemGamesBeforeSearch.size,
                onSearchTextChange = if (group == null) null else { text ->
                    gamelist.searchText = text
                    focusedGameIndex = 0
                },
                onJumpTo = { index ->
                    focusedGameIndex = index.coerceIn(0, (systemGamesForGroup.lastIndex).coerceAtLeast(0))
                },
                onOpenSort = if (group == null) null else ({ gamelist.sortOpen = true }),
                onOpenFilter = if (group == null) null else ({ gamelist.filterOpen = true }),
            )
        }
    }
    if (gamelist.sortOpen) {
        LibrarySortSheet(
            scope = retroScope,
            query = gamelist.query,
            onQueryChange = {
                gamelist.query = it
                focusedGameIndex = 0
            },
            onDismiss = { gamelist.sortOpen = false },
        )
    }
    if (gamelist.filterOpen) {
        LibraryFilterSheet(
            scope = retroScope,
            base = retroBase,
            query = gamelist.query,
            savedViews = retroSaved.views,
            onQueryChange = {
                gamelist.query = it
                focusedGameIndex = 0
            },
            onSaveView = { retroSaved.save(it, gamelist.query) },
            onForgetView = { retroSaved.forget(it) },
            onSearch = null,
            onDismiss = { gamelist.filterOpen = false },
        )
    }

    // Real, unified themed-gamelist condition -- ONE real render path
    // below handles ANY theme with a real "gamelist" view, whether or not
    // it declares its own <carousel>/<grid>/<textlist>
    // (gamelistHasListWidget is an internal detail EsDeThemedView/
    // EsDeSystemListView already decide for themselves, exactly like the
    // system-list screen's own carousel/grid/textlist dispatch -- NOT a
    // droidtop-level "which theme is this" branch. Two real themes
    // (DEcaffe: no widget: Art Book Next: real <textlist>/<grid>) already
    // confirm both real shapes render correctly through this one path;
    // an arbitrary third-party theme should too, since nothing here is
    // keyed off theme identity, only off what the theme itself declares.
    // The OLD hand-built grid is only the real fallback for "no active
    // theme" or "the loaded theme genuinely has no gamelist view at all"
    // (invalid for a real ES-DE theme, but a real crash-guard, not a
    // normal case).
    val hasThemedGamelist = gamelistView != null
    // Real EsDeListItem per game, only built when actually needed (the
    // widget-driven render path) -- boxart as the item's "logo" image,
    // no item count (a real game has none, unlike a system group).
    // onSelect matches the system-list screen's own real convention (A
    // activates the focused item) -- launching directly on select, since
    // a themed gamelist view has no separate "drill in further" step the
    // way the system list's onSelect (open this system) does.
    // Keyed on the group too: whether a game shows its own system as a
    // suffix depends on whether the list being shown IS a collection.
    val inCollectionGamelist = selectedGroup is GameGroup.Collection
    // A theme whose list does not name the source system (its own `systemNameSuffix` off, or no list
    // widget at all) would show the same title for two systems as two identical rows (tracker#497).
    val themeNamesSystem = gamelistView?.primaryListElement()?.let { it.boolOrNull("systemNameSuffix") ?: true } ?: false
    val gamelistWidgetItems = remember(systemGamesForGroup, inCollectionGamelist, themeNamesSystem) {
        val labels = if (inCollectionGamelist && !themeNamesSystem) {
            dev.droidtop.library.theme.esDeDisambiguatedTitles(
                systemGamesForGroup.map { it.title },
                systemGamesForGroup.map { it.systemId },
            )
        } else {
            systemGamesForGroup.map { it.title }
        }
        systemGamesForGroup.mapIndexed { index, entry ->
            EsDeListItem(
                key = entry.id,
                label = labels[index],
                logoPath = entry.artworkUri,
                // A opens the game's details, where Play is (Droidtop/tracker#641).
                onSelect = { onShowDetail(entry) },
                // Lets a themed carousel/grid honour its own real
                // <imageType> for this game instead of always drawing the
                // one pre-resolved artwork. Coordinates only, no I/O here.
                mediaLocator = entry.mediaLocator,
                // Real ES-DE textlist indicators: a favorite game gets a
                // leading marker before its name in a gamelist.
                favorite = entry.favorite,
                // Real `systemNameSuffix`: inside a COLLECTION's gamelist
                // every game names the system it really comes from
                // (GamelistBase.cpp:789-806). Outside one this stays null,
                // which is ES-DE's own `isCollection` guard.
                // ES-DE appends the SHORT system name here
                // (getSourceFileData()->getSystem()->getName(), e.g.
                // "megadrive"), which is what systemId is.
                sourceSystemName = if (inCollectionGamelist) entry.systemId else null,
            )
        }
    }
    LaunchedEffect(selectedGroup, hasThemedGamelist, focusedGameIndex) {
        // Real regardless of whether the theme's gamelist has its own
        // list widget -- a widget's onFocusedIndexChanged (wired at the
        // render call site below) updates the exact same focusedGameIndex
        // state the headless case's own Up/Down handling uses.
        // PC and engine games go through this same themed gamelist
        // (docs/SPEC.md 7i, revised 2026-09-26).
        if (hasThemedGamelist) {
            val focused = systemGamesForGroup.getOrNull(focusedGameIndex)
            onFocusedEntryChanged(focused)
            // What to come back to. Recorded as the user moves, not only
            // when they open something, so B out of a detail and B out of
            // the gamelist agree about where they were.
            nav.rememberFocus(systemGamesForGroup.getOrNull(focusedGameIndex)?.id)
        }
    }
    LaunchedEffect(selectedGroup, hasThemedGamelist, nav.optionsOpen) {
        onDrillDownChanged(selectedGroup != null)
        if (selectedGroup != null) {
            // A THEMED view lays the help row out itself, at its own
            // <helpsystem> position or -- declaring none -- at ES-DE's own
            // default for that component, which exists whether a theme
            // styles it or not (HelpComponent.cpp:23-27). So the claim is
            // about the canvas, not about the element: it is what stops the
            // shell putting its bar in a strip BELOW a themed canvas and
            // shortening the canvas to fit (rig, build 548). The
            // hand-built grid (the fallback for "no active theme" / "theme
            // has no real gamelist view at all") is not a themed canvas and
            // keeps the shell's bar in the column.
            onHelpRowClaim(
                when {
                    // A group's own options screen (ShellBackStack.Options,
                    // a level above the group) is a plain droidtop screen
                    // drawn over the group: it has no row of its own, so
                    // the shell's bar draws there -- and its B hint is the
                    // only touch route out.
                    nav.optionsOpen -> HelpRowClaim.NONE
                    hasThemedGamelist -> HelpRowClaim.THEME
                    else -> HelpRowClaim.NONE
                },
            )
        }
    }

    // System-back route for the drill-up: on this hardware B doubles as
    // KEYCODE_BACK, which Android delivers through the back DISPATCHER
    // (Activity back), not as a key event any composable sees, so the
    // onKeyEvent branch below never receives it. BackHandler registers on
    // the dispatcher so B drills up instead of finishing the Activity;
    // composed
    // deeper than GamepadShell's own root swallow, so it wins while a
    // group is open.
    androidx.activity.compose.BackHandler(enabled = selectedGroup != null) {
        // Real ES-DE back sound (GamelistBase.cpp:144/156 -- leaving a
        // gamelist for the system view plays BACKSOUND) -- this dispatcher
        // route and the onKeyEvent branch below are the same real drill-up,
        // just different hardware paths (see this handler's own comment).
        EsDeNavigationSounds.play(UiSound.BACK)
        // One level at a time: a group's own options screen is above the
        // group, so back leaves THAT first.
        nav.back()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // The shoulders mean "switch system" at this level and "switch
            // section" above it; inside a screen opened from the group they
            // mean nothing, so they never reach the group or tab under that
            // screen (rig, build 549). Taken before
            // anything below sees them.
            .onPad(preview = true) { press ->
                nav.optionsOpen && (press.action == GamepadAction.L || press.action == GamepadAction.R)
            }
            // B is not here: it is the back dispatcher's, through the shell's
            // root, and this group's BackHandler above is the one that answers
            // it -- one route for the pad's B and the system back key alike
            // (SPEC 6e). The guard this handler kept against the release of a
            // B that had closed the options screen above it is gone with it:
            // a release belongs to the press that began it.
            .onPad(cadence = PadCadence.THEMED_LIST) { press ->
                // A screen opened FROM the group owns its own presses while
                // it is up.
                if (nav.optionsOpen) return@onPad false
                val group = selectedGroup
                val themed = hasThemedGamelist
                val headless = group != null && themed && !gamelistHasListWidget &&
                    systemGamesForGroup.isNotEmpty()
                when (press.action) {
                    // Gamelist options (sort/scrape/import) right where the
                    // user is -- the ES-DE GuiGamelistOptions PATTERN in
                    // droidtop's own placement (Select; the Quick Menu is R2
                    // or a held Select). A system's gamelist gets sort/
                    // scrape/import, the carousel the library-wide actions.
                    GamepadAction.SELECT -> {
                        gamelistOptionsOpen = true
                        true
                    }
                    // ES-DE's "General navigation": Left/Right inside a
                    // gamelist jumps to the adjacent system's gamelist, with
                    // ES-DE's quicksysselect sound (ViewController.cpp:718/
                    // 728). The shoulders mean switch section everywhere
                    // (owner, 2026-09-27), so they fall through to the root.
                    GamepadAction.LEFT, GamepadAction.RIGHT -> {
                        if (group == null || orderedGroups.size <= 1) return@onPad false
                        EsDeNavigationSounds.play(UiSound.TAB)
                        val index = orderedGroups.indexOf(group)
                        val step = if (press.action == GamepadAction.LEFT) -1 else 1
                        selectGroup(orderedGroups[(index + step + orderedGroups.size) % orderedGroups.size])
                        true
                    }
                    // Headless per-game navigation, only when the theme's
                    // gamelist has no list widget of its own to own the
                    // cursor. Clamped (tracker#1): ES-DE's textlist pauses
                    // at its ends, and wrapping here read as "Up escaped the
                    // list". ES-DE's scroll sound (GamelistBase.cpp:133).
                    GamepadAction.UP, GamepadAction.DOWN -> {
                        if (!headless) return@onPad false
                        val next = (focusedGameIndex + if (press.action == GamepadAction.UP) -1 else 1)
                            .coerceIn(0, systemGamesForGroup.size - 1)
                        if (next != focusedGameIndex) EsDeNavigationSounds.play(UiSound.MOVE)
                        focusedGameIndex = next
                        true
                    }
                    GamepadAction.A -> headless && systemGamesForGroup.getOrNull(focusedGameIndex)?.let { onShowDetail(it) } != null
                    // Y/Info, with or without a list widget.
                    GamepadAction.Y -> group != null && themed &&
                        systemGamesForGroup.getOrNull(focusedGameIndex)?.let { onShowDetail(it) } != null
                    // X/favourite, with or without a list widget.
                    GamepadAction.X -> group != null && themed &&
                        systemGamesForGroup.getOrNull(focusedGameIndex)?.let { onToggleFavorite(it) } != null
                    else -> false
                }
            },
    ) {
        // Real ES-DE view transitions (ViewController.cpp:664-1010,
        // chosen per transition kind by ThemeData::setThemeTransitions at
        // :1042-1120). droidtop cut between the system view and a gamelist
        // no matter what the theme asked for; decaffe's own default
        // profile fades both ways, and twelve of the fifteen themes
        // measured for this pass declare a profile of some kind.
        //
        // The animation depends on WHICH transition this is, so the
        // animated state is the selected group itself rather than a
        // boolean: the outgoing content has to keep rendering the group it
        // was showing while it leaves.
        val esDeTransitions = remember(ThemePrefs.version) { ThemeAssets.activeTransitions(context) }
        // Which of ES-DE's six transitions the move currently on screen
        // is, which is what both of the transition-time element
        // behaviours turn on. The pair of endpoints is plain bookkeeping,
        // not state: it is read in the same composition that changes it
        // and never needs to trigger one of its own.
        val groupHistory = remember { EsDeGroupHistory() }
        if (groupHistory.current != selectedGroup) {
            groupHistory.previous = groupHistory.current
            groupHistory.current = selectedGroup
        }
        val transitionKind = esDeTransitionKind(groupHistory.previous, groupHistory.current)
        val transitionAnimation = esDeTransitions[transitionKind] ?: EsDeTransitionAnimation.INSTANT
        val transitionTowardsGamelist = groupHistory.previous == null && groupHistory.current != null
        androidx.compose.animation.AnimatedContent(
            targetState = selectedGroup,
            // Both views are full-screen; without this the animated
            // container would wrap its content instead of owning the
            // screen a themed full-bleed background needs.
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val towardsGamelist = initialState == null && targetState != null
                val kind = esDeTransitionKind(initialState, targetState)
                esDeViewTransition(
                    esDeTransitions[kind] ?: EsDeTransitionAnimation.INSTANT,
                    towardsGamelist = towardsGamelist,
                )
            },
            label = "ES-DE view transition",
        ) { group ->
            // One context for every themed element below: what this copy
            // of the view is doing right now. See EsDeTransitionContext.
            val esDeTransition = esDeTransitionContext(
                kind = transitionKind,
                animation = transitionAnimation,
                towardsGamelist = transitionTowardsGamelist,
                outgoing = group != selectedGroup,
            )
            if (group == null) {
                val continuePlaying = entries.filter { it.lastPlayedEpochMs != null }.sortedByDescending { it.lastPlayedEpochMs }
                // NOTE: the focus request for this screen lives INSIDE the
                // render branch below, where whether firstFocus will actually
                // ATTACH to anything is knowable (see its own comment). A
                // theme whose system view declares no carousel/grid/textlist
                // (ES-DWEE) gives the requester nothing to attach to.
                if (entries.isEmpty()) {
                    // An empty library offers the one thing that fills it,
                    // never a sentence with nothing to press
                    // (Droidtop/tracker#172): the same Game folders screen
                    // Settings and the Launcher's games grid open, drawn in
                    // place. CatalogNavigator owns its own B, so B leaves it.
                    val foldersScreen = remember { dev.droidtop.library.settings.SettingsScreenRegistry.get(GAME_FOLDERS_SCREEN_ID) }
                    var addingFolders by remember { mutableStateOf(false) }
                    if (addingFolders && foldersScreen != null) {
                        // The footer promises what this page does: A selects, B leaves it. Without this it kept
                        // the Games tab's own Options hint, which opens nothing over an empty library (#537).
                        Box(Modifier.fillMaxSize().declaresHints(EMPTY_FOLDERS_HINTS)) {
                            CatalogNavigator(root = foldersScreen, onExit = { addingFolders = false })
                        }
                    } else {
                        // The one empty state of a games place (Droidtop/tracker#533).
                        EmptyLibrary(
                            line = "No games yet",
                            actionLabel = "Add a folder",
                            onAction = { if (foldersScreen != null) addingFolders = true },
                            onChanged = onRequestRescan,
                        )
                    }
                } else {
                    // Box, not Column: EsDeThemedView needs to genuinely fill
                    // the whole screen (a real full-bleed background image is
                    // one of its own themed elements) -- a Column sibling would
                    // have measured it against the Column's remaining-height
                    // constraint and clipped/overlapped continuePlaying instead,
                    // the same class of sizing bug fillMaxSize itself just
                    // fixed at the EsDeThemedView call site below. continuePlaying
                    // renders on top, anchored to the top -- the reference
                    // theme has no equivalent concept, so this is droidtop's
                    // own addition layered over the theme rather than part of it.
                    Box(modifier = Modifier.fillMaxSize()) {
                        val context = LocalContext.current
                        // Real per-system metadata (systemName/systemManufacturer/
                        // systemReleaseYear/...) needs a theme parsed with the
                        // CURRENTLY FOCUSED system's own ${system.theme}
                        // substituted -- see ThemeAssets.loadActiveTheme's own
                        // doc comment for why this can't be one static parse.
                        // focusedSystemIndex is fed by EsDeThemedView's own
                        // onFocusedIndexChanged, driven by whichever carousel
                        // item actually has focus right now.
                        // Starts on the system the person came from when they
                        // press Back out of its gamelist (this branch is rebuilt
                        // every time), found by id in the current carousel so a
                        // rescan that reorders the cards cannot move it
                        // (Droidtop/tracker#642).
                        val restoredSystemIndex = remember {
                            restoredFocusIndex(orderedGroups.map { it.key }, nav.focusHere)
                        }
                        var focusedSystemIndex by remember { mutableStateOf(restoredSystemIndex) }
                        // ES-DE's system-to-system FADE (SystemView.cpp:
                        // 447-458): the element layer fades to black over
                        // the first fifth of the animation, holds, swaps
                        // the system underneath at the midpoint and fades
                        // back in -- the carousel itself keeps moving
                        // throughout, which is why the swap is a second,
                        // delayed index rather than the one the carousel
                        // reads. With any other animation the element
                        // layer follows the carousel immediately, as it
                        // always has.
                        val systemToSystem = esDeTransitions[EsDeViewTransition.SYSTEM_TO_SYSTEM]
                            ?: EsDeTransitionAnimation.INSTANT
                        var elementSystemIndex by remember { mutableStateOf(focusedSystemIndex) }
                        val systemFadeOpacity = remember { Animatable(0f) }
                        LaunchedEffect(focusedSystemIndex, systemToSystem) {
                            if (systemToSystem != EsDeTransitionAnimation.FADE ||
                                elementSystemIndex == focusedSystemIndex
                            ) {
                                elementSystemIndex = focusedSystemIndex
                                systemFadeOpacity.snapTo(0f)
                            } else {
                                launch {
                                    systemFadeOpacity.animateTo(
                                        0f,
                                        esDeSystemFadeSpec(systemFadeOpacity.value),
                                    )
                                }
                                delay(ES_DE_SYSTEM_FADE_MS / 2)
                                elementSystemIndex = focusedSystemIndex
                            }
                        }
                        val focusedGroup = orderedGroups.getOrNull(elementSystemIndex)
                        val focusedGroupLabel = focusedGroup?.label
                        val theme = rememberActiveTheme(focusedGroup)
                        // Same real navigation-sound rebinding as the gamelist
                        // screen's own hook (see that LaunchedEffect's comment)
                        // -- this is the site that runs FIRST after app start /
                        // a live theme switch, so sounds bind before any
                        // gamelist is ever entered.
                        LaunchedEffect(theme) { EsDeNavigationSounds.load(theme) }
                        val listElement = remember(theme) { theme?.views?.get("system")?.primaryListElement() }
                        // Every group's theme parse, and the carousel logo
                        // read out of it, worked out in the background, never
                        // in composition and never again on every library
                        // publish. Keyed on the groups' theme keys, not on
                        // the group list, which is a new list on every
                        // publish of a walk. Each logo lands as its group is
                        // done; until then that group draws its name, which
                        // is what a logo-less system draws anyway. The same
                        // loop warms the parse cache rememberActiveTheme
                        // reads, so moving to another system or opening its
                        // gamelist does not parse.
                        val groupThemeKeys = remember(orderedGroups) {
                            orderedGroups.map { it.key to it.themeKey() }
                        }
                        var logos by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
                        LaunchedEffect(groupThemeKeys, ThemePrefs.version) {
                            for ((groupKey, themeKey) in groupThemeKeys) {
                                val logo = withContext(Dispatchers.Default) {
                                    if (themeKey.systemId == null) null
                                    else themeKey.load(context)?.let { ThemeAssets.systemLogoPath(it) }
                                }
                                logos = logos + (groupKey to logo)
                            }
                        }
                        // remember(): the carousel recomposes every
                        // animation frame, and this list only changes with
                        // the groups or their logos.
                        val items = remember(orderedGroups, logos) {
                            orderedGroups.map { entryGroup ->
                                EsDeListItem(
                                    key = entryGroup.key,
                                    label = entryGroup.label,
                                    logoPath = logos[entryGroup.key],
                                    // Real `letterCaseAutoCollections` /
                                    // `letterCaseCustomCollections`: ES-DE cases a
                                    // collection's own name by which KIND of
                                    // collection it is (SystemView.cpp:835-849).
                                    collectionKind = entryGroup.esDeCollectionKind(),
                                    // Real ES-DE select sound -- entering a
                                    // system from the system view plays
                                    // SELECTSOUND (SystemView.cpp:129).
                                    onSelect = {
                                        EsDeNavigationSounds.play(UiSound.CONFIRM)
                                        selectGroup(entryGroup)
                                    },
                                )
                            }
                        }
                        // The theme drives this whole screen's layout. The
                        // carousel/grid/textlist is positioned at the theme's
                        // own pos/size (see EsDeThemedView's own doc comment)
                        // alongside every other themed element (background
                        // art, info text, help icons), composited together by
                        // z-index. fillMaxSize lets a full-bleed background
                        // image cover the whole screen. There is no separate
                        // "Systems" label: the theme's own carousel title
                        // treatment says what is selected, matching the
                        // reference theme.
                        // The real games backing whichever system the carousel
                        // currently has focus on -- feeds EsDeThemedView's own
                        // gameselector-driven elements (screen2's game-preview
                        // poster, the game1..game9 mosaic, the metadata-bound
                        // title caption).
                        // A function of the GROUP, not of the focused one:
                        // while the element layer slides between systems it
                        // draws the neighbours too, each bound to its own
                        // system's games (SystemView.cpp's mSystemElements is
                        // parsed and fed per system).
                        //
                        // byGroup only partitions system groups --
                        // a Collection's members live in collectionGroupMembers
                        // (cross-cutting, see GameGroup.Collection's own doc
                        // comment). Reading byGroup for a collection returned
                        // an empty list, which showed up on-device as
                        // "0 games (0 favorites)" in the themed gamecount strip
                        // and an empty game-preview for every collection.
                        fun entriesOf(entryGroup: GameGroup?): List<LibraryEntry> = when (entryGroup) {
                            null -> emptyList()
                            is GameGroup.Collection -> collectionGroupMembers[entryGroup].orEmpty()
                            else -> byGroup[entryGroup].orEmpty()
                        }
                        // Real ES-DE special case (SystemView.cpp's own
                        // favoriteSystem/recentSystem flags): those two
                        // auto-collections show a bare game count.
                        fun countsOnly(entryGroup: GameGroup?): Boolean =
                            (entryGroup as? GameGroup.Collection)?.id
                                ?.let { it == AutoCollections.FAVORITES_ID || it == AutoCollections.LAST_PLAYED_ID } == true
                        val focusedSystemEntries = entriesOf(orderedGroups.getOrNull(elementSystemIndex))
                        // The hints this screen can keep (docs/SPEC.md 7j: a hint
                        // row promises only what dispatches). No Y: on the
                        // carousel nothing is focused that has info -- Y acts on
                        // a focused GAME, in a gamelist or on a card -- and the
                        // row said "Info" to a button that did nothing (UI pass
                        // 2026-09-24, screenshots 01/06). No shoulder either: L1/R1
                        // never switch sections (docs/SPEC.md 7j, "Gaming controls"),
                        // and nothing here is a tab strip they could step.
                        // Start and R2 end the theme's row, as real ES-DE's own row ends on START MENU (#510).
                        val systemListHints = listOf(GamepadAction.A to "Select") + rememberHintList(ShellMenuHints)
                        val systemView = theme?.views?.get("system")
                        // Same rule as the gamelist's own claim above: a
                        // themed canvas lays the help row out itself,
                        // whether or not this theme styles <helpsystem>.
                        val themedSystemView = systemView != null
                        LaunchedEffect(themedSystemView) {
                            onHelpRowClaim(if (themedSystemView) HelpRowClaim.THEME else HelpRowClaim.NONE)
                        }
                        // Crash boundary (a downloaded community theme,
                        // ES-DWEE): firstFocus only ATTACHES when a list widget
                        // composes with at least one item. The themed path
                        // does that only when the theme's system view declares
                        // a carousel/grid/textlist; the fallback path always
                        // does. Requesting focus on an unattached
                        // FocusRequester can throw IllegalStateException, so
                        // the request is gated on the attachment condition and
                        // also guarded: a theme must never be able to kill the
                        // app.
                        val willAttachFocus = items.isNotEmpty() &&
                            (systemView == null || systemView.primaryListElement() != null)
                        LaunchedEffect(willAttachFocus, systemView) {
                            if (willAttachFocus) {
                                requestFocusWhenAttached(firstFocus, "System list")
                            }
                        }
                        if (systemView != null) {
                            // Real ES-DE systembrowse sound: moving the
                            // system carousel plays SYSTEMBROWSESOUND
                            // (CarouselComponent.h:108-110 -- the primary
                            // component's own scroll plays systembrowse
                            // when NOT hosted in a gamelist, scroll when it
                            // is). Guarded on a real index CHANGE: this
                            // callback also fires for the initial focus
                            // attach, which is not a browse.
                            val onSystemFocused: (Int) -> Unit = {
                                if (it != focusedSystemIndex) EsDeNavigationSounds.play(UiSound.SYSTEM)
                                focusedSystemIndex = it
                            }
                            val systemContext = dev.droidtop.shell.gamepad.theme.EsDeSystemContext(
                                name = focusedGroupLabel,
                                gameCount = focusedSystemEntries.size,
                                favoriteCount = focusedSystemEntries.count { it.favorite },
                                countsOnly = countsOnly(focusedGroup),
                            )
                            // ES-DE's system-to-system SLIDE
                            // (SystemView.cpp:1565-1745): the element layer
                            // does not swap between systems, it travels. Each
                            // system's elements are drawn at their own
                            // distance from the carousel's continuous camera
                            // offset, so the neighbours slide in from the
                            // sides while the carousel scrolls; the carousel
                            // itself, and the window-level clock, status bar
                            // and help bar, stay put (:204, :255). That needs
                            // the view drawn in separate passes, which is the
                            // one shape this branch has that the others do
                            // not.
                            val slidingSystems = systemToSystem == EsDeTransitionAnimation.SLIDE &&
                                orderedGroups.size > 1
                            val camOffset = remember { mutableFloatStateOf(0f) }
                            val slideHorizontal = remember(listElement) {
                                EsDeSystemSlide.slidesHorizontally(listElement?.type, listElement.strOrNull("type"))
                            }
                            val systemSlot: @Composable (Int) -> EsDeSystemSlot? = { index ->
                                val slotGroup = orderedGroups.getOrNull(index)
                                rememberEsDeSystemSlot(
                                    group = slotGroup,
                                    entries = entriesOf(slotGroup),
                                    countsOnly = countsOnly(slotGroup),
                                )
                            }
                            if (slidingSystems) {
                                EsDeSystemElementLayer(
                                    layer = EsDeViewLayer.BELOW_PRIMARY,
                                    camOffset = camOffset,
                                    systemCount = orderedGroups.size,
                                    slideHorizontal = slideHorizontal,
                                    backgroundDimmed = gamelistOptionsOpen,
                                    transition = esDeTransition,
                                    slot = systemSlot,
                                )
                                EsDeThemedView(
                                    view = systemView,
                                    items = items,
                                    firstItemFocus = firstFocus,
                                    modifier = Modifier.fillMaxSize(),
                                    onFocusedIndexChanged = onSystemFocused,
                                    initialFocusIndex = restoredSystemIndex,
                                    focusedSystemEntries = focusedSystemEntries,
                                    systemContext = systemContext,
                                    backgroundDimmed = gamelistOptionsOpen,
                                    transition = esDeTransition,
                                    layer = EsDeViewLayer.PRIMARY,
                                    onCamOffsetChanged = { camOffset.floatValue = it },
                                )
                                EsDeSystemElementLayer(
                                    layer = EsDeViewLayer.ABOVE_PRIMARY,
                                    camOffset = camOffset,
                                    systemCount = orderedGroups.size,
                                    slideHorizontal = slideHorizontal,
                                    backgroundDimmed = gamelistOptionsOpen,
                                    transition = esDeTransition,
                                    slot = systemSlot,
                                )
                                // Drawn last and never moved, which is where
                                // ES-DE draws them from: the focused system's
                                // own clock, status bar and help bar are handed
                                // to the Window (SystemView.cpp:255).
                                EsDeThemedView(
                                    view = systemView,
                                    items = emptyList(),
                                    firstItemFocus = null,
                                    modifier = Modifier.fillMaxSize(),
                                    focusedSystemEntries = focusedSystemEntries,
                                    hints = systemListHints,
                                    systemContext = systemContext,
                                    backgroundDimmed = gamelistOptionsOpen,
                                    transition = esDeTransition,
                                    layer = EsDeViewLayer.WINDOW,
                                )
                            } else {
                                EsDeThemedView(
                                    view = systemView,
                                    items = items,
                                    firstItemFocus = firstFocus,
                                    modifier = Modifier.fillMaxSize(),
                                    onFocusedIndexChanged = onSystemFocused,
                                    initialFocusIndex = restoredSystemIndex,
                                    focusedSystemEntries = focusedSystemEntries,
                                    hints = systemListHints,
                                    systemContext = systemContext,
                                    // droidtop's own real equivalent of ES-DE's
                                    // Window::isBackgroundDimmed -- the options
                                    // menu is a Compose Dialog drawn over this
                                    // view with a scrim, so the theme's own
                                    // helpsystem *Dimmed variants apply while it
                                    // is open. See EsDeThemedHelpSystem.
                                    backgroundDimmed = gamelistOptionsOpen,
                                    transition = esDeTransition,
                                    systemFadeOpacity = systemFadeOpacity.value,
                                )
                            }
                            // NO droidtop chrome over a themed screen: the
                            // "Continue Playing" overlay sat directly on top of
                            // decaffe's own real metadata sidebar (and collided
                            // on every other real theme tested) -- a themed view
                            // owns its whole surface, same as real ES-DE. The
                            // row stays on the unthemed fallback below, which IS
                            // droidtop's own surface. (docs/SPEC.md §7f's
                            // "needs real per-theme-aware safe-zone placement"
                            // note is resolved by this simpler decision:
                            // themed screens get no overlay at all.)
                        } else {
                            EsDeSystemListView(
                                element = listElement,
                                items = items,
                                firstItemFocus = firstFocus,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = LocalShellWindow.current.edgePadding),
                                initialFocusIndex = restoredSystemIndex,
                            )
                            if (continuePlaying.isNotEmpty()) {
                                HomeSectionRow(
                                    HomeSection("Continue Playing", continuePlaying),
                                    firstCardFocus = null,
                                    onShowDetail = onShowDetail,
                                    onOptions = {
                                        optionsAnchor = null
                                        optionsFor = it
                                    },
                                    onFocusedEntryChanged = onFocusedEntryChanged,
                                    modifier = Modifier.align(Alignment.TopStart).padding(top = 16.dp),
                                    onToggleFavorite = onToggleFavorite,
                                )
                            }
                        }
                    }
                }
            } else if (hasThemedGamelist && gamelistView != null) {
                // Real, unified theme-driven gamelist render -- ONE call into
                // the same generic EsDeThemedView/EsDeSystemListView machinery
                // the system-list screen already uses: a game is laid out by
                // the theme's own element positions, sizes, variants and md_*
                // metadata bindings. PC and engine games are not here at all
                // any more (docs/SPEC.md 7i, 2026-10-01): they have their own
                // tab, which no theme draws.
                // A widget owns its own D-pad focus movement (real Compose
                // focus + EsDeListItem.onSelect, firstItemFocus attaches to
                // its first item); with no widget, this composable's own
                // headless Up/Down handling above drives focusedGameIndex
                // instead -- either way, onFocusedIndexChanged and
                // focusedGameIndex both point at the exact same state, so
                // every other element (metadata/rating/datetime/video) always
                // binds to whichever game is actually current.
                LaunchedEffect(group, gamelistHasListWidget, gamelistWidgetItems) {
                    // Same never-crash boundary AND same frame-retry as the
                    // system-list screen's own focus request above (see
                    // requestFocusWhenAttached).
                    if (gamelistHasListWidget && gamelistWidgetItems.isNotEmpty()) {
                        requestFocusWhenAttached(firstFocus, "Gamelist")
                    }
                }
                // Per-game hints below are gated on this: every one of
                // them acts on the game under the cursor, and an empty
                // gamelist has none.
                val gameUnderCursor = systemGamesForGroup.isNotEmpty()
                EsDeThemedView(
                    view = gamelistView,
                    items = gamelistWidgetItems,
                    firstItemFocus = if (gamelistHasListWidget) firstFocus else null,
                    modifier = Modifier.fillMaxSize(),
                    // Same real SCROLLSOUND as the headless Up/Down
                    // branch above (a widget hosted in a gamelist
                    // scrolls with the scroll sound, CarouselComponent.
                    // h:105-108) -- guarded on a real index change,
                    // same reason as the system carousel.
                    onFocusedIndexChanged = {
                        if (it != focusedGameIndex) EsDeNavigationSounds.play(UiSound.MOVE)
                        focusedGameIndex = it
                    },
                    initialFocusIndex = focusedGameIndex,
                    focusedSystemEntries = systemGamesForGroup,
                    focusedGameIndex = focusedGameIndex,
                    hints = rememberHintList(
                        listOf(
                            HintBinding(GamepadAction.A, "Launch") { gameUnderCursor },
                            HintBinding(GamepadAction.Y, "Info") { gameUnderCursor },
                            HintBinding(GamepadAction.X, "Favorite") { gameUnderCursor },
                            HintBinding(GamepadAction.B, "Back"),
                        ) + ShellMenuHints
                    ),
                    systemContext = dev.droidtop.shell.gamepad.theme.EsDeSystemContext(
                        name = selectedGroupLabel,
                        gameCount = systemGamesForGroup.size,
                        favoriteCount = systemGamesForGroup.count { it.favorite },
                        countsOnly = (group as? GameGroup.Collection)?.id
                            ?.let { it == AutoCollections.FAVORITES_ID || it == AutoCollections.LAST_PLAYED_ID } == true,
                    ),
                    backgroundDimmed = gamelistOptionsOpen,
                    gamelist = true,
                    collectionGamelist = inCollectionGamelist,
                    transition = esDeTransition,
                )
            } else {
                // The group's list through the one query model, like the themed list above.
                val games = systemGamesForGroup
                // Same "don't request focus on an unattached FocusRequester" fix
                // as the system-list view above -- games can be empty here too
                // (the "recent" filter selected with zero recently-played entries).
                LaunchedEffect(group, gamelist.query) { if (games.isNotEmpty()) requestFocusWhenAttached(firstFocus, "Game grid") }
                // Same real per-system accent as GroupCard's own border, applied
                // as a subtle top-down vignette behind the whole grid -- carries
                // the "dynamic per-system," not just per-card, through into the
                // actual game-browsing view rather than stopping at the system
                // list.
                val drillDownAccent = group.systemThemeFolder
                    ?.let { SystemThemeColors.forSystem(LocalContext.current, it) }
                    ?.let { Color(it) }
                Column(
                    modifier = Modifier.fillMaxSize().let {
                        if (drillDownAccent != null) {
                            it.background(Brush.verticalGradient(listOf(drillDownAccent.copy(alpha = 0.16f), Color.Transparent)))
                        } else {
                            it
                        }
                    },
                ) {
                    // The one pill the other lists draw while something filters ("Favourites, 12 of 80"),
                    // cleared by one press; no count line and no chip row otherwise.
                    gamelist.query.pillText(retroScope, games.size, gamelist.query.totalIn(retroBase, retroScope))?.let { pill ->
                        Row(
                            modifier = Modifier.padding(horizontal = LocalShellWindow.current.edgePadding, vertical = 8.dp),
                        ) {
                            ShellChip(pill, on = true, onClick = { gamelist.query = gamelist.query.cleared })
                        }
                    }
                    val pad = rememberGridPad()
                    LazyVerticalGrid(
                        state = pad.state,
                        columns = GridCells.Adaptive(minSize = 220.dp),
                        // The hint bar's own room (MenuTokens.HintBarRoom).
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            bottom = MenuTokens.HintBarRoom,
                        ),
                        // Arrow keys move focus between games here: GameCard
                        // handles only A/Center/Enter/Y, and Compose has no
                        // automatic arrow-key focus movement in a grid.
                        // FocusManager.moveFocus's return value (true = moved,
                        // false = no further focusable target that direction)
                        // is what onKeyEvent needs: false lets Left/Right
                        // bubble up to the outer Box's sibling-system switcher
                        // (see GamesSection's own onKeyEvent above) only at a
                        // grid edge, matching ES-DE's "switch system at the
                        // edge" convention.
                        //
                        // Up at the TOP row does not answer true either: it
                        // leaves like Left/Right at an edge, so Compose's own
                        // focus search reaches the rows above this grid: the
                        // filter chips first, and in safe mode the banner's
                        // action behind them (docs/SPEC.md 10c). Destinations
                        // are chosen in the left menu, never through focus
                        // traversal.
                        modifier = Modifier.fillMaxSize().padding(horizontal = LocalShellWindow.current.edgePadding)
                            .onPad { press ->
                                val direction = gridDirection(press.action) ?: return@onPad false
                                // One card per step (GridPad); at an edge
                                // it answers false and Left/Right reach the
                                // switch-system handler above.
                                if (pad.canMove(direction)) {
                                    pad.move(direction)
                                    true
                                } else {
                                    false
                                }
                            },
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        gridItemsIndexed(games, key = { _, entry -> entry.id }) { index, entry ->
                            val cardBounds = rememberAnchor()
                            GameCard(
                                entry = entry,
                                modifier = Modifier
                                    .anchorFor(cardBounds)
                                    .focusRequester(pad.requester(index))
                                    .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                                // A opens the details, where Play is (Droidtop/tracker#641).
                                onActivate = { onShowDetail(entry) },
                                onShowDetail = { onShowDetail(entry) },
                                onOptions = {
                                    optionsAnchor = cardBounds.value
                                    optionsFor = entry
                                },
                                onFocused = {
                                    pad.focused = index
                                    onFocusedEntryChanged(entry)
                                },
                                onToggleFavorite = { onToggleFavorite(entry) },
                            )
                        }
                    }
                }
            }
        }

    }
    optionsFor?.let { entry ->
        AppOptionsMenu(
            entry = entry,
            detailsLabel = "Game details",
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
            onDismiss = { optionsFor = null },
            anchor = optionsAnchor,
        )
    }
}



/** What the Game folders page promises while it is drawn in place of an empty library's Games tab. */
internal val EMPTY_FOLDERS_HINTS = listOf(
    HintBinding(GamepadAction.A, "Select"),
    HintBinding(GamepadAction.B, "Back"),
)

/**
 * One gamelist's own state, held together (Droidtop/tracker#763): the sort and filter it shows ([query], stored per
 * list by PersistQuery once [queryLoaded]), whether its Sort or Filter sheet is open, and the text its Select menu's
 * Search narrows it by. GamesSection remembers one per group.
 */
@androidx.compose.runtime.Stable
internal class GamelistState(default: LibraryQuery) {
    var query by mutableStateOf(default)
    var queryLoaded by mutableStateOf(false)
    var sortOpen by mutableStateOf(false)
    var filterOpen by mutableStateOf(false)
    var searchText by mutableStateOf("")
}
