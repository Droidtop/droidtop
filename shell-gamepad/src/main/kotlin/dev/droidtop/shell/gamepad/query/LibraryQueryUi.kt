package dev.droidtop.shell.gamepad.query

import dev.droidtop.shell.gamepad.input.onPad
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.menuMove
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import dev.droidtop.library.integrations.PluginSearchAggregator
import dev.droidtop.library.integrations.SourceHit
import dev.droidtop.library.integrations.SourceOutcome
import dev.droidtop.library.integrations.UnavailableSource
import dev.droidtop.library.integrations.hits
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.pluginhost.PluginRuntimeNeeds
import dev.droidtop.shell.gamepad.CatalogNavigator
import dev.droidtop.shell.gamepad.MenuHint
import dev.droidtop.shell.gamepad.MenuPanel
import dev.droidtop.shell.gamepad.MenuRow
import dev.droidtop.shell.gamepad.MenuSectionLabel
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.ShellChip
import dev.droidtop.shell.gamepad.TextEditDialog
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One flattened row of the filter dialog: a section marker or a selectable row. */
private sealed interface FilterEntry {
    data class Header(val text: String) : FilterEntry
    data class Row(
        val title: String,
        val value: String? = null,
        val danger: Boolean = false,
        val onLongClick: (() -> Unit)? = null,
        val onClick: (() -> Unit)? = null,
    ) : FilterEntry
}

/**
 * The whole filter, sort and saved-view surface of one library list: every
 * facet the scope offers with every value the list actually holds, the
 * sort, the person's saved views (activate with A, forget with a
 * long-press), and saving the current view under a name. Entirely
 * controller-driven like every menu in this shell: Up/Down moves, A
 * activates, B closes -- and every row is a touch target too.
 */
@Composable
internal fun LibraryFilterDialog(
    scope: LibraryQueryScope,
    base: List<LibraryEntry>,
    query: LibraryQuery,
    savedViews: List<NamedLibraryView>,
    onQueryChange: (LibraryQuery) -> Unit,
    onSaveView: (String) -> Unit,
    onForgetView: (String) -> Unit,
    onSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    var focusIndex by remember { mutableIntStateOf(0) }
    var naming by remember { mutableStateOf(false) }

    val entries = remember(query, base, scope, savedViews) {
        buildList {
            add(
                FilterEntry.Row("Sort by", value = query.sort.label) {
                    val offered = scope.sorts
                    val next = offered[(offered.indexOf(query.sort) + 1) % offered.size]
                    onQueryChange(query.copy(sort = next))
                },
            )
            add(FilterEntry.Row("Search", value = query.text.takeIf { it.isNotBlank() }?.let { "\"$it\"" }, onClick = onSearch))
            scope.facets.forEach { facet ->
                val values = facet.valuesIn(base, scope.context)
                // A facet with no values in this list is not offered here:
                // "runner" before the background pass has answered and
                // "ProtonDB tier" before anything was asked have nothing to
                // filter yet, and a list of nothing is noise, not a filter.
                if (values.isEmpty()) return@forEach
                add(FilterEntry.Header(facet.label))
                values.forEach { value ->
                    add(
                        FilterEntry.Row(value, value = if (value in query.selected(facet)) "✓" else null) {
                            onQueryChange(query.withToggled(facet, value, value !in query.selected(facet)))
                        },
                    )
                }
            }
            if (savedViews.isNotEmpty()) {
                add(FilterEntry.Header("Views"))
                savedViews.forEach { view ->
                    add(
                        FilterEntry.Row(
                            view.name,
                            value = if (view.query == query) "✓" else null,
                            onLongClick = { onForgetView(view.name) },
                        ) {
                            onQueryChange(view.query)
                        },
                    )
                }
            }
            if (!query.isEmpty) {
                add(FilterEntry.Row("Clear every filter", danger = true) { onQueryChange(query.clearFacets.copy(text = "")) })
            }
            add(FilterEntry.Row("Save this view") { naming = true })
            add(FilterEntry.Row("Close", onClick = onDismiss))
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
            focusLabel = "Filters and sort",
            onPad = { press ->
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> focusIndex = menuMove(focusIndex, rows.size, press)
                    GamepadAction.A -> rows.getOrNull(focusIndex)?.onClick?.invoke()
                    GamepadAction.B, GamepadAction.SELECT -> onDismiss()
                    else -> Unit
                }
                true
            },
        ) {
            Text(
                "Filters and sort",
                style = MaterialTheme.typography.titleLarge,
                color = MenuTokens.OnSurface,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            Text(
                "${query.applyTo(base, scope).size} of ${base.size} games shown",
                color = MenuTokens.OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            entries.forEach { entry ->
                when (entry) {
                    is FilterEntry.Header -> MenuSectionLabel(entry.text)
                    is FilterEntry.Row -> {
                        val index = rows.indexOf(entry)
                        MenuRow(
                            title = entry.title,
                            value = entry.value,
                            danger = entry.danger,
                            selected = index == focusIndex,
                            onLongClick = entry.onLongClick,
                            onClick = entry.onClick ?: {},
                        )
                    }
                }
            }
            MenuHint("Up/Down moves, A chooses, B closes")
        }
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
                    if (matchCount == 0) "$shown. B closes; the search stays on the chip row" else shown
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
                    val openPlugins = { activeCatalog = SettingsScreenRegistry.get(AcquireContentSources.PLUGINS_SCREEN_ID) }
                    if (outcomes.isEmpty()) {
                        // No source answered because none can: say which of the two cases it is.
                        if (unavailable.isEmpty()) {
                            SourceNote("No download source is installed.")
                            MenuRow(
                                title = "Install a download source",
                                subtitle = "Opens Settings > Accounts and sources > Plugins",
                                onClick = openPlugins,
                            )
                        } else {
                            unavailable.forEach { SourceNote("${it.label} ${it.reason}") }
                            MenuRow(title = "Open Plugins", subtitle = "Approve or turn on a download source", onClick = openPlugins)
                        }
                    }
                    outcomes.forEach { outcome ->
                        when {
                            outcome.failure != null -> {
                                SourceNote("${outcome.source.label}: ${outcome.failure}")
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
            MenuHint("B keeps what is typed; clear it from the chip row")
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
