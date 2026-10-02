package dev.droidtop.shell.gamepad.query

import dev.droidtop.shell.gamepad.getGamesScreen
import dev.droidtop.shell.gamepad.input.onPad
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.HideSystemBarsInThisDialog
import dev.droidtop.shell.gamepad.menuMove
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.droidtop.library.LibraryEntry
import java.io.File
import dev.droidtop.library.integrations.AcquireContentSources
import dev.droidtop.library.integrations.GameSources
import dev.droidtop.library.integrations.GetGamesContext
import dev.droidtop.library.integrations.GetGamesEntry
import dev.droidtop.library.integrations.GetMoreState
import dev.droidtop.library.integrations.PluginGameSource
import dev.droidtop.library.integrations.PluginSearchAggregator
import dev.droidtop.library.integrations.SourceHit
import dev.droidtop.library.integrations.SourceOutcome
import dev.droidtop.library.integrations.UnavailableSource
import dev.droidtop.library.integrations.hits
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.pluginhost.PluginRuntimeNeeds
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.shell.gamepad.CatalogNavigator
import dev.droidtop.shell.gamepad.MenuHint
import dev.droidtop.shell.gamepad.MenuPanel
import dev.droidtop.shell.gamepad.MenuRow
import dev.droidtop.shell.gamepad.MenuSectionLabel
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.ShellChip
import dev.droidtop.shell.gamepad.TextEditDialog
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One flattened row of a sheet: a section marker or a selectable row. */
private sealed interface FilterEntry {
    data class Header(val text: String) : FilterEntry
    data class Row(
        val title: String,
        val value: String? = null,
        val subtitle: String? = null,
        val chevron: Boolean = false,
        val danger: Boolean = false,
        val onLongClick: (() -> Unit)? = null,
        val onClick: (() -> Unit)? = null,
    ) : FilterEntry
}

/** A row a list adds to a sheet: a facet's own extra (Usage access under Recently used) or a foot action. */
internal data class SheetAction(val title: String, val subtitle: String? = null, val onClick: () -> Unit)

/** What the sheet counts off the main thread: the facets with their values, and how many entries show. */
private class SheetCounts(val offers: List<FacetOffer>, val shown: Int, val total: Int)

/**
 * X on a library or app list: the Filter sheet (docs/SPEC.md 7j). Two
 * levels, both controller-first: the facets this list offers with what each
 * is set to, and, one A deeper, a facet's values with how many entries
 * each would show (several values of one facet add up; facets narrow each
 * other). B goes back a level, then closes; X clears every filter. The
 * person's saved views and "Save this view" sit on the first level. Every
 * row is a touch target too. Counting happens off the main thread.
 */
@Composable
internal fun LibraryFilterSheet(
    scope: LibraryQueryScope,
    base: List<LibraryEntry>,
    query: LibraryQuery,
    savedViews: List<NamedLibraryView>,
    onQueryChange: (LibraryQuery) -> Unit,
    onSaveView: (String) -> Unit,
    onForgetView: (String) -> Unit,
    onSearch: (() -> Unit)?,
    onDismiss: () -> Unit,
    facetActions: Map<LibraryFacet, List<SheetAction>> = emptyMap(),
    footerActions: List<SheetAction> = emptyList(),
) {
    var focusIndex by remember { mutableIntStateOf(0) }
    var openFacet by remember { mutableStateOf<LibraryFacet?>(null) }
    var parentFocus by remember { mutableIntStateOf(0) }
    var naming by remember { mutableStateOf(false) }

    val counts by produceState<SheetCounts?>(null, base, scope, query) {
        value = withContext(Dispatchers.Default) {
            SheetCounts(query.facetOffers(base, scope), query.applyTo(base, scope).size, query.totalIn(base, scope))
        }
    }

    val entries = remember(query, counts, scope, savedViews, openFacet, facetActions, footerActions) {
        buildList<FilterEntry> {
            val facet = openFacet
            val offers = counts?.offers.orEmpty()
            if (facet == null) {
                if (onSearch != null) {
                    add(
                        FilterEntry.Row(
                            "Search",
                            value = query.text.trim().takeIf { it.isNotEmpty() }?.let { "\"$it\"" },
                            chevron = true,
                            onClick = onSearch,
                        ),
                    )
                }
                offers.forEach { offer ->
                    val selected = query.selected(offer.facet)
                    add(
                        FilterEntry.Row(
                            offer.facet.label,
                            value = when (selected.size) {
                                0 -> null
                                1 -> selected.first()
                                else -> "${selected.size} selected"
                            },
                            chevron = true,
                            onClick = {
                                parentFocus = focusIndex
                                openFacet = offer.facet
                                focusIndex = 0
                            },
                        ),
                    )
                }
                if (savedViews.isNotEmpty()) {
                    add(FilterEntry.Header("Views"))
                    savedViews.forEach { view ->
                        add(
                            FilterEntry.Row(
                                view.name,
                                value = if (view.query == query) "✓" else null,
                                onLongClick = { onForgetView(view.name) },
                                onClick = { onQueryChange(view.query) },
                            ),
                        )
                    }
                }
                if (!query.isEmpty) add(FilterEntry.Row("Clear filters", danger = true, onClick = { onQueryChange(query.cleared) }))
                add(FilterEntry.Row("Save this view", onClick = { naming = true }))
                footerActions.forEach { add(FilterEntry.Row(it.title, subtitle = it.subtitle, onClick = it.onClick)) }
            } else {
                offers.firstOrNull { it.facet == facet }?.values?.forEach { entry ->
                    val on = entry.value in query.selected(facet)
                    add(
                        FilterEntry.Row(
                            entry.value,
                            value = (if (on) "✓ " else "") + entry.count,
                            onClick = { onQueryChange(query.withToggled(facet, entry.value, !on)) },
                        ),
                    )
                }
                facetActions[facet].orEmpty().forEach { add(FilterEntry.Row(it.title, subtitle = it.subtitle, onClick = it.onClick)) }
                add(
                    FilterEntry.Row(
                        "Back",
                        onClick = {
                            openFacet = null
                            focusIndex = parentFocus
                        },
                    ),
                )
            }
        }
    }
    val rows = entries.filterIsInstance<FilterEntry.Row>()
    // The row list shrinks when a filter or a view goes away; the cursor
    // never points past the end it is drawn against.
    LaunchedEffect(rows.size) {
        focusIndex = focusIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    }

    if (naming) {
        TextEditDialog(
            title = "Save this view",
            subtitle = "The filters, the search and the sort as they are now, as one view in this list",
            initial = query.text.takeIf { it.isNotBlank() } ?: "",
            onCommit = { name ->
                naming = false
                if (name.isNotBlank()) onSaveView(name.trim())
            },
            onDismiss = { naming = false },
        )
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        MenuPanel(
            modifier = Modifier.width(dev.droidtop.shell.gamepad.LocalShellWindow.current.panelWidth(560.dp)),
            focusLabel = "Filter",
            onPad = { press ->
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> focusIndex = menuMove(focusIndex, rows.size, press)
                    GamepadAction.A -> rows.getOrNull(focusIndex)?.onClick?.invoke()
                    GamepadAction.B -> if (openFacet != null) {
                        openFacet = null
                        focusIndex = parentFocus
                    } else {
                        onDismiss()
                    }
                    GamepadAction.X -> if (!query.isEmpty) onQueryChange(query.cleared)
                    GamepadAction.SELECT -> onDismiss()
                    else -> Unit
                }
                true
            },
        ) {
            Text(
                openFacet?.label ?: "Filter",
                style = MaterialTheme.typography.titleLarge,
                color = MenuTokens.OnSurface,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            counts?.let {
                Text(
                    queryCountLine(it.shown, it.total, !query.isEmpty, scope),
                    color = MenuTokens.OnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            entries.forEach { entry ->
                when (entry) {
                    is FilterEntry.Header -> MenuSectionLabel(entry.text)
                    is FilterEntry.Row -> MenuRow(
                        title = entry.title,
                        value = entry.value,
                        subtitle = entry.subtitle,
                        chevron = entry.chevron,
                        danger = entry.danger,
                        selected = rows.indexOf(entry) == focusIndex,
                        onLongClick = entry.onLongClick,
                        onClick = entry.onClick ?: {},
                    )
                }
            }
            HintRow(
                bindings = listOf(
                    HintBinding(GamepadAction.A, "Select"),
                    HintBinding(GamepadAction.X, "Clear") { !query.isEmpty },
                    HintBinding(GamepadAction.B, if (openFacet != null) "Back" else "Close"),
                ),
                background = Color.Transparent,
            )
        }
    }
}

/**
 * Y on a library or app list: the Sort By sheet (docs/SPEC.md 7j). One row
 * per sort the list offers, the active one marked with its direction; A on
 * the sort that is already active flips its direction, on another starts it
 * in its natural order. A or B closes.
 */
@Composable
internal fun LibrarySortSheet(
    scope: LibraryQueryScope,
    query: LibraryQuery,
    onQueryChange: (LibraryQuery) -> Unit,
    onDismiss: () -> Unit,
) {
    var focusIndex by remember { mutableIntStateOf(scope.sorts.indexOf(query.sort).coerceAtLeast(0)) }
    Dialog(onDismissRequest = onDismiss) {
        MenuPanel(
            modifier = Modifier.width(dev.droidtop.shell.gamepad.LocalShellWindow.current.panelWidth(560.dp)),
            focusLabel = "Sort by",
            onPad = { press ->
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> focusIndex = menuMove(focusIndex, scope.sorts.size, press)
                    GamepadAction.A -> {
                        scope.sorts.getOrNull(focusIndex)?.let { onQueryChange(query.withSort(it)) }
                        onDismiss()
                    }
                    GamepadAction.B, GamepadAction.Y, GamepadAction.SELECT -> onDismiss()
                    else -> Unit
                }
                true
            },
        ) {
            Text(
                "Sort by",
                style = MaterialTheme.typography.titleLarge,
                color = MenuTokens.OnSurface,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            Text(
                "Pick the sort that is on again to flip its direction",
                color = MenuTokens.OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            scope.sorts.forEachIndexed { index, key ->
                val active = key == query.sort
                MenuRow(
                    title = scope.sortLabel(key),
                    value = if (active) "✓ ${key.orderLabel(query.reversed)}" else key.naturalOrder,
                    selected = index == focusIndex,
                    onClick = {
                        onQueryChange(query.withSort(key))
                        onDismiss()
                    },
                )
            }
            HintRow(
                bindings = listOf(
                    HintBinding(GamepadAction.A, "Sort"),
                    HintBinding(GamepadAction.B, "Close"),
                ),
                background = Color.Transparent,
            )
        }
    }
}

/**
 * The state line and the active-filter chips under a list's title: "12 of
 * 80 apps · Sort: Name, A to Z" always, and while anything filters one chip
 * per filter plus a leading Clear chip. Chips are a touch shortcut (a tap
 * takes that filter off) and are never a D-pad stop: the pad reaches the
 * same things through X (Filter) and Y (Sort By). [message] is a short
 * transient notice appended to the line.
 */
@Composable
internal fun QueryChipRow(
    scope: LibraryQueryScope,
    query: LibraryQuery,
    shown: Int,
    total: Int,
    onChange: (LibraryQuery) -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    val chips = query.activeChips(scope)
    Column(modifier = modifier) {
        Text(
            querySummaryLine(shown, total, query, scope) + (message?.let { " · $it" } ?: ""),
            color = MenuTokens.OnSurfaceMuted,
            style = dev.droidtop.shell.gamepad.TypeRole.supporting,
        )
        if (chips.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(dev.droidtop.shell.gamepad.Space.Sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = dev.droidtop.shell.gamepad.Space.Xs),
            ) {
                ShellChip("Clear", selected = false, onClick = { onChange(query.cleared) })
                chips.forEach { chip ->
                    ShellChip(chip.label, on = true, selected = false, onClick = { onChange(query.without(chip)) })
                }
            }
        }
    }
}

/**
 * A list's saved views (docs/SPEC.md 7j): the named filter-and-sort
 * combinations the filter sheet lists, kept per scope id. Reads and writes
 * go through [LibraryViewPrefs] off the main thread and republish [views].
 */
internal class SavedViews(
    private val context: Context,
    private val scopeId: String,
    private val coroutines: CoroutineScope,
) {
    var views by mutableStateOf<List<NamedLibraryView>>(emptyList())
        private set

    suspend fun load() {
        views = withContext(Dispatchers.IO) { LibraryViewPrefs.savedViews(context, scopeId) }
    }

    fun save(name: String, query: LibraryQuery) {
        coroutines.launch {
            withContext(Dispatchers.IO) { LibraryViewPrefs.saveView(context, scopeId, NamedLibraryView(name, query)) }
            load()
        }
    }

    fun forget(name: String) {
        coroutines.launch {
            withContext(Dispatchers.IO) { LibraryViewPrefs.removeView(context, scopeId, name) }
            load()
        }
    }
}

@Composable
internal fun rememberSavedViews(scopeId: String): SavedViews {
    val context = LocalContext.current
    val coroutines = rememberCoroutineScope()
    val saved = remember(scopeId) { SavedViews(context, scopeId, coroutines) }
    LaunchedEffect(saved) { saved.load() }
    return saved
}

/**
 * Remembers one view's query across visits: loaded from the list's prefs
 * once, then written back on every change, off the main thread.
 * [onLoaded] gets what was saved; [loaded] says it has.
 */
@Composable
internal fun PersistQuery(scopeId: String, query: LibraryQuery, loaded: Boolean, onLoaded: (LibraryQuery) -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(scopeId) {
        if (!loaded) onLoaded(withContext(Dispatchers.IO) { LibraryViewPrefs.activeQuery(context, scopeId) })
    }
    LaunchedEffect(query, loaded) {
        if (loaded) withContext(Dispatchers.IO) { LibraryViewPrefs.setActiveQuery(context, scopeId, query) }
    }
}

/**
 * Text search over one list: a field, the live count of what it matches,
 * and -- the "Search fan-out" mechanism, docs/SPEC.md 12a -- a "Get more"
 * group below it fed by every approved+enabled acquire_content source
 * plugin ([GameSources.plugins]), searched in parallel and debounced so a
 * keystroke doesn't fire a plugin round trip. This is the ONE shared
 * search surface (the commit that built this component: "console lists
 * get the same component"), so any list that opens this dialog gets
 * plugin results for free, never a second, plugin-specific search screen.
 *
 * [systemId]/[systemFolder] scope a plugin search to one system and give
 * downloads a real destination -- both null for a cross-system list (the
 * PC library today): results still show, but a result with no resolvable
 * destination cannot be downloaded from here and says so, rather than
 * silently failing a `startJob` call with an invalid path.
 *
 * Typing is text entry -- the platform's own keyboard, touched or
 * attached -- and B leaves without clearing what was typed, so the chips
 * row keeps showing the search as an active, clearable filter.
 */
@Composable
internal fun LibrarySearchDialog(
    query: LibraryQuery,
    matchCount: Int,
    totalCount: Int,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    systemId: String? = null,
    systemFolder: File? = null,
    // droidtop's own Recommendations (docs/SPEC.md 12a), shown while the
    // field is empty; picking one puts its title in the field. Computed by
    // the caller off the main thread, never here.
    suggestions: List<dev.droidtop.library.integrations.Recommendation> = emptyList(),
    // The launcher's search (docs/SPEC.md 12a "Launcher search") is this
    // same dialog with its own local results: the installed apps and the
    // library's games that match, drawn between the count line and the
    // "Get more" group. [summary] replaces the count line's wording, since
    // "N games match" is wrong for a list of apps and games together.
    summary: String? = null,
    results: (@Composable androidx.compose.foundation.layout.ColumnScope.(String) -> Unit)? = null,
) {
    var text by remember { mutableStateOf(query.text) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { fieldFocus.requestFocus() }
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // One outcome per source (docs/SPEC.md 12a "Search fan-out"): its results, or why it could not answer.
    var outcomes by remember { mutableStateOf<List<SourceOutcome>>(emptyList()) }
    // Plugins that are installed but cannot answer (waiting for approval, disabled), so "no source" is never said for them.
    var unavailable by remember { mutableStateOf<List<UnavailableSource>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var statusLine by remember { mutableStateOf<String?>(null) }
    // Bumped to search again after the person fixed something (installed a runtime, approved a plugin).
    var searchTick by remember { mutableIntStateOf(0) }
    var activeCatalog by remember { mutableStateOf<CatalogScreen?>(null) }
    // What a failed source's plugin reported in its own words, shown on request under its plain sentence (Droidtop/tracker#167).
    var technicalDetails by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val sourceHits = remember(outcomes) { outcomes.hits() }

    // Debounced fan-out: a keystroke doesn't itself trigger a plugin round
    // trip, only the text settling for a beat does -- the same reasoning
    // every existing debounced search in droidtop uses.
    LaunchedEffect(text, systemId, searchTick) {
        val q = text.trim()
        if (q.isBlank()) {
            outcomes = emptyList()
            unavailable = emptyList()
            searching = false
            return@LaunchedEffect
        }
        delay(350)
        searching = true
        // Reading the plugin store and calling a plugin are disk and binder work: never on the main thread.
        val (sources, notRunnable) = withContext(Dispatchers.IO) {
            GameSources.plugins(context) to AcquireContentSources.unavailablePlugins(context)
        }
        unavailable = notRunnable
        outcomes = if (sources.isEmpty()) emptyList() else PluginSearchAggregator.searchAll(context, sources, q, systemId)
        searching = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        GatePadInThisDialog()
        HideSystemBarsInThisDialog()
        Column(
            modifier = Modifier
                .width(dev.droidtop.shell.gamepad.LocalShellWindow.current.panelWidth(560.dp))
                // The pad's own B (and a keyboard's Escape) is not the
                // system back key, so the Dialog's dismiss-on-back does not
                // see it; the field does not type it either. Ahead of the
                // field, which leaves the keys it types to it (SPEC 6e).
                .onPad(preview = true) { press ->
                    if (press.action == GamepadAction.B) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .clip(MenuTokens.OverlayShape)
                .background(MenuTokens.OverlaySurface)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text(
                "Search",
                style = MaterialTheme.typography.titleLarge,
                color = MenuTokens.OnSurface,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    onTextChange(it)
                },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MenuTokens.OnSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .focusRequester(fieldFocus)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MenuTokens.SurfaceSelected)
                    .padding(12.dp),
            )
            Text(
                if (summary != null) {
                    summary
                } else if (text.isBlank()) {
                    "Type a name, a genre or a developer; ${totalCount} games to search"
                } else {
                    val shown = "$matchCount ${if (matchCount == 1) "game" else "games"} match"
                    if (matchCount == 0) "$shown. B closes; the search stays" else shown
                },
                color = MenuTokens.OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (results != null && text.isNotBlank()) results(text.trim())
            if (text.isBlank() && suggestions.isNotEmpty()) {
                MenuSectionLabel("Recommended for you")
                suggestions.forEach { pick ->
                    MenuRow(
                        title = pick.title,
                        subtitle = pick.reason,
                        onClick = {
                            text = pick.title
                            onTextChange(pick.title)
                        },
                    )
                }
            }
            if (text.isNotBlank()) {
                MenuSectionLabel(
                    when {
                        searching -> "Get more (searching…)"
                        outcomes.isEmpty() -> "Get more"
                        else -> "Get more (${sourceHits.size})"
                    },
                )
                if (!searching) {
                    // No source answered because none can: say which of the two cases it is. Every state ends at the
                    // one Get games entry below, which leads to Plugins when there is nothing to browse.
                    val state = GetMoreState.of(outcomes, unavailable)
                    when (state) {
                        GetMoreState.NO_SOURCE -> SourceNote("No download source is installed.")
                        GetMoreState.NOT_READY -> unavailable.forEach { SourceNote("${it.label} ${it.reason}") }
                        else -> Unit
                    }
                    outcomes.forEach { outcome ->
                        when {
                            outcome.failure != null -> {
                                SourceNote("${outcome.source.label}: ${outcome.failure}")
                                val pluginId = (outcome.source as? PluginGameSource)?.source?.record?.manifest?.id
                                if (pluginId != null) {
                                    technicalDetails[pluginId]?.let { SourceNote(it) } ?: MenuRow(
                                        title = "Technical details",
                                        subtitle = "What the plugin reported, for its developer",
                                        onClick = {
                                            coroutineScope.launch {
                                                val detail = withContext(Dispatchers.IO) { PluginStore.disabledDetail(context, pluginId) }
                                                technicalDetails = technicalDetails + (pluginId to (detail ?: "The plugin reported nothing more."))
                                            }
                                        },
                                    )
                                }
                                outcome.source.settingsScreen()?.let { settings ->
                                    MenuRow(title = "Open ${outcome.source.label} settings", onClick = { activeCatalog = settings })
                                }
                                outcome.runtimeNeed?.let { need ->
                                    MenuRow(
                                        title = need.actionLabel,
                                        subtitle = "Then the search runs again by itself",
                                        onClick = {
                                            coroutineScope.launch {
                                                val error = withContext(Dispatchers.IO) {
                                                    PluginRuntimeNeeds.install(context, need) { statusLine = it }
                                                }
                                                if (error == null) {
                                                    statusLine = null
                                                    searchTick++
                                                } else {
                                                    statusLine = "The ${need.runtime} runtime could not be installed: $error"
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                            outcome.results.isEmpty() -> SourceNote("${outcome.source.label}: no match")
                        }
                        // A source whose plugin has a full screen of its own offers it here too (ui.main, docs/plugin-api.md 1.7).
                        if (outcome.source.hasMainUi) {
                            MenuRow(
                                title = "Open ${outcome.source.label}",
                                subtitle = "Its own screen. Back returns here",
                                onClick = {
                                    coroutineScope.launch {
                                        val problem = withContext(Dispatchers.IO) { outcome.source.openMainUi(context) }
                                        statusLine = problem
                                    }
                                },
                            )
                        }
                    }
                    MenuRow(
                        title = GetGamesEntry.LABEL,
                        subtitle = GetGamesEntry.searchSubtitle(state),
                        onClick = { activeCatalog = getGamesScreen(GetGamesContext.SEARCH, systemId) },
                    )
                }
                sourceHits.forEach { hit ->
                    MenuRow(
                        title = hit.result.title,
                        subtitle = listOfNotNull(hit.source.label, hit.result.columns.joinToString(" · ").takeIf { it.isNotBlank() }, hit.result.platform, hit.result.sizeLabel).joinToString(" · "),
                        onClick = {
                            if (systemFolder == null) {
                                statusLine = "No download destination configured for this list -- open ${hit.result.title} from its own system to download it"
                            } else {
                                activeCatalog = hit.source.detailScreen(hit.result, systemId, null, systemFolder)
                            }
                        },
                    )
                }
                statusLine?.let { line ->
                    Text(line, color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
            MenuHint("B keeps what is typed; delete the text to clear it")
        }
    }

    activeCatalog?.let { screen ->
        val close: () -> Unit = {
            activeCatalog = null
            searchTick += 1
        }
        Dialog(onDismissRequest = close) { CatalogNavigator(root = screen, onExit = close) }
    }
}

/** One muted line of the "Get more" group: a source's outcome when it has no rows to show. */
@Composable
private fun SourceNote(text: String) {
    Text(text, color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
}
