package dev.droidtop.shell.gamepad.query

import dev.droidtop.ui.shared.NoticeCard
import dev.droidtop.library.settings.NoticePlacement
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.library.settings.Notice
import dev.droidtop.shell.gamepad.getGamesScreen
import dev.droidtop.shell.gamepad.input.onPad
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.HideSystemBarsInThisDialog
import dev.droidtop.shell.gamepad.menuMove
import dev.droidtop.shell.gamepad.moveCue
import dev.droidtop.shell.gamepad.input.DeclareLayerHints
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.TextRange
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.droidtop.library.LibraryEntry
import java.io.File
import dev.droidtop.library.integrations.GetGamesContext
import dev.droidtop.library.integrations.GetGamesEntry
import dev.droidtop.library.integrations.GetMoreState
import dev.droidtop.library.integrations.PluginGameSource
import dev.droidtop.library.integrations.LocalSearchRow
import dev.droidtop.library.integrations.AcquireContentSources
import dev.droidtop.library.integrations.SourceDestinations
import dev.droidtop.library.integrations.SourceHit
import dev.droidtop.library.integrations.UnavailableSource
import dev.droidtop.library.integrations.UnifiedSearch
import dev.droidtop.library.integrations.UnifiedState
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.pluginhost.PluginRuntimeNeeds
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.shell.gamepad.CatalogNavigator
import dev.droidtop.shell.gamepad.MenuPanel
import dev.droidtop.shell.gamepad.MenuRow
import dev.droidtop.shell.gamepad.MenuSectionLabel
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.ShellChip
import dev.droidtop.shell.gamepad.TextEditDialog
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One flattened row of a sheet: a section marker or a selectable row. */
private sealed interface FilterEntry {
    data class Header(val text: String) : FilterEntry
    /** A line of words under a facet's values (what "Engine" means); not a stop. */
    data class Note(val text: String) : FilterEntry
    data class Row(
        val title: String,
        val value: String? = null,
        val subtitle: String? = null,
        val chevron: Boolean = false,
        val danger: Boolean = false,
        val onLongClick: (() -> Unit)? = null,
        /** What A does here, in the hint bar, where "Select" would not say it (a save row's own words). */
        val hint: String? = null,
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
    // A list whose saved views can be tabs (PC Games, docs/SPEC.md 7i): "Save
    // this view" becomes "Save as a tab" ([onSaveView]) and "Save only" (this).
    onSaveOnly: ((String) -> Unit)? = null,
    // Editing a pinned tab: one row saves the filters into that view as it is
    // named, in place (same view, same tab position).
    editingName: String? = null,
) {
    var focusIndex by remember { mutableIntStateOf(0) }
    var openFacet by remember { mutableStateOf<LibraryFacet?>(null) }
    var parentFocus by remember { mutableIntStateOf(0) }
    // Naming a view to save: null while not, else whether it becomes a tab.
    var naming by remember { mutableStateOf<Boolean?>(null) }

    val counts by produceState<SheetCounts?>(null, base, scope, query) {
        value = withContext(Dispatchers.Default) {
            SheetCounts(query.facetOffers(base, scope), query.applyTo(base, scope).size, query.totalIn(base, scope))
        }
    }

    val entries = remember(query, counts, scope, savedViews, openFacet, facetActions, footerActions, editingName) {
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
                                1 -> offer.facet.valueLabel(selected.first(), scope.context)
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
                when {
                    editingName != null -> add(
                        FilterEntry.Row("Save \"$editingName\"", subtitle = "These filters and sort, in the same tab", hint = "Save") { onSaveView(editingName) },
                    )
                    onSaveOnly != null -> {
                        add(FilterEntry.Row("Save as a tab", subtitle = "A tab on the strip, after the others", hint = "Save as a tab") { naming = true })
                        add(FilterEntry.Row("Save only", subtitle = "Listed with your saved views in Collections", hint = "Save only") { naming = false })
                    }
                    else -> add(FilterEntry.Row("Save this view", hint = "Save") { naming = true })
                }
                footerActions.forEach { add(FilterEntry.Row(it.title, subtitle = it.subtitle, onClick = it.onClick)) }
            } else {
                offers.firstOrNull { it.facet == facet }?.values?.forEach { entry ->
                    val on = entry.value in query.selected(facet)
                    add(
                        FilterEntry.Row(
                            facet.valueLabel(entry.value, scope.context),
                            value = (if (on) "✓ " else "") + entry.count,
                            onClick = { onQueryChange(query.withToggled(facet, entry.value, !on)) },
                        ),
                    )
                }
                facet.hint?.let { add(FilterEntry.Note(it)) }
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

    naming?.let { asTab ->
        TextEditDialog(
            title = if (onSaveOnly == null) "Save this view" else if (asTab) "Save as a tab" else "Save only",
            subtitle = "Current filters and sort",
            initial = query.text.takeIf { it.isNotBlank() } ?: "",
            onCommit = { name ->
                naming = null
                if (name.isNotBlank()) if (asTab || onSaveOnly == null) onSaveView(name.trim()) else onSaveOnly(name.trim())
            },
            onDismiss = { naming = null },
        )
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        GatePadInThisDialog()
        MenuPanel(
            modifier = Modifier.width(dev.droidtop.shell.gamepad.LocalShellWindow.current.panelWidth(560.dp)),
            focusLabel = "Filter",
            title = openFacet?.label ?: "Filter",
            hints = listOf(
                HintBinding(GamepadAction.A, rows.getOrNull(focusIndex)?.hint ?: "Select"),
                HintBinding(GamepadAction.X, "Clear") { !query.isEmpty },
                HintBinding(GamepadAction.B, if (openFacet != null) "Back" else "Close"),
            ),
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
                    is FilterEntry.Note -> NoticeCard(Notice(NoticeTone.Info, entry.text), NoticePlacement.Group)
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
        GatePadInThisDialog()
        MenuPanel(
            modifier = Modifier.width(dev.droidtop.shell.gamepad.LocalShellWindow.current.panelWidth(560.dp)),
            focusLabel = "Sort by",
            title = "Sort by",
            hints = listOf(HintBinding(GamepadAction.A, "Sort"), HintBinding(GamepadAction.B, "Close")),
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
        views = withContext(Dispatchers.IO) {
            LibraryViewPrefs.seedOnce(context, scopeId)
            LibraryViewPrefs.savedViews(context, scopeId)
        }
    }

    fun save(name: String, query: LibraryQuery) = put(NamedLibraryView(name, query))

    /** Saves [view]: in place when its id (or name) is saved already, else at the end. */
    fun put(view: NamedLibraryView) {
        coroutines.launch {
            withContext(Dispatchers.IO) { LibraryViewPrefs.saveView(context, scopeId, view) }
            load()
        }
    }

    /** Every saved view at once, in this order (a move, a pin, an unpin); shown at once, written off the main thread. */
    fun replaceAll(next: List<NamedLibraryView>) {
        views = next
        coroutines.launch { withContext(Dispatchers.IO) { LibraryViewPrefs.writeViews(context, scopeId, next) } }
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
 * THE search (docs/SPEC.md 12a "One search", Droidtop/tracker#315): a field
 * and ONE ranked list, opened from the PC library, a console gamelist's
 * Select menu and the launcher's drawer. The list is fed by [UnifiedSearch]:
 * the device's own rows from [local] (installed apps and library games,
 * matched in memory by the caller) and every download source, built-in or
 * plugin, which join the same list as they answer. A source's result is a
 * row like any other; there is no "Get more" group and no plugin mode.
 * A source that could not answer is told apart by a line under the rows
 * that names it; one that waits for approval or is turned off is a row
 * that opens its page in Settings, Plugins.
 *
 * Every surface passes [local] for the rows of its own library, the PC
 * library included, so the one list always holds the device's rows and the
 * sources'. A surface whose list is on screen under the dialog (a console
 * gamelist) may pass none: that list narrows live as the text changes
 * ([onTextChange]) and the dialog shows the match count above the sources'
 * rows.
 *
 * [systemId]/[systemFolder] scope the sources to one system and give a
 * download its destination. Without them (the PC library, the launcher) a
 * picked source row works its destination out from the result itself
 * ([SourceDestinations]), and a result that has nowhere to go opens a page
 * saying which folder is missing, never nothing. [sourceFilter] drops the
 * results a surface has no use for (the PC library drops console systems).
 *
 * Typing is text entry, by the platform's keyboard or an attached one. The
 * field asks for the soft keyboard explicitly when it opens (an implicit
 * request is dropped while a pad or keyboard counts as a hardware
 * keyboard). The pad reaches the rows: Down (or Search on the keyboard)
 * leaves the field for the first row, Up from the first row returns to the
 * field, A opens the row under the cursor, and typing puts the cursor back
 * in the field. B leaves without clearing what was typed.
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
    // The device's own rows for the text (installed apps and library games).
    // Runs off the main thread over lists already in memory.
    local: (suspend (String) -> List<LocalSearchRow>)? = null,
    // Changes when what [local] or [sourceFilter] read changed (the library finished loading): the search runs again for the same text.
    localKey: Any? = null,
    // Switches under the field that widen what [local] matches (the launcher's
    // "Include hidden games"); the caller holds their state, off each time it opens.
    switches: List<SearchSwitch> = emptyList(),
    // Drops a source's result this surface has no use for, before it is ranked.
    sourceFilter: ((dev.droidtop.library.integrations.AcquireContentResult) -> Boolean)? = null,
    // What the sources are asked for when it is not a console system (the PC library asks for PC games), so they can
    // narrow their own answer. A console list leaves it null and asks for its [systemId].
    sourcePlatform: String? = null,
    // The settings of this surface: matching ones follow the rows as a section called Settings, and a press opens the
    // screen the setting lives on. Null where the surface has no settings of its own to search.
    settingsRoot: CatalogScreen? = null,
) {
    // The field reopens with what was typed and the caret at its END, so Backspace deletes the last letter
    // (Droidtop/tracker#376: a plain String field put the caret at the start).
    var field by remember {
        mutableStateOf(TextFieldValue(query.text, selection = TextRange(query.text.length)))
    }
    val text = field.text
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    // A page a result opens (Get games, a source's detail, a plugin's settings) takes the window over: the
    // search panel is not drawn under it, because two translucent windows put text on text (Droidtop/tracker#542).
    // What was typed and where the cursor was stay in this composition and are there when the page closes.
    var activeCatalog by remember { mutableStateOf<CatalogScreen?>(null) }
    var firstShow by remember { mutableStateOf(true) }
    LaunchedEffect(activeCatalog == null) {
        if (activeCatalog != null) return@LaunchedEffect
        // The field lives in the dialog's own composition, which attaches a frame or more after this
        // effect starts: one early request failed silently on a reopen and left the field unfocused
        // until A (Droidtop/tracker#376). Ask until it is attached.
        dev.droidtop.shell.gamepad.requestFocusWhenAttached(fieldFocus, "Search field")
        // Back from a page the soft keyboard stays away: the pad is on a row, not typing.
        if (!firstShow) return@LaunchedEffect
        firstShow = false
        // Explicit, not implicit: Compose's own request on focus is dropped while a pad or keyboard is attached.
        delay(120)
        keyboard?.show()
        val imm = view.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        android.util.Log.i(
            "droidtop.search",
            "search field on display ${view.display?.displayId} windowFocus=${view.hasWindowFocus()} imeActive=${imm?.isActive(view)}",
        )
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var state by remember { mutableStateOf(UnifiedState.empty()) }
    var statusLine by remember { mutableStateOf<String?>(null) }
    // Bumped to search again after the person fixed something (installed a runtime, approved a plugin).
    var searchTick by remember { mutableIntStateOf(0) }
    // What a failed source reported in its own words, shown on request under its plain sentence (Droidtop/tracker#167), by source id.
    var technicalDetails by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // The row under the pad's cursor, by key so a source answering above it does not move it; null is the field.
    var focusKey by remember { mutableStateOf<String?>(null) }
    val currentLocal by rememberUpdatedState(local)
    val currentFilter by rememberUpdatedState(sourceFilter)
    // The settings index is built once, off the main thread, the first time the text is long enough to match one
    // (SettingsSearchIndex.search wants two letters); typing afterwards only filters it in memory.
    val settingsIndex by produceState<List<dev.droidtop.library.settings.SettingsSearchResult>?>(null, settingsRoot, text.length >= 2) {
        if (settingsRoot != null && text.length >= 2 && value == null) {
            value = try {
                withContext(Dispatchers.IO) { dev.droidtop.library.settings.SettingsSearchIndex.build(context, settingsRoot) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("droidtop.search", "settings index failed: ${e.message}")
                emptyList()
            }
        }
    }

    // Typing puts the cursor back in the field.
    LaunchedEffect(text) { focusKey = null }

    // Restarted by every keystroke: the previous query is cancelled, the device's rows are back at once and the sources
    // are asked only once the text rests (UnifiedSearch.SOURCE_DEBOUNCE_MS).
    LaunchedEffect(text, systemId, sourcePlatform, searchTick, localKey) {
        UnifiedSearch.search(
            context = context,
            query = text,
            platform = sourcePlatform ?: systemId,
            local = { q -> currentLocal?.invoke(q).orEmpty() },
            carry = state.sourceHits,
            keep = { result -> currentFilter?.invoke(result) ?: true },
        ).collect { state = it }
    }

    // Opening a source's result: its destination is worked out off the main thread, then its detail opens. The row is
    // never a dead end: a result with no folder to go to opens a page that says which one is missing.
    fun openHit(hit: SourceHit) {
        coroutineScope.launch {
            val pick = SourceDestinations.resolve(context, systemId, systemFolder, hit.result.platform)
            activeCatalog = hit.source.detailScreen(hit.result, pick.systemId, pick.systemName, pick.folder)
        }
    }

    fun openPlugin(pluginId: String?) {
        activeCatalog = SettingsScreenRegistry.get(AcquireContentSources.PLUGINS_SCREEN_ID, pluginId)
    }

    val lines = buildList<SearchLine> {
        switches.forEach { switch ->
            add(SearchLine.Entry("switch:${switch.label}", switch.label, value = if (switch.on) "On" else "Off", onPick = switch.toggle))
        }
        if (local == null) {
            add(
                SearchLine.Note(
                    if (text.isBlank()) {
                        "$totalCount games"
                    } else if (matchCount == 0) {
                        "No games match"
                    } else {
                        "$matchCount ${if (matchCount == 1) "game" else "games"} match"
                    },
                ),
            )
        }
        if (text.isBlank() && suggestions.isNotEmpty()) {
            add(SearchLine.Header("Recommended for you"))
            suggestions.forEach { pick ->
                add(
                    SearchLine.Entry("suggestion:${pick.title}", pick.title, subtitle = pick.reason) {
                        field = TextFieldValue(pick.title, selection = TextRange(pick.title.length))
                        onTextChange(pick.title)
                    },
                )
            }
        }
        if (text.isNotBlank()) {
            state.rows.forEach { row ->
                add(
                    SearchLine.Entry("row:${row.kind}:${row.key}", row.title, subtitle = row.detail, icon = row.icon) {
                        val hit = row.hit
                        val open = row.open
                        when {
                            open != null -> {
                                open()
                                onDismiss()
                            }
                            hit != null -> openHit(hit)
                        }
                    },
                )
            }
            // Settings are a different thing being searched (docs/SPEC.md 7k), so they are a section of their own after the
            // rows, a few at most; the full list is the Settings page's own search.
            val settingsHits = settingsIndex?.let { dev.droidtop.library.settings.SettingsSearchIndex.search(it, text).take(MAX_SETTINGS_ROWS) }.orEmpty()
            if (settingsHits.isNotEmpty()) {
                add(SearchLine.Header("Settings"))
                settingsHits.forEach { hit ->
                    add(SearchLine.Entry("setting:${hit.itemId}", hit.itemTitle, subtitle = hit.screenTitle) { activeCatalog = hit.target })
                }
            }
            when {
                !state.settled -> add(SearchLine.Note("Searching"))
                state.rows.isEmpty() && settingsHits.isEmpty() -> add(SearchLine.Note("No match"))
            }
            if (state.settled) {
                // Sources that wait for the person: one row that opens the approval, not a line per plugin.
                val waiting = state.unavailable.filter { it.waitingForApproval }
                if (waiting.isNotEmpty()) {
                    add(
                        SearchLine.Entry(
                            "waiting",
                            UnavailableSource.waitingTitle(waiting),
                            subtitle = UnavailableSource.waitingSubtitle(waiting),
                        ) { openPlugin(waiting.singleOrNull()?.pluginId) },
                    )
                }
                state.unavailable.filter { !it.waitingForApproval }.forEach { off ->
                    add(SearchLine.Entry("off:${off.pluginId}", "${off.label} ${off.reason}", subtitle = "Open its page") { openPlugin(off.pluginId) })
                }
                state.sources.filter { it.failure != null }.forEach { status ->
                    val source = status.source
                    add(SearchLine.Note("${source.label}: ${status.failure}"))
                    val shown = technicalDetails[source.id]
                    if (shown != null) {
                        add(SearchLine.Note(shown))
                    } else {
                        add(
                            SearchLine.Entry("technical:${source.id}", "Technical details", subtitle = source.label) {
                                coroutineScope.launch {
                                    val pluginId = (source as? PluginGameSource)?.source?.record?.manifest?.id
                                    val recorded = pluginId?.let { withContext(Dispatchers.IO) { PluginStore.disabledDetail(context, it) } }
                                    technicalDetails = technicalDetails + (
                                        source.id to (listOfNotNull(status.technical, recorded).joinToString("\n").ifBlank { "The source reported nothing more." })
                                    )
                                }
                            },
                        )
                    }
                    source.settingsScreen()?.let { settings ->
                        add(SearchLine.Entry("settings:${source.id}", "${source.label} settings") { activeCatalog = settings })
                    }
                    status.runtimeNeed?.let { need ->
                        add(
                            SearchLine.Entry("runtime:${source.id}", need.actionLabel, subtitle = "Searches again after") {
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
                // The one way on from a search that found nothing to get: browse the sources, or install one.
                val getGames = GetMoreState.of(state.sources.mapNotNull { it.outcome }, state.unavailable)
                add(
                    SearchLine.Entry("get_games", GetGamesEntry.LABEL, subtitle = GetGamesEntry.searchSubtitle(getGames)) {
                        activeCatalog = getGamesScreen(GetGamesContext.SEARCH, systemId)
                    },
                )
            }
            statusLine?.let { add(SearchLine.Note(it)) }
        }
    }
    val entries = lines.filterIsInstance<SearchLine.Entry>()

    if (activeCatalog == null) Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        GatePadInThisDialog()
        HideSystemBarsInThisDialog()
        DeclareLayerHints(listOf(HintBinding(GamepadAction.A, "Open") { focusKey != null }, HintBinding(GamepadAction.B, "Close")))
        Column(
            modifier = Modifier
                .width(dev.droidtop.shell.gamepad.LocalShellWindow.current.panelWidth(560.dp))
                // The pad's own B (and a keyboard's Escape) is not the system back key, so the Dialog's
                // dismiss-on-back does not see it; the field does not type it either. Up, Down and A move
                // the cursor between the field and the rows. All of it ahead of the field, which keeps the
                // keys it types (SPEC 6e).
                .onPad(preview = true) { press ->
                    val at = entries.indexOfFirst { it.key == focusKey }
                    when (press.action) {
                        GamepadAction.B -> {
                            onDismiss()
                            true
                        }
                        GamepadAction.DOWN -> if (entries.isEmpty()) {
                            false
                        } else {
                            if (at < 0) {
                                keyboard?.hide()
                                moveCue(true, press.repeat)
                                focusKey = entries.first().key
                            } else {
                                focusKey = entries[menuMove(at, entries.size, press)].key
                            }
                            true
                        }
                        GamepadAction.UP -> when {
                            at < 0 -> false
                            at == 0 -> {
                                moveCue(true, press.repeat)
                                focusKey = null
                                keyboard?.show()
                                true
                            }
                            else -> {
                                focusKey = entries[menuMove(at, entries.size, press)].key
                                true
                            }
                        }
                        GamepadAction.A -> if (at >= 0) {
                            entries[at].onPick()
                            true
                        } else {
                            false
                        }
                        else -> false
                    }
                }
                .clip(MenuTokens.OverlayShape)
                .background(MenuTokens.OverlaySurface)
                // The panel stays above the soft keyboard (the dialog window is edge to edge, so the keyboard's
                // height arrives as an inset).
                .imePadding()
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
                value = field,
                onValueChange = {
                    val changed = it.text != field.text
                    field = it
                    if (changed) onTextChange(it.text)
                },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = {
                        keyboard?.hide()
                        entries.firstOrNull()?.let { focusKey = it.key }
                    },
                ),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MenuTokens.OnSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .focusRequester(fieldFocus)
                    .clip(dev.droidtop.shell.gamepad.Corners.Crisp)
                    .background(MenuTokens.SurfaceSelected)
                    .padding(12.dp),
            )
            // On a screen Android draws no keyboard on (the add-on display), droidtop's own (SPEC 4c, tracker#314).
            dev.droidtop.shell.gamepad.OwnFieldKeyboard()
            Column(modifier = Modifier.padding(top = 8.dp)) {
                lines.forEach { line ->
                    when (line) {
                        is SearchLine.Header -> MenuSectionLabel(line.text)
                        is SearchLine.Note -> SourceNote(line.text)
                        is SearchLine.Entry -> SearchEntryRow(line, selected = line.key == focusKey) {
                            focusKey = line.key
                            line.onPick()
                        }
                    }
                }
            }
        }
    }

    activeCatalog?.let { screen ->
        val close: () -> Unit = {
            activeCatalog = null
            searchTick += 1
        }
        Dialog(onDismissRequest = close) {
            GatePadInThisDialog()
            CatalogNavigator(root = screen, onExit = close)
        }
    }
}

/** The most settings the search lists; a person who wants more opens Settings. */
private const val MAX_SETTINGS_ROWS = 5

/** A switch under the search field: its label, whether it is on, and what pressing it does. */
internal data class SearchSwitch(val label: String, val on: Boolean, val toggle: () -> Unit)

/** What the search dialog draws, top to bottom: a heading, a muted line, or a row the pad's cursor can stop on. */
private sealed interface SearchLine {
    data class Header(val text: String) : SearchLine
    data class Note(val text: String) : SearchLine
    class Entry(
        /** Stable across a redraw, so the cursor stays on its row while sources answer above it. */
        val key: String,
        val title: String,
        val subtitle: String? = null,
        val value: String? = null,
        val icon: android.graphics.Bitmap? = null,
        val onPick: () -> Unit,
    ) : SearchLine
}

/** One row of the one list: an app with its icon, a library game, a source's result, or a follow-up action. */
@Composable
private fun SearchEntryRow(entry: SearchLine.Entry, selected: Boolean, onPick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        entry.icon?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp).size(40.dp),
            )
        }
        MenuRow(
            title = entry.title,
            subtitle = entry.subtitle,
            value = entry.value,
            selected = selected,
            modifier = Modifier.weight(1f),
            onClick = onPick,
        )
    }
}

/** One muted line under the rows: a source's outcome when it has no rows to show. */
@Composable
private fun SourceNote(text: String) {
    Text(text, color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
}
