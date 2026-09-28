package dev.droidtop.shell.gamepad.pc

import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.PcRunnerOptions
import dev.droidtop.library.ResolvedRunner
import dev.droidtop.shell.gamepad.GameCard
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import dev.droidtop.shell.gamepad.input.handleGamepadKeyDown
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.query.INSTALLED_YES
import dev.droidtop.shell.gamepad.query.LibraryFacet
import dev.droidtop.shell.gamepad.query.LibraryFilterDialog
import dev.droidtop.shell.gamepad.query.LibraryQuery
import dev.droidtop.shell.gamepad.query.LibraryQueryChips
import dev.droidtop.shell.gamepad.query.LibraryQueryScope
import dev.droidtop.shell.gamepad.query.LibrarySearchDialog
import dev.droidtop.shell.gamepad.query.LibrarySortKey
import dev.droidtop.shell.gamepad.query.LibraryViewPrefs
import dev.droidtop.shell.gamepad.query.NamedLibraryView
import dev.droidtop.shell.gamepad.query.RECENT_YES
import dev.droidtop.shell.gamepad.rememberGridPad
import dev.droidtop.shell.gamepad.requestFocusWhenAttached
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The PC group's own content, drawn over the active theme's FRAME ONLY
 * (docs/SPEC.md 7i, redecided again 2026-09-28: "take the GENERAL menu
 * layout from the selected theme, but fill the rest in -- a blanket list
 * like we currently have for ArtBookNext is a terrible idea"). Where the
 * 2026-09-26 revision handed PC games to the theme's own gamelist widget
 * (the SAME carousel/grid/textlist a console ROM's list uses) plus two
 * overlay strips, this one keeps only what [dev.droidtop.shell.gamepad.
 * theme.EsDeThemedView]'s `frameOnly` render draws -- background, colour,
 * font, header/logo, help area, proportions -- and fills the content area
 * itself with droidtop's own cover-art grid and a focused-game panel,
 * because PC's own facts (a resolved runner, a store, ProtonDB, install
 * state) are not ES-DE metadata a theme's own elements could ever bind to
 * -- the same reasoning that already gave [PcGameMenu] its L2 slot.
 *
 * Filter, sort and search are the ONE shared model
 * ([dev.droidtop.shell.gamepad.query.LibraryQuery]) every library list can
 * use, as clearable chips; "Continue playing" and "Installed" are its own
 * built-in [NamedLibraryView]s, not a second section mechanism.
 *
 * No per-game disk or network work happens while this renders: every
 * entry's grid card reads only in-memory [LibraryEntry] fields, and the
 * one IO lookup (the focused game's resolved runner) runs for the single
 * focused entry, off the main thread, the same cost the retired
 * PcExpandedOverlay always paid.
 */
@Composable
internal fun PcLibraryContent(
    entries: List<LibraryEntry>,
    focused: LibraryEntry?,
    onFocusEntry: (LibraryEntry) -> Unit,
    onLaunch: (LibraryEntry) -> Unit,
    onToggleFavorite: (LibraryEntry) -> Unit,
    // Y and long-press (GameCard's own onShowDetail) open the same
    // in-context "Game options" menu L2 does (docs/SPEC.md 7i): a player
    // who has not learned the L2 convention still finds it, the same as
    // every other GameCard-based grid's Y opens ITS detail screen.
    onOpenMenu: (LibraryEntry) -> Unit,
    firstFocus: FocusRequester,
    plateColor: Color?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = remember {
        LibraryQueryScope(
            id = "pc",
            // RUNNER/READY/PROTONDB are left off this scope's facets: they
            // cost a folder walk or a network ask per entry, and this list
            // is drawn for every game in the group at once -- exactly the
            // per-item cost the shell never pays while rendering. A facet
            // with nothing behind it already offers nothing (LibraryFacet.
            // valuesIn's own doc comment); leaving these out here says the
            // same thing at the source instead of relying on that fallback.
            facets = listOf(
                LibraryFacet.STORE,
                LibraryFacet.ENGINE,
                LibraryFacet.INSTALLED,
                LibraryFacet.FAVOURITES,
                LibraryFacet.PLAYED,
                LibraryFacet.RECENTLY_PLAYED,
                LibraryFacet.GENRE,
                LibraryFacet.DEVELOPER,
                LibraryFacet.YEAR,
                LibraryFacet.UPDATE,
                LibraryFacet.MISSING_ART,
                LibraryFacet.HIDDEN,
            ),
            sorts = listOf(
                LibrarySortKey.NAME,
                LibrarySortKey.RECENT,
                LibrarySortKey.PLAYTIME,
                LibrarySortKey.YEAR,
                LibrarySortKey.RATING,
                LibrarySortKey.SIZE,
            ),
        )
    }
    var query by remember { mutableStateOf(LibraryViewPrefs.activeQuery(context, scope.id)) }
    LaunchedEffect(query) { LibraryViewPrefs.setActiveQuery(context, scope.id, query) }
    var savedViews by remember { mutableStateOf(LibraryViewPrefs.savedViews(context, scope.id)) }
    // The PC library's own built-in sections (owner direction: "sections --
    // Continue playing, Installed"), as views like any other -- the same
    // mechanism a person's own saved view is, never a second one.
    val builtInViews = remember {
        listOf(
            NamedLibraryView("All games", LibraryQuery()),
            NamedLibraryView(
                "Continue playing",
                LibraryQuery(facets = mapOf(LibraryFacet.RECENTLY_PLAYED.key to setOf(RECENT_YES)), sort = LibrarySortKey.RECENT),
            ),
            NamedLibraryView("Installed", LibraryQuery(facets = mapOf(LibraryFacet.INSTALLED.key to setOf(INSTALLED_YES)))),
        )
    }
    var filterOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    val filtered = remember(entries, query, scope) { query.applyTo(entries, scope) }
    val chipFocus = remember { FocusRequester() }

    Column(modifier = modifier.fillMaxSize()) {
        LibraryQueryChips(
            scope = scope,
            base = entries,
            query = query,
            onQueryChange = { query = it },
            views = builtInViews + savedViews,
            onActivateView = { query = it.query },
            onOpenFilters = { filterOpen = true },
            onOpenSearch = { searchOpen = true },
            firstChipFocus = chipFocus,
            modifier = Modifier
                .background(MenuTokens.Scrim, RoundedCornerShape(10.dp))
                .padding(horizontal = LocalShellWindow.current.edgePadding, vertical = 8.dp),
        )
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(modifier = Modifier.weight(0.62f).fillMaxHeight()) {
                if (filtered.isEmpty()) {
                    Text(
                        if (entries.isEmpty()) "No PC or engine games yet" else "No games match this filter",
                        color = MenuTokens.OnSurfaceMuted,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    val pad = rememberGridPad()
                    LaunchedEffect(filtered) { requestFocusWhenAttached(firstFocus, "PC library grid") }
                    LazyVerticalGrid(
                        state = pad.state,
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        contentPadding = PaddingValues(
                            start = LocalShellWindow.current.edgePadding,
                            end = 8.dp,
                            bottom = MenuTokens.HintBarRoom,
                        ),
                        // Same real GridPad contract as the Games section's
                        // own unthemed grid: the UP key edge moves one card,
                        // Left/Right bubble to the sibling-system switcher
                        // at the grid's own edge (docs/SPEC.md 7j/7k -- never
                        // the other way), and Up at the top row is answered
                        // true and left there rather than escaping onto the
                        // chip row, which has no D-pad route of its own
                        // (reached by touch only, same as every other
                        // droidtop-drawn chip row in this shell).
                        modifier = Modifier.fillMaxSize()
                            .onKeyEvent { event ->
                                val direction = when (GamepadKeyMap.actionFor(event.key)) {
                                    GamepadAction.UP -> FocusDirection.Up
                                    GamepadAction.DOWN -> FocusDirection.Down
                                    GamepadAction.LEFT -> FocusDirection.Left
                                    GamepadAction.RIGHT -> FocusDirection.Right
                                    else -> null
                                } ?: return@onKeyEvent false
                                // DOWN edge moves, repeats included
                                // (Droidtop/tracker#1); canMove is pure
                                // (GridPad's own doc comment) so the UP
                                // edge answers the same true/false without
                                // moving a second card.
                                handleGamepadKeyDown(event.type == KeyEventType.KeyDown, event.type == KeyEventType.KeyUp, pad.canMove(direction) || direction == FocusDirection.Up) {
                                    pad.move(direction)
                                }
                            },
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        gridItemsIndexed(filtered, key = { _, entry -> entry.id }) { index, entry ->
                            GameCard(
                                entry = entry,
                                modifier = Modifier
                                    .focusRequester(pad.requester(index))
                                    .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                                onLaunch = { onLaunch(entry) },
                                onShowDetail = { onOpenMenu(entry) },
                                onFocused = {
                                    pad.focused = index
                                    onFocusEntry(entry)
                                },
                                onToggleFavorite = { onToggleFavorite(entry) },
                                plateColor = plateColor,
                            )
                        }
                    }
                }
            }
            FocusedGamePanel(
                entry = focused,
                plateColor = plateColor,
                modifier = Modifier.weight(0.38f).fillMaxHeight()
                    .padding(start = 12.dp, end = LocalShellWindow.current.edgePadding, top = 4.dp, bottom = MenuTokens.HintBarRoom),
            )
        }
    }
    if (filterOpen) {
        LibraryFilterDialog(
            scope = scope,
            base = entries,
            query = query,
            savedViews = savedViews,
            onQueryChange = { query = it },
            onSaveView = { name ->
                LibraryViewPrefs.saveView(context, scope.id, NamedLibraryView(name, query))
                savedViews = LibraryViewPrefs.savedViews(context, scope.id)
            },
            onForgetView = { name ->
                LibraryViewPrefs.removeView(context, scope.id, name)
                savedViews = LibraryViewPrefs.savedViews(context, scope.id)
            },
            onDismiss = { filterOpen = false },
        )
    }
    if (searchOpen) {
        LibrarySearchDialog(
            query = query,
            matchCount = filtered.size,
            totalCount = entries.size,
            onTextChange = { query = query.copy(text = it) },
            onDismiss = { searchOpen = false },
        )
    }
}

/**
 * The focused game's own facts the theme's element schema has no slot
 * for and this pass's frame-only render therefore drops from the theme's
 * canvas entirely: hero art, description, playtime, the resolved runner,
 * the source/store, update state and ProtonDB. Read-only -- every real
 * action on this game (the runner picker, Wine settings, the Lutris
 * import, ProtonDB's own live ask) stays on [PcGameMenu] (L2/Y), which
 * this panel names as where to find them rather than duplicating them.
 */
@Composable
private fun FocusedGamePanel(entry: LibraryEntry?, plateColor: Color?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        if (entry == null) {
            Text("Select a game", color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
            return
        }
        val art = entry.heroUri ?: entry.artworkUri
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .background(plateColor ?: MenuTokens.Surface, RoundedCornerShape(10.dp)),
        ) {
            if (art != null) {
                AsyncImage(model = art, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    dev.droidtop.library.GameNaming.displayName(entry.title),
                    color = MenuTokens.OnSurface,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            }
        }
        Text(
            dev.droidtop.library.GameNaming.displayName(entry.title),
            color = MenuTokens.OnSurface,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp),
        )
        entry.description?.takeIf { it.isNotBlank() }?.let { desc ->
            Text(
                desc,
                color = MenuTokens.OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        val context = LocalContext.current
        val runner by produceState<ResolvedRunner?>(null, entry.id) {
            value = null
            value = withContext(Dispatchers.IO) {
                val runners = PcRunnerOptions.forEntry(context, entry)
                PcRunnerOptions.resolvedFor(context, entry, runners)
            }
        }
        PanelFact("Runner", runner?.let { "${it.label} — ${it.reason}" } ?: "Working out what can run this…")
        PanelFact("Source", entry.sourceLabel())
        if (entry.playtimeSeconds > 0) PanelFact("Played", "${entry.playtimeSeconds / 60} min")
        entry.availableUpdate?.let { PanelFact("Update", "$it available") }
        // ProtonDB is asked for, never fetched automatically (docs/SPEC.md
        // 7i: "compatibility is evidence, never a verdict and never a
        // gate" -- looked up only when the person asks, on PcGameMenu).
        if (entry.pcInfo?.source == "Steam") PanelFact("ProtonDB", "See Game options (L2)")
    }
}

@Composable
private fun PanelFact(label: String, value: String) {
    Row(modifier = Modifier.padding(top = 6.dp)) {
        Text(label, color = MenuTokens.SectionLabel, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 6.dp))
        Text(value, color = MenuTokens.Value, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
