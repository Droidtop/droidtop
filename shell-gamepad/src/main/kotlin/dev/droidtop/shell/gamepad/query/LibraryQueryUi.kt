package dev.droidtop.shell.gamepad.query

import dev.droidtop.shell.gamepad.getGamesScreen
import dev.droidtop.shell.gamepad.input.onPad
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.HideSystemBarsInThisDialog
import dev.droidtop.shell.gamepad.menuMove
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
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
import dev.droidtop.library.integrations.SearchRow
import dev.droidtop.library.integrations.UnifiedSearch
import dev.droidtop.library.integrations.UnifiedState
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
                                1 -> offer.facet.valueLabel(selected.first())
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
                            facet.valueLabel(entry.value),
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
                    is FilterEntry.Note -> MenuHint(entry.text)
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
 * Only a source that could not answer is told apart, by a line under the
 * rows that names it and the way to fix it.
 *
 * A surface whose list is on screen under the dialog (the PC library, a
 * console gamelist) passes no [local]: that list narrows live as the text
 * changes ([onTextChange]) and the dialog shows the match count above the
 * sources' rows.
 *
 * [systemId]/[systemFolder] scope the sources to one system and give a
 * download its destination; both are null for a cross-system list (the PC
 * library, the launcher), where picking a source's row says there is no
 * folder instead of sending an invalid path to the plugin.
 *
 * Typing is text entry, by the platform's keyboard or an attached one. The
 * field asks for the soft keyboard explicitly when it opens (an implicit
 * request is dropped while a pad or keyboard counts as a hardware
 * keyboard), Search on the keyboard hides it and moves the selection to the
 * first row, and B leaves without clearing what was typed.
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
    // The device's own rows for the text (the launcher: installed apps and
    // library games). Runs off the main thread over lists already in memory.
    local: (suspend (String) -> List<LocalSearchRow>)? = null,
    // Changes when what [local] reads changed (the library finished loading): the search runs again for the same text.
    localKey: Any? = null,
    // Switches under the field that widen what [local] matches (the launcher's
    // "Include hidden games"); the caller holds their state, off each time it opens.
    switches: List<SearchSwitch> = emptyList(),
) {
    // The field reopens with what was typed and the caret at its END, so Backspace deletes the last letter
    // (Droidtop/tracker#376: a plain String field put the caret at the start).
    var field by remember {
        mutableStateOf(TextFieldValue(query.text, selection = TextRange(query.text.length)))
    }
    val text = field.text
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    LaunchedEffect(Unit) {
        // The field lives in the dialog's own composition, which attaches a frame or more after this
        // effect starts: one early request failed silently on a reopen and left the field unfocused
        // until A (Droidtop/tracker#376). Ask until it is attached.
        dev.droidtop.shell.gamepad.requestFocusWhenAttached(fieldFocus, "Search field")
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
    var activeCatalog by remember { mutableStateOf<CatalogScreen?>(null) }
    // What a failed source's plugin reported in its own words, shown on request under its plain sentence (Droidtop/tracker#167).
    var technicalDetails by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val currentLocal by rememberUpdatedState(local)

    // Restarted by every keystroke: the previous query is cancelled, the device's rows are back at once and the sources
    // are asked only once the text rests (UnifiedSearch.SOURCE_DEBOUNCE_MS).
    LaunchedEffect(text, systemId, searchTick, localKey) {
        UnifiedSearch.search(
            context = context,
            query = text,
            platform = systemId,
            local = { q -> currentLocal?.invoke(q).orEmpty() },
            carry = state.sourceHits,
        ).collect { state = it }
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
                        focusManager.moveFocus(FocusDirection.Down)
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
            switches.forEach { switch ->
                MenuRow(title = switch.label, value = if (switch.on) "On" else "Off", onClick = switch.toggle)
            }
            if (local == null) {
                Text(
                    if (text.isBlank()) {
                        "$totalCount games"
                    } else if (matchCount == 0) {
                        "No games match"
                    } else {
                        "$matchCount ${if (matchCount == 1) "game" else "games"} match"
                    },
                    color = MenuTokens.OnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (text.isBlank() && suggestions.isNotEmpty()) {
                MenuSectionLabel("Recommended for you")
                suggestions.forEach { pick ->
                    MenuRow(
                        title = pick.title,
                        subtitle = pick.reason,
                        onClick = {
                            field = TextFieldValue(pick.title, selection = TextRange(pick.title.length))
                            onTextChange(pick.title)
                        },
                    )
                }
            }
            if (text.isNotBlank()) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    state.rows.forEach { row ->
                        SearchResultRow(
                            row = row,
                            onPick = {
                                val hit = row.hit
                                val open = row.open
                                when {
                                    open != null -> {
                                        open()
                                        onDismiss()
                                    }
                                    hit == null -> Unit
                                    systemFolder == null -> statusLine = "No download folder for this list"
                                    else -> activeCatalog = hit.source.detailScreen(hit.result, systemId, null, systemFolder)
                                }
                            },
                        )
                    }
                    when {
                        !state.settled -> SourceNote("Searching")
                        state.rows.isEmpty() -> SourceNote("No match")
                    }
                    if (state.settled) {
                        state.unavailable.forEach { SourceNote("${it.label} ${it.reason}") }
                        state.sources.filter { it.failure != null }.forEach { status ->
                            val source = status.source
                            SourceNote("${source.label}: ${status.failure}")
                            val pluginId = (source as? PluginGameSource)?.source?.record?.manifest?.id
                            if (pluginId != null) {
                                technicalDetails[pluginId]?.let { SourceNote(it) } ?: MenuRow(
                                    title = "Technical details",
                                    subtitle = source.label,
                                    onClick = {
                                        coroutineScope.launch {
                                            val detail = withContext(Dispatchers.IO) { PluginStore.disabledDetail(context, pluginId) }
                                            technicalDetails = technicalDetails + (pluginId to (detail ?: "The plugin reported nothing more."))
                                        }
                                    },
                                )
                            }
                            source.settingsScreen()?.let { settings ->
                                MenuRow(title = "${source.label} settings", onClick = { activeCatalog = settings })
                            }
                            status.runtimeNeed?.let { need ->
                                MenuRow(
                                    title = need.actionLabel,
                                    subtitle = "Searches again after",
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
                        // The one way on from a search that found nothing to get: browse the sources, or install one.
                        val getGames = GetMoreState.of(state.sources.mapNotNull { it.outcome }, state.unavailable)
                        MenuRow(
                            title = GetGamesEntry.LABEL,
                            subtitle = GetGamesEntry.searchSubtitle(getGames),
                            onClick = { activeCatalog = getGamesScreen(GetGamesContext.SEARCH, systemId) },
                        )
                    }
                    statusLine?.let { line ->
                        Text(line, color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    }
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
        Dialog(onDismissRequest = close) {
            GatePadInThisDialog()
            CatalogNavigator(root = screen, onExit = close)
        }
    }
}

/** A switch under the search field: its label, whether it is on, and what pressing it does. */
internal data class SearchSwitch(val label: String, val on: Boolean, val toggle: () -> Unit)

/** One row of the one list: an app with its icon, a library game, or a source's result. */
@Composable
private fun SearchResultRow(row: SearchRow, onPick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        row.icon?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp).size(40.dp),
            )
        }
        MenuRow(
            title = row.title,
            subtitle = row.detail,
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
