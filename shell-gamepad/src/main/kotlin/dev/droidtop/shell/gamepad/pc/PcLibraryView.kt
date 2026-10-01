package dev.droidtop.shell.gamepad.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.focus.focusProperties
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
import dev.droidtop.library.GameNaming
import dev.droidtop.library.LibraryEntry
import dev.droidtop.shell.gamepad.ShellChip
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
import dev.droidtop.shell.gamepad.query.LibraryQueryScope
import dev.droidtop.shell.gamepad.query.LibrarySearchDialog
import dev.droidtop.shell.gamepad.query.LibrarySortKey
import dev.droidtop.shell.gamepad.query.LibraryViewPrefs
import dev.droidtop.shell.gamepad.query.NamedLibraryView
import dev.droidtop.shell.gamepad.query.RECENT_YES
import dev.droidtop.shell.gamepad.rememberGridPad
import dev.droidtop.shell.gamepad.requestFocusWhenAttached

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
    // Y and long-press (GameCard's own onShowDetail) open the game's own
    // page ([PcGamePage], docs/SPEC.md 7i), the same as every other
    // GameCard-based grid's Y opens ITS detail screen. L2 (the short
    // [PcGameMenu]) is bound at the shell, not here.
    onOpenPage: (LibraryEntry) -> Unit,
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
    val pad = rememberGridPad()

    Column(modifier = modifier.fillMaxSize()) {
        // ONE button in front of the grid (docs/SPEC.md 7i): the current
        // shelf, sort and search in words, and A opens the one filter
        // dialog. A single target needs no Left/Right of its own (those
        // keep switching the system), the dialog is already a D-pad menu,
        // and Up from it is cancelled so the tab bar is never reached.
        Row(
            modifier = Modifier
                .background(MenuTokens.Scrim, RoundedCornerShape(10.dp))
                .padding(horizontal = LocalShellWindow.current.edgePadding, vertical = 8.dp),
        ) {
            ShellChip(
                browseLabel(query, builtInViews + savedViews, filtered.size),
                modifier = Modifier.focusRequester(chipFocus).focusProperties { up = FocusRequester.Cancel },
                onClick = { filterOpen = true },
            )
        }
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(modifier = Modifier.weight(0.62f).fillMaxHeight()) {
                if (filtered.isEmpty()) {
                    Text(
                        if (entries.isEmpty()) "No PC or engine games yet" else "No games match this filter",
                        color = MenuTokens.OnSurfaceMuted,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    // Focus lands on the grid when it first appears, never on
                    // a filter change: the Browse button is a D-pad target.
                    LaunchedEffect(Unit) { requestFocusWhenAttached(firstFocus, "PC library grid") }
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
                        // the other way). Up at the top row is NOT answered
                        // here: it falls through to Compose's own focus
                        // search, which the focusProperties below point at
                        // the Browse button just above the grid.
                        modifier = Modifier.fillMaxSize()
                            .focusProperties { if (pad.onTopRow) up = chipFocus }
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
                                handleGamepadKeyDown(event.type == KeyEventType.KeyDown, event.type == KeyEventType.KeyUp, pad.canMove(direction)) {
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
                                onShowDetail = { onOpenPage(entry) },
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
                onLaunch = onLaunch,
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
            savedViews = builtInViews + savedViews,
            onQueryChange = { query = it },
            onSearch = {
                filterOpen = false
                searchOpen = true
            },
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
        // Recommendations for the empty search field: worked out off the
        // main thread, only while the dialog is open (a linear pass over
        // the group, docs/SPEC.md 12a "Recommendations").
        val suggestions by produceState(emptyList<dev.droidtop.library.integrations.Recommendation>(), entries) {
            value = dev.droidtop.library.integrations.LocalSimilarityRecommendations({ entries })
                .recommend(context, dev.droidtop.library.integrations.RecommendationScope.Overall, 5)
        }
        LibrarySearchDialog(
            query = query,
            matchCount = filtered.size,
            totalCount = entries.size,
            onTextChange = { query = query.copy(text = it) },
            onDismiss = { searchOpen = false },
            suggestions = suggestions,
        )
    }
}

/**
 * The focused game, Steam-style: its art, its name, the one big button A
 * will press (Play, or the setup step that makes it Play,
 * [PcPlayState]), and a few facts. Read-only and short enough to never
 * overflow -- the full description, the about facts, where each came
 * from and the game's own actions are on its page ([PcGamePage], Y) and
 * in [PcGameMenu] (L2); nothing here is left for a finger to scroll.
 * The button is a touch target too: a tap on it is A.
 */
@Composable
private fun FocusedGamePanel(
    entry: LibraryEntry?,
    plateColor: Color?,
    onLaunch: (LibraryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    GameNaming.displayName(entry.title),
                    color = MenuTokens.OnSurface,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            }
        }
        Text(
            GameNaming.displayName(entry.title),
            color = MenuTokens.OnSurface,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        val (play, runner) = rememberPcPlayState(entry)
        val shape = RoundedCornerShape(12.dp)
        Column(
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth()
                .focusProperties { canFocus = false }
                .clickable(enabled = play.pressable) { onLaunch(entry) }
                .background(if (play.pressable) MenuTokens.Launch else MenuTokens.LaunchDisabled, shape)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                play.verb,
                color = if (play.pressable) MenuTokens.OnSurface else MenuTokens.OnSurfaceDisabled,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (play.detail.isNotBlank()) {
                Text(
                    play.detail,
                    color = if (play.pressable) MenuTokens.OnLaunchMuted else MenuTokens.OnSurfaceDisabled,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!entry.hideMetadata) {
            entry.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Text(
                    desc,
                    color = MenuTokens.OnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
        PanelFact("Runs with", runner?.let { "${it.label} - ${it.reason}" }.orEmpty())
        PanelFact("Source", entry.sourceLabel())
        if (entry.playtimeSeconds > 0) PanelFact("Played", "${entry.playtimeSeconds / 60} min")
        entry.availableUpdate?.let { PanelFact("Update", "$it available") }
    }
}

@Composable
private fun PanelFact(label: String, value: String) {
    if (value.isBlank()) return
    Row(modifier = Modifier.padding(top = 6.dp)) {
        Text(label, color = MenuTokens.SectionLabel, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 6.dp))
        Text(value, color = MenuTokens.Value, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The Browse button's words: the shelf, how many games it shows, the sort, and the search if there is one. */
private fun browseLabel(query: LibraryQuery, views: List<NamedLibraryView>, shown: Int): String = buildList {
    add(views.firstOrNull { it.query == query }?.name ?: "Custom view")
    add(if (shown == 1) "1 game" else "$shown games")
    add("Sort: ${query.sort.label}")
    query.text.takeIf { it.isNotBlank() }?.let { add("\"$it\"") }
}.joinToString(" · ")
