package dev.droidtop.shell.gamepad.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.displayName
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.shell.gamepad.CatalogNavigator
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.HelpRowOwner
import dev.droidtop.shell.gamepad.LocalHelpRowOwner
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.ShellChip
import dev.droidtop.shell.gamepad.TouchHintBar
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** ES-DE's own system id for the PC category -- the card this surface opens from. */
internal const val PC_SYSTEM_ID = "pc"

/**
 * :app's "Stores and folders" settings screen, by [SettingsScreenRegistry]
 * id because this module cannot depend on :app -- the same way
 * `GamingSettingsCatalog` names the console-systems and Windows-games
 * screens it opens.
 */
private const val PC_STORES_SCREEN_ID = "pc_stores"

/**
 * droidtop's own PC surface — the whole of docs/SPEC.md §7i's "Library"
 * view, and the one thing in Gaming mode the ES-DE theme does not draw.
 *
 * **One list of games.** Directed 2026-09-10: "Engine games were ALWAYS
 * going to be under PC. No need for filters and stuff, though they're a
 * nice to have. PC is a list of games, and each game is run according to
 * its configuration." So the grid is every PC and engine game together —
 * Steam, GOG, Epic, Amazon, a folder the user pointed droidtop at, and a
 * detected Ren'Py or RPG Maker game — with source and engine demoted to
 * chips over that one list rather than promoted into separate screens.
 *
 * The filter chips cost nothing extra because every value they filter on
 * is already on the entry (source and install state from `PcInfo`, engine
 * from the entry's kind). Runner state is deliberately NOT a chip: it
 * costs a filesystem walk and a provider query per game, which is fine on
 * one open detail screen and not fine across a whole grid.
 *
 * Full-bleed on purpose. No 420 dp centred column (directed 2026-09-10):
 * the grid spans the screen with the same 48 dp gutters the rest of the
 * shell uses.
 */
@Composable
internal fun PcSurface(
    entries: List<LibraryEntry>,
    onOpen: (LibraryEntry) -> Unit,
    /**
     * The card to land on: the one the user was on when they last left
     * this grid (docs/SPEC.md 7i). Null -- never been here -- lands on the
     * first card, which is what this surface always did.
     */
    focusEntryId: String? = null,
    onFocusedEntryChanged: (LibraryEntry?) -> Unit,
    /**
     * Whether this surface's own options screen ("Stores and folders") is
     * up, and how to open and close it. Held by the shell's back stack
     * rather than here (docs/SPEC.md 7i): it is a LEVEL above the grid,
     * so B out of it has to land on the grid, on the card the user left.
     * While this screen owned the answer itself, the shell's own drill-up
     * took the BACK key first and dropped the user on the carousel with
     * the system reset (rig, build 548).
     */
    optionsOpen: Boolean = false,
    onOpenOptions: () -> Unit = {},
    onCloseOptions: () -> Unit = {},
) {
    // ONE CARD PER GAME (docs/SPEC.md 7m). A game found in three folders
    // -- three weeks of Fetish Locator, two versions of one Godot game --
    // is one entry here, and the folders behind it are reachable from its
    // detail. Filters and sort run over the cards, which is the list the
    // user sees.
    //
    // Grouping derives a name from every folder by regex, and a walk
    // republishes the list every 250 ms, so it runs on the Default
    // dispatcher and composition only reads the answer (docs/SPEC.md 7g,
    // "no per-item work where a list is drawn"). Null is "not worked out
    // yet", which draws nothing rather than a false "No PC games"; a
    // republish keeps the cards already shown until the new ones are ready.
    val grouped by produceState<List<LibraryEntry>?>(initialValue = null, entries) {
        value = withContext(Dispatchers.Default) {
            dev.droidtop.library.LibraryGrouping.group(entries).map { it.displayEntry }
        }
    }
    val cards = grouped.orEmpty()

    var sort by remember { mutableStateOf(PcSort.NAME) }
    var sources by remember { mutableStateOf<Set<String>>(emptySet()) }
    var engines by remember { mutableStateOf<Set<String>>(emptySet()) }
    var installedOnly by remember { mutableStateOf(false) }
    // The card the grid opens on. One requester, moved to whichever card
    // that is, rather than a second one for the restored case.
    val openingCard = remember { FocusRequester() }
    val gridState = rememberLazyGridState()

    // "Stores and folders": sign in to a store, add a games folder, set up
    // Windows games, see the downloads queue. It is :app's own settings
    // catalog screen (7i's first-run repairs and the surface's options
    // menu are the same four actions, so they are the same rows), rendered
    // right here by the navigator the shell's settings already use rather
    // than sending anybody to another screen. Registered at process start
    // by :app, which this module cannot depend on, hence the id.
    val storesScreen = remember { SettingsScreenRegistry.get(PC_STORES_SCREEN_ID) }
    // Nothing in the library is exactly the case those four actions fix,
    // so an empty surface opens on them instead of on an empty grid.
    // Once per change of emptiness: backing out of the screen must not
    // re-open it on the next recomposition.
    LaunchedEffect(entries.isEmpty(), storesScreen) {
        if (entries.isEmpty() && storesScreen != null) onOpenOptions()
    }

    val allSources = remember(cards) { cards.map { it.sourceLabel() }.distinct().sorted() }
    val allEngines = remember(cards) { cards.mapNotNull { it.engineLabel() }.distinct().sorted() }

    val shown = remember(cards, sort, sources, engines, installedOnly) {
        cards
            .filter { sources.isEmpty() || it.sourceLabel() in sources }
            .filter { engines.isEmpty() || it.engineLabel() in engines }
            .filter { !installedOnly || it.pcInfo?.installed != false }
            .sortedWith(sort.comparator)
    }

    // Landing on a card is a ONE-SHOT: after this the user owns the focus,
    // and re-running it on every filter change would pull focus out of the
    // chip row the user is standing in.
    var landed by remember { mutableStateOf(false) }
    val openingIndex = remember(shown, focusEntryId) {
        shown.indexOfFirst { it.id == focusEntryId }.takeIf { it >= 0 } ?: 0
    }
    // The options screen is drawn INSTEAD of the grid, by this same
    // composable, so the grid leaves and comes back with `landed` still
    // true from the first visit: it returned at the right scroll with no
    // card under the cursor, and the next A fell through to the shell
    // (rig, build 549). Coming back from it is a fresh landing.
    LaunchedEffect(optionsOpen) {
        if (!optionsOpen) landed = false
    }
    LaunchedEffect(shown.isNotEmpty(), landed) {
        if (shown.isEmpty() || landed) return@LaunchedEffect
        landed = true
        // Scroll first: a card outside the viewport is not composed, so
        // its focus requester is attached to nothing and the request
        // would be dropped. Waiting for the item to actually appear is
        // what makes this work for a card 90 rows down.
        if (openingIndex > 0) runCatching { gridState.scrollToItem(openingIndex) }
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.any { it.index == openingIndex } }
            .first { it }
        runCatching { openingCard.requestFocus() }
    }

    if (optionsOpen && storesScreen != null) {
        CatalogNavigator(root = storesScreen, onExit = onCloseOptions)
        return
    }

    val focusManager = LocalFocusManager.current
    // Back is deliberately NOT handled here: the shell already owns
    // leaving a drilled-in group, on both the key route and Android's
    // back dispatcher, and it plays the theme's own back sound doing it.
    // A second handler would be a second mechanism for one job.
    val window = LocalShellWindow.current
    Column(modifier = Modifier.fillMaxSize()) {
        PcHeader(total = cards.size, shown = shown.size, entries = cards, folders = entries.size)

        // The filter chips outgrow a phone's width long before they
        // outgrow the console's, and a chip that runs off the edge is a
        // filter the user cannot turn off again.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = window.edgePadding, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Sort is one chip that cycles rather than a menu: it is a
            // single-choice setting with three values, and a menu for that
            // is a screen the pad has to walk into and back out of.
            ShellChip("Sort: ${sort.label}", onClick = { sort = sort.next() })
            ShellChip("Installed", on = installedOnly, onClick = { installedOnly = !installedOnly })
            allSources.forEach { source ->
                ShellChip(source, on = source in sources, onClick = { sources = sources.toggle(source) })
            }
            allEngines.forEach { engine ->
                ShellChip(engine, on = engine in engines, onClick = { engines = engines.toggle(engine) })
            }
        }

        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            if (grouped == null && entries.isNotEmpty()) {
                // Still grouping the first list: nothing to say yet.
            } else if (shown.isEmpty()) {
                Text(
                    if (entries.isEmpty()) {
                        "No PC games yet. Press Y for stores and folders."
                    } else {
                        "Nothing matches these filters."
                    },
                    color = MenuTokens.OnSurfaceMuted,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.Center).padding(LocalShellWindow.current.edgePadding),
                )
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = window.gridItemMinWidth),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = window.edgePadding)
                        // Compose moves focus in a grid for nobody: the
                        // same explicit d-pad handling the shell's other
                        // grid already needs, and for the same reason.
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                            when (GamepadKeyMap.actionFor(event.key)) {
                                // The surface's options: where games come
                                // from, on the surface they came into.
                                GamepadAction.Y -> {
                                    if (storesScreen != null) onOpenOptions()
                                    storesScreen != null
                                }
                                GamepadAction.UP -> focusManager.moveFocus(FocusDirection.Up)
                                GamepadAction.DOWN -> focusManager.moveFocus(FocusDirection.Down)
                                GamepadAction.LEFT -> focusManager.moveFocus(FocusDirection.Left)
                                GamepadAction.RIGHT -> focusManager.moveFocus(FocusDirection.Right)
                                else -> false
                            }
                        },
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    // The hint bar's own room at the end of the grid
                    // (MenuTokens.HintBarRoom): the last row of cards
                    // clears the bar instead of ending against it.
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        bottom = dev.droidtop.shell.gamepad.MenuTokens.HintBarRoom,
                    ),
                ) {
                    itemsIndexed(shown, key = { _, entry -> entry.id }) { index, entry ->
                        PcGameCard(
                            entry = entry,
                            modifier = if (index == openingIndex) Modifier.focusRequester(openingCard) else Modifier,
                            onOpen = { onOpen(entry) },
                            onFocused = { onFocusedEntryChanged(entry) },
                        )
                    }
                }
            }
        }

        // The surface draws its own legend rather than borrowing the
        // shell's: A opens a game here instead of launching it, because a
        // PC game's runner may need setup first and the detail screen is
        // where that is said.
        PcHints()
    }
}

/** Plain facts, not a verdict: how much is here and how much of it is on this device. */
@Composable
private fun PcHeader(total: Int, shown: Int, entries: List<LibraryEntry>, folders: Int) {
    val installed = entries.count { it.pcInfo?.installed != false }
    val engineGames = entries.count { it.kind != LibraryEntryKind.WINE_PROFILE }
    val edge = LocalShellWindow.current.edgePadding
    Column(modifier = Modifier.fillMaxWidth().padding(start = edge, end = edge, top = 20.dp, bottom = 4.dp)) {
        Text("PC", color = MenuTokens.OnSurface, style = MaterialTheme.typography.headlineMedium)
        Text(
            // No games from some folders only while the grouping of the
            // first list is still being worked out; a count of zero then
            // would be a false statement for the moment it shows.
            if (total == 0 && folders > 0) "Counting games…" else buildString {
                append(if (shown == total) "$total games" else "$shown of $total games")
                append(", $installed installed")
                if (engineGames > 0) append(", $engineGames with a detected engine")
                // A game found in more than one folder is one card, so
                // the folder count and the game count differ, and a
                // person comparing this with their own folder tree needs
                // to be told which number is which (docs/SPEC.md 7m).
                if (folders > total) append(", in $folders folders")
            },
            color = MenuTokens.OnSurfaceMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * This screen's own help row -- and the ONE row on it: the surface
 * claims the row (`HelpRowClaim.SCREEN`, see docs/SPEC.md 7j) so the
 * shell's own bar stays down and the theme's `<helpsystem>` is
 * suppressed, in both orientations. It is a real [TouchHintBar], so
 * every hint here dispatches its own press; nothing is lost by the
 * shell's bar standing down on a touch screen.
 *
 * Drawn only while this screen actually owns the row, so switching
 * hints off silences this one like every other.
 */
@Composable
private fun PcHints() {
    if (LocalHelpRowOwner.current != HelpRowOwner.SCREEN) return
    TouchHintBar(
        hints = listOf(
            GamepadAction.A to "Open",
            GamepadAction.B to "Back",
            GamepadAction.Y to "Stores and folders",
        ),
    )
}


/**
 * Sorts the one list; never a filter, and never reordered by anything the
 * user did not ask for. No playtime: nothing measures it yet, so it is 0
 * for every game and a sort on it would do nothing (docs/SPEC.md 7g).
 */
internal enum class PcSort(val label: String, val comparator: Comparator<LibraryEntry>) {
    NAME("Name", compareBy<LibraryEntry> { it.title.lowercase() }),
    LAST_PLAYED("Last played", compareByDescending<LibraryEntry> { it.lastPlayedEpochMs ?: 0L }.thenBy { it.title.lowercase() }),
    SIZE("Size", compareByDescending<LibraryEntry> { it.pcInfo?.sizeBytes ?: 0L }.thenBy { it.title.lowercase() }),
    ;

    fun next(): PcSort = PcSort.entries[(ordinal + 1) % PcSort.entries.size]
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

/** Where this game came from. A store row says so itself; anything else is a folder droidtop found. */
internal fun LibraryEntry.sourceLabel(): String = pcInfo?.source ?: "Folder"

/** The detected engine, or null for a PC entry that has none — a Steam game is still a game. */
internal fun LibraryEntry.engineLabel(): String? =
    if (kind == LibraryEntryKind.WINE_PROFILE) null else kind.displayName()
