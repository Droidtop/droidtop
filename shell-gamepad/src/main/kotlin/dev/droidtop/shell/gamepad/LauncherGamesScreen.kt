package dev.droidtop.shell.gamepad

import androidx.compose.ui.res.stringResource
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.onPad
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.droidtop.shell.gamepad.query.LAUNCHER_GAMES_SCOPE_ID
import dev.droidtop.shell.gamepad.query.LibraryFilterSheet
import dev.droidtop.shell.gamepad.query.LibraryQuery
import dev.droidtop.shell.gamepad.query.LibrarySortSheet
import dev.droidtop.shell.gamepad.query.PersistQuery
import dev.droidtop.shell.gamepad.query.launcherGamesQueryScope
import dev.droidtop.shell.gamepad.query.pillText
import dev.droidtop.shell.gamepad.query.queryCountLine
import dev.droidtop.shell.gamepad.query.rememberSavedViews
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.scraper.isPcOrEngineGame
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow
import dev.droidtop.shell.gamepad.input.ownPadButtons

/**
 * Settings' "Game folders" screen, by [SettingsScreenRegistry] id: :app
 * registers it at process start, and this module cannot depend on :app.
 */
internal const val GAME_FOLDERS_SCREEN_ID = "rom_folders"

/**
 * Launcher mode's Games grid (docs/SPEC.md 2c, "Games in the Launcher"),
 * drawn as droidtop's own chrome rather than a stock Material list: the
 * user did not recognise the first version as one of droidtop's screens
 * (rig, build 814). It is the shell's pieces, not a copy of them: the
 * Games section's own card ([GameCard], with its accent ring over a
 * raised fill), the black ground, the screen header, and a [TouchHintBar]
 * that names every action and dispatches it.
 *
 * What it does: A plays, X filters and Y sorts over the one query model
 * every list uses; Select's Options pins the game to the home screen and
 * opens Game folders in place (the settings screen that fills an empty
 * grid, rendered by the same navigator the shell's settings use); B leaves.
 * A PC or engine game is Gaming's PC game wired out (Droidtop/tracker#349):
 * A follows the same primary-action rule as the PC Games tab (a store game
 * that is not installed offers the install), and a long press or Options >
 * Game page opens the same page and menu ([PcGameStandalone]). A long press on any game opens its options:
 * Game page for a PC game, Favourite, Hide, Pin to home screen (the gap review, 2026-10-17: a long press used to
 * pin a console game outright, with no way to favourite or hide one here). There are no themes or Quick Menu here.
 *
 * [games] null is "not read yet", which says so rather than "no games".
 */
@Composable
fun LauncherGamesScreen(
    games: List<LibraryEntry>?,
    onPlay: (LibraryEntry) -> Unit,
    onPin: (LibraryEntry) -> Unit,
    /** The library, for a PC game's page and menu; null leaves PC games with Play and Pin only. */
    library: dev.droidtop.library.Library? = null,
    /** The Downloads place, where a store install runs (the page's and the install offer's link). */
    onOpenDownloads: () -> Unit = {},
    /** A game whose page opens at once (a widget's tap on a store game that is not installed: Install is there). */
    openPageId: String? = null,
) {
    val shellWindow = currentShellWindow()
    CompositionLocalProvider(LocalShellWindow provides shellWindow) {
        val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        val foldersScreen = remember { SettingsScreenRegistry.get(GAME_FOLDERS_SCREEN_ID) }
        var foldersOpen by rememberSaveable { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .groundBackground()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                // The shell owns the pad here as everywhere: B is the back
                // dispatcher, which leaves this screen or closes Game folders.
                .ownPadButtons { backDispatcher?.onBackPressed() },
        ) {
            if (foldersOpen && foldersScreen != null) {
                BackHandler { foldersOpen = false }
                // Its own hint row, as every screen has one: the settings
                // navigator draws none, and inside the Gaming shell that
                // row is the shell's (rig, dq-shell2-01: no hint row here).
                // All three dispatch inside the navigator: A activates the
                // row, Y is its Info sheet, B pops one level.
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f)) {
                        CatalogNavigator(root = foldersScreen, onExit = { foldersOpen = false })
                    }
                    HintRow(
                        bindings = listOf(
                            HintBinding(GamepadAction.A, "Select"),
                            HintBinding(GamepadAction.Y, "Info"),
                            HintBinding(GamepadAction.B, "Back"),
                        ),
                    )
                }
            } else {
                GamesGrid(
                    games = games,
                    onPlay = onPlay,
                    onPin = onPin,
                    onOpenFolders = if (foldersScreen != null) ({ foldersOpen = true }) else null,
                    library = library,
                    onOpenDownloads = onOpenDownloads,
                    openPageId = openPageId,
                )
            }
        }
    }
}

@Composable
private fun GamesGrid(
    games: List<LibraryEntry>?,
    onPlay: (LibraryEntry) -> Unit,
    onPin: (LibraryEntry) -> Unit,
    onOpenFolders: (() -> Unit)?,
    library: dev.droidtop.library.Library?,
    onOpenDownloads: () -> Unit,
    openPageId: String?,
) {
    val window = LocalShellWindow.current
    val pad = rememberGridPad()
    // A PC or engine game takes the PC Games tab's primary action and has its page (Droidtop/tracker#349).
    fun hasPage(entry: LibraryEntry) = library != null && entry.isPcOrEngineGame
    var pageId by remember(openPageId) { mutableStateOf(openPageId?.takeIf { library != null }) }
    val pcLaunch = dev.droidtop.shell.gamepad.pc.rememberPcLaunch(onLaunch = onPlay, onOpenDownloads = onOpenDownloads)
    val play: (LibraryEntry) -> Unit = { entry -> if (hasPage(entry)) pcLaunch.launch(entry) else onPlay(entry) }
    val emptyAction = remember { FocusRequester() }
    // The one query model the Gaming lists use (docs/SPEC.md 7j): this view's filters and sort are
    // remembered, and applied off the main thread.
    val scope = remember { launcherGamesQueryScope() }
    var query by remember { mutableStateOf(LibraryQuery()) }
    var queryLoaded by remember { mutableStateOf(false) }
    PersistQuery(LAUNCHER_GAMES_SCOPE_ID, query, queryLoaded) {
        query = it
        queryLoaded = true
    }
    val savedViews = rememberSavedViews(LAUNCHER_GAMES_SCOPE_ID)
    // A favourite or hide made here shows at once; the library's next read carries it from then on.
    var favouriteNow by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    var hiddenNow by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    val withChanges = remember(games, favouriteNow, hiddenNow) {
        games?.map { e ->
            val favourite = favouriteNow[e.id]
            val hidden = hiddenNow[e.id]
            if (favourite == null && hidden == null) e else e.copy(favorite = favourite ?: e.favorite, hidden = hidden ?: e.hidden)
        }
    }
    val shown by produceState<List<LibraryEntry>?>(null, withChanges, query, scope) {
        value = withChanges?.let { withContext(Dispatchers.Default) { query.applyTo(it, scope) } }
    }
    var filterOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var optionsOpen by remember { mutableStateOf(false) }
    // The game the options are for: the focused one for Select, the pressed one for a long press.
    var optionsGame by remember { mutableStateOf<LibraryEntry?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val actions = androidx.compose.runtime.rememberCoroutineScope()
    val list = shown.takeIf { queryLoaded }
    val total = remember(games, query, scope) { games?.let { query.totalIn(it, scope) } ?: 0 }
    Column(
        modifier = Modifier
            .fillMaxSize()
            // One handler for the screen, above the cards: each step of a
            // direction moves one card (GridPad), and Select opens Game
            // folders (docs/SPEC.md 6e).
            .onPad { press ->
                val direction = gridDirection(press.action)
                when {
                    direction != null -> {
                        pad.move(direction)
                        true
                    }
                    press.action == GamepadAction.X && games != null -> {
                        filterOpen = true
                        true
                    }
                    press.action == GamepadAction.Y && games != null -> {
                        sortOpen = true
                        true
                    }
                    press.action == GamepadAction.SELECT && (onOpenFolders != null || !list.isNullOrEmpty()) -> {
                        optionsGame = list?.getOrNull(pad.focused)
                        optionsOpen = true
                        true
                    }
                    else -> false
                }
            },
    ) {
        MenuHeader(
            title = "Games",
            subtitle = list?.let { queryCountLine(it.size, total, !query.isEmpty, scope) },
        )
        // The one pill the other lists draw while something filters, cleared by one press.
        list?.let { query.pillText(scope, it.size, total) }?.let { pill ->
            Row(modifier = Modifier.padding(horizontal = window.edgePadding, vertical = Space.Sm)) {
                ShellChip(pill, on = true, onClick = { query = query.cleared })
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            when {
                games == null || list == null -> EmptyLine("Reading the library…")
                games.isNotEmpty() && list.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(window.edgePadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Space.Lg),
                    ) {
                        Text(stringResource(R.string.no_games_match_filters), color = MenuTokens.OnSurfaceMuted, style = TypeRole.body)
                        ShellChip(
                            "Clear filters",
                            primary = true,
                            modifier = Modifier.focusRequester(emptyAction),
                            onClick = { query = query.cleared },
                        )
                        LaunchedEffect(Unit) { requestFocusWhenAttached(emptyAction, "Launcher games filtered empty") }
                    }
                }
                games.isEmpty() -> {
                    // An empty grid offers the one thing that fills it.
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(window.edgePadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Space.Lg),
                    ) {
                        Text(stringResource(R.string.no_games_yet), color = MenuTokens.OnSurfaceMuted, style = TypeRole.body)
                        if (onOpenFolders != null) {
                            ShellChip(
                                "Add a games folder",
                                primary = true,
                                modifier = Modifier.focusRequester(emptyAction),
                                onClick = onOpenFolders,
                            )
                            LaunchedEffect(Unit) { requestFocusWhenAttached(emptyAction, "Launcher games empty") }
                        }
                        GetGamesChip()
                    }
                }
                else -> {
                    LaunchedEffect(Unit) { requestFocusWhenAttached(pad.requester(0), "Launcher games") }
                    LazyVerticalGrid(
                        state = pad.state,
                        columns = GridCells.Adaptive(minSize = window.gridItemMinWidth),
                        modifier = Modifier.fillMaxSize().padding(horizontal = window.edgePadding),
                        horizontalArrangement = Arrangement.spacedBy(Space.Xl),
                        verticalArrangement = Arrangement.spacedBy(Space.Xl),
                        contentPadding = PaddingValues(top = Space.Sm, bottom = Space.Xl),
                    ) {
                        itemsIndexed(list, key = { _, entry -> entry.id }) { index, entry ->
                            GameCard(
                                entry = entry,
                                modifier = Modifier.focusRequester(pad.requester(index)),
                                onActivate = { play(entry) },
                                onFocused = { pad.focused = index },
                                // A long press: the shell's "act on this one", the game's options.
                                onShowDetail = {
                                    pad.focused = index
                                    optionsGame = entry
                                    optionsOpen = true
                                },
                            )
                        }
                    }
                }
            }
        }
        // The same gated row the shell's own footer uses: only what
        // dispatches right now is named -- A needs a card to act on, X and
        // Y need a list, Select needs something to offer, B always leaves.
        HintRow(
            bindings = listOf(
                HintBinding(GamepadAction.A, "Play") { !list.isNullOrEmpty() },
                HintBinding(GamepadAction.X, "Filter") { games != null },
                HintBinding(GamepadAction.Y, "Sort By") { games != null },
                HintBinding(GamepadAction.SELECT, "Options") { onOpenFolders != null || !list.isNullOrEmpty() },
                HintBinding(GamepadAction.B, "Back"),
            ),
        )
    }
    if (filterOpen && games != null) {
        LibraryFilterSheet(
            scope = scope,
            base = games,
            query = query,
            savedViews = savedViews.views,
            onQueryChange = { query = it },
            onSaveView = { savedViews.save(it, query) },
            onForgetView = { savedViews.forget(it) },
            onSearch = null,
            onDismiss = { filterOpen = false },
        )
    }
    if (sortOpen) {
        LibrarySortSheet(scope = scope, query = query, onQueryChange = { query = it }, onDismiss = { sortOpen = false })
    }
    // The PC Games tab's page, menu and install offer, wired out (Droidtop/tracker#349).
    val pageLibrary = library
    val openPage = pageId
    if (pageLibrary != null && openPage != null) {
        dev.droidtop.shell.gamepad.pc.PcGameStandalone(
            library = pageLibrary,
            entryId = openPage,
            onLaunch = onPlay,
            onOpenDownloads = onOpenDownloads,
            onClose = { pageId = null },
        )
    }
    dev.droidtop.shell.gamepad.pc.PcLaunchOfferSheet(pcLaunch)
    if (optionsOpen) {
        val focused = optionsGame?.let { picked -> list?.firstOrNull { it.id == picked.id } ?: picked }
        LauncherGamesOptions(
            game = focused,
            onPin = onPin,
            onFavourite = library?.let { lib ->
                { game: LibraryEntry ->
                    actions.launch {
                        lib.toggleFavorite(game)?.let { now ->
                            favouriteNow = favouriteNow + (game.id to now)
                            dev.droidtop.library.settings.Notices.flash(
                                if (now) "${game.title} is a favourite" else "${game.title} is no longer a favourite",
                                context = context,
                            )
                        }
                    }
                }
            },
            onHide = { game ->
                val now = !game.hidden
                hiddenNow = hiddenNow + (game.id to now)
                actions.launch {
                    withContext(Dispatchers.IO) { dev.droidtop.library.GameHiding.set(context, game.id, now) }
                    dev.droidtop.library.settings.Notices.flash(if (now) "${game.title} hidden" else "${game.title} shown", context = context)
                }
            },
            onOpenPage = focused?.takeIf { hasPage(it) }?.let { game -> { pageId = game.id } },
            onOpenFolders = onOpenFolders,
            onDismiss = { optionsOpen = false },
        )
    }
}

/**
 * Select or a long press on the Launcher's Games list: the game's own rows (its page, favourite, hide, pin it to the
 * home screen) and
 * Game folders, in the one menu shape the Apps view's options use. Controller-first: Up/Down moves,
 * A chooses, B or Select closes, and every row is a touch target.
 */
@Composable
private fun LauncherGamesOptions(
    game: LibraryEntry?,
    onPin: (LibraryEntry) -> Unit,
    onFavourite: ((LibraryEntry) -> Unit)?,
    onHide: (LibraryEntry) -> Unit,
    onOpenPage: (() -> Unit)?,
    onOpenFolders: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    class Option(val title: String, val onClick: () -> Unit)

    val options = remember(game, onOpenFolders, onOpenPage) {
        buildList<Option> {
            if (onOpenPage != null) add(Option("Game page") { onDismiss(); onOpenPage() })
            if (game != null && onFavourite != null) {
                add(Option(if (game.favorite) "Remove from favourites" else "Add to favourites") { onDismiss(); onFavourite(game) })
            }
            if (game != null) add(Option(if (game.hidden) "Show again" else "Hide") { onDismiss(); onHide(game) })
            if (game != null) add(Option("Pin to home screen") { onDismiss(); onPin(game) })
            if (onOpenFolders != null) add(Option("Game folders") { onDismiss(); onOpenFolders() })
        }
    }
    var focusIndex by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss) {
        GatePadInThisDialog()
        MenuPanel(
            modifier = Modifier.width(LocalShellWindow.current.panelWidth(480.dp)),
            focusLabel = "Options",
            hints = listOf(HintBinding(GamepadAction.A, "Choose"), HintBinding(GamepadAction.B, "Close")),
            onPad = { press ->
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> focusIndex = menuMove(focusIndex, options.size, press)
                    GamepadAction.A -> options.getOrNull(focusIndex)?.onClick?.invoke()
                    GamepadAction.B, GamepadAction.SELECT -> onDismiss()
                    else -> Unit
                }
                true
            },
        ) {
            if (game != null) {
                Text(game.title, style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = MenuTokens.OnSurface, maxLines = 1)
            }
            options.forEachIndexed { index, option ->
                MenuRow(title = option.title, subtitle = null, selected = index == focusIndex, onClick = option.onClick)
            }
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Box(Modifier.fillMaxSize().padding(Space.Xl), contentAlignment = Alignment.Center) {
        Text(text, color = MenuTokens.OnSurfaceMuted, style = TypeRole.body)
    }
}
