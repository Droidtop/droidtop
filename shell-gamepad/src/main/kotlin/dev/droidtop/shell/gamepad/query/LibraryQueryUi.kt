package dev.droidtop.shell.gamepad.query

import dev.droidtop.ui.shared.NoticeCard
import dev.droidtop.library.settings.NoticePlacement
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.library.settings.Notice
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.droidtop.library.LibraryEntry
import java.io.File
import dev.droidtop.library.integrations.GetGamesEntry
import dev.droidtop.library.integrations.SourceGets
import dev.droidtop.library.integrations.PluginGameSource
import dev.droidtop.library.integrations.LocalSearchRow
import dev.droidtop.library.integrations.SearchRow
import dev.droidtop.library.integrations.SearchRowKind
import dev.droidtop.library.integrations.AcquireContentSources
import dev.droidtop.library.integrations.SourceDestinations
import dev.droidtop.library.integrations.SourceHit
import dev.droidtop.library.integrations.UnavailableSource
import dev.droidtop.library.integrations.UnifiedSearch
import dev.droidtop.library.integrations.SourceSelection
import dev.droidtop.library.integrations.SourceFix
import dev.droidtop.library.settings.StatusMark
import dev.droidtop.library.settings.Notices
import dev.droidtop.library.integrations.UnifiedState
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.pluginhost.PluginRuntimeNeeds
import dev.droidtop.pluginhost.PluginWebAccount
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.shell.gamepad.MenuPanel
import dev.droidtop.shell.gamepad.MenuRow
import dev.droidtop.shell.gamepad.MenuSectionLabel
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.ShellChip
import dev.droidtop.shell.gamepad.TextEditDialog
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import dev.droidtop.ui.shared.holdKeyFocus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.droidtop.shell.gamepad.ShellDialog

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

    ShellDialog(onDismissRequest = onDismiss) {
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
    ShellDialog(onDismissRequest = onDismiss) {
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
 * [onLoaded] gets what was saved, or [default] when nothing was; [loaded]
 * says it has, and nothing is written before it.
 */
@Composable
internal fun PersistQuery(
    scopeId: String,
    query: LibraryQuery,
    loaded: Boolean,
    default: LibraryQuery = LibraryQuery(),
    onLoaded: (LibraryQuery) -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(scopeId) {
        if (!loaded) onLoaded(withContext(Dispatchers.IO) { LibraryViewPrefs.activeQuery(context, scopeId, default) })
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
    // The person opened the search to type (a Search row they picked), so typing starts at once (Droidtop/tracker#612).
    startTyping: Boolean = false,
    // Get games (Droidtop/tracker#825): the source filter's chips under the field, and with no text what the sources
    // feature now, so it opens on games rather than a blank list.
    getGames: Boolean = false,
) {
    // The field reopens with what was typed and the caret at its END, so Backspace deletes the last letter
    // (Droidtop/tracker#376: a plain String field put the caret at the start).
    var field by remember {
        mutableStateOf(TextFieldValue(query.text, selection = TextRange(query.text.length)))
    }
    val text = field.text
    val fieldFocus = remember { FocusRequester() }
    // The panel's own focus, which it takes back whenever the field gives focus up (Droidtop/tracker#747).
    val panelFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    // Its window must not raise Android's keyboard by itself when the field takes the cursor (Droidtop/tracker#832).
    dev.droidtop.shell.gamepad.KeepKeyboardDownOnFocus()
    // A page a result opens (a source's detail, a plugin's settings) takes the window over: the
    // search panel is not drawn under it, because two translucent windows put text on text (Droidtop/tracker#542).
    // What was typed and where the cursor was stay in this composition and are there when the page closes.
    var activeCatalog by remember { mutableStateOf<CatalogScreen?>(null) }
    // The merged row whose places are being chosen from (its key), or null for the result list.
    var choosing by remember { mutableStateOf<String?>(null) }
    var firstShow by remember { mutableStateOf(true) }
    // Whether the person chose to type (A or a tap on the field, or a key typed). Putting the cursor in the field is
    // droidtop's doing, not a choice, so no keyboard comes up for it, except on one touch-only screen (owner,
    // 2026-10-23, Droidtop/tracker#612; AddonKeyboardRules.raisesOnOwnFocus).
    var typing by remember { mutableStateOf(startTyping || dev.droidtop.shell.gamepad.KeyboardTargets.raisesOnOwnFocus()) }
    // Android's keyboard, where it is the one that types here; droidtop's own (the companion's Keys) follows [typing].
    fun androidKeyboard() {
        if (!dev.droidtop.shell.gamepad.KeyboardTargets.servesField(view.display?.displayId)) keyboard?.show()
    }
    LaunchedEffect(activeCatalog == null) {
        if (activeCatalog != null) return@LaunchedEffect
        // The field lives in the dialog's own composition, which attaches a frame or more after this
        // effect starts: one early request failed silently on a reopen and left the field unfocused
        // until A (Droidtop/tracker#376). Ask until it is attached.
        dev.droidtop.shell.gamepad.requestFocusWhenAttached(fieldFocus, "Search field")
        // Back from a page the soft keyboard stays away: the pad is on a row, not typing.
        if (!firstShow) return@LaunchedEffect
        firstShow = false
        if (!typing) return@LaunchedEffect
        // Explicit, not implicit: Compose's own request on focus is not reliable on a first show.
        delay(120)
        androidKeyboard()
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
    // Whether the folded "N sources need attention" row was opened; a new search folds them again.
    var failuresOpen by remember(text) { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    // The row under the pad's cursor, by key so a source answering above it does not move it; null is the field.
    var focusKey by remember { mutableStateOf<String?>(null) }
    // The chip under the cursor while it is on the source filter's row (Left and Right move it).
    var chipAt by remember { mutableIntStateOf(0) }
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
    LaunchedEffect(text) {
        focusKey = null
        choosing = null
    }

    // JSON acquire_content integrations, read once off the main thread: each is one hand-off row, after every source's
    // (docs/SPEC.md 13, "There is no Get games sheet"; IntegrationHandoff), in the searches its template can fill.
    val handoffs by produceState(emptyList<dev.droidtop.library.integrations.Integration>()) {
        value = withContext(Dispatchers.IO) {
            dev.droidtop.library.integrations.IntegrationStore.available(context, dev.droidtop.library.integrations.IntegrationCapability.ACQUIRE_CONTENT)
        }
    }
    val scopedToSystem = GetGamesEntry.searchSystem(systemId) != null && sourcePlatform == null
    fun handoffRows(q: String): List<LocalSearchRow> =
        handoffs.filter { dev.droidtop.library.integrations.IntegrationHandoff.covers(it.argumentsTemplate, scopedToSystem) }.map { integration ->
            val (title, detail) = dev.droidtop.library.integrations.IntegrationHandoff.text(integration.label, integration.argumentsTemplate, q)
            LocalSearchRow("handoff:${integration.id}", SearchRowKind.WEB, title, detail ?: integration.description.orEmpty()) {
                SourceGets.handOff(context, integration, systemId, systemFolder, q) { statusLine = it }
            }
        }

    // Restarted by every keystroke: the previous query is cancelled, the device's rows are back at once and the sources
    // are asked only once the text rests (UnifiedSearch.SOURCE_DEBOUNCE_MS).
    LaunchedEffect(text, systemId, sourcePlatform, searchTick, localKey, handoffs) {
        // Set up off the main thread: it reads the source filter's preferences (SourceSelection).
        withContext(Dispatchers.IO) {
            UnifiedSearch.search(
                context = context,
                query = text,
                platform = sourcePlatform ?: systemId,
                local = { q -> currentLocal?.invoke(q).orEmpty() + handoffRows(q) },
                carry = state.sourceHits,
                keep = { result -> currentFilter?.invoke(result) ?: true },
                featuredWhenEmpty = getGames,
            )
        }.collect { state = it }
    }

    // Opening a source's result: its destination is worked out off the main thread, then its detail opens. The row is
    // never a dead end: a result with no folder to go to opens a page that says which one is missing.
    // A result that carries `get` is fetched from its row as a job (docs/SPEC.md 13, "Get from the row"); the search stays
    // open and says so. Without `get`, or with no folder to put it in, the result's page opens (it says what is missing).
    fun openHit(hit: SourceHit) {
        coroutineScope.launch {
            val pick = SourceDestinations.resolve(context, systemId, systemFolder, hit.result.platform)
            val folder = pick.folder
            if (folder != null && hit.source.canGet(hit.result)) {
                statusLine = SourceGets.startedLine(hit.result.title)
                SourceGets.start(context, hit, pick.systemId, pick.systemName, folder) { statusLine = it }
            } else {
                activeCatalog = hit.source.detailScreen(hit.result, pick.systemId, pick.systemName, folder)
            }
        }
    }

    fun openPlugin(pluginId: String?) {
        activeCatalog = SettingsScreenRegistry.get(AcquireContentSources.PLUGINS_SCREEN_ID, pluginId)
    }

    val lines = buildList<SearchLine> {
        switches.forEach { switch ->
            add(SearchLine.Entry("switch:${switch.label}", switch.label, on = switch.on, onPick = switch.toggle))
        }
        // Where Get games searches (owner, 2026-10-30, Droidtop/tracker#825: "a filter to choose where it searches"): a
        // chip per source, on or off and kept (SourceSelection), turning while its source is asked and saying so when it
        // failed. A press turns it over and searches again.
        if (getGames) {
            val chips = sourceChips(state)
            if (chips.isNotEmpty()) {
                add(
                    SearchLine.Chips(SOURCE_CHIPS_KEY, chips, at = chipAt.coerceIn(0, chips.lastIndex)) { chip ->
                        coroutineScope.launch {
                            withContext(Dispatchers.IO) { SourceSelection.set(context, chip.id, !chip.on) }
                            searchTick++
                        }
                    },
                )
            }
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
        // One row's own action: a device row opens (a game, its page), a source's row gets or opens its result.
        fun pick(row: SearchRow) {
            val hit = row.hit
            val open = row.open
            when {
                open != null -> {
                    open()
                    onDismiss()
                }
                hit != null -> openHit(hit)
            }
        }
        // One result as one line. A source's row says what pressing it does: Get when it can be fetched from here, else
        // it opens; a game from several places asks where from.
        fun rowLine(row: SearchRow): SearchLine = SearchLine.Entry(
            "row:${row.kind}:${row.key}",
            row.title,
            // One game from several sources names them all (Droidtop/tracker#825: "from F95zone · LewdCorner").
            subtitle = sourcesLine(row.choices.map { UnifiedSearch.placeOf(it) }) ?: row.detail,
            art = row.hit?.result?.artUrl?.takeIf { it.isNotBlank() },
            value = when {
                row.others.isNotEmpty() -> "${row.choices.size} places"
                else -> row.hit?.let { if (it.source.canGet(it.result)) "Get" else null }
            },
            icon = row.icon,
        ) {
            if (row.others.isNotEmpty()) choosing = row.key else pick(row)
        }
        val choosingRow = choosing?.let { picked -> state.rows.firstOrNull { it.key == picked } }
        if (choosingRow != null) {
            // The same game from several places: where from, then that one's page (owner, 2026-10-16). B goes back.
            add(SearchLine.Header("${choosingRow.title}: where from"))
            choosingRow.choices.forEach { choice ->
                add(
                    SearchLine.Entry(
                        "choice:${choice.kind}:${choice.key}",
                        UnifiedSearch.placeOf(choice),
                        subtitle = listOfNotNull(choice.title.takeIf { it != choosingRow.title }, choice.hit?.result?.columns?.joinToString(" · ")).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
                        value = choice.hit?.let { if (it.source.canGet(it.result)) "Get" else null },
                    ) {
                        choosing = null
                        pick(choice)
                    },
                )
            }
        } else if (text.isNotBlank() || getGames) {
            // What is here first, then the matches from the download sources under their own heading (owner, 2026-10-23:
            // "Get new games should be presented under the search results", Droidtop/tracker#612). Get games with no text
            // lists what the sources feature now (Droidtop/tracker#825).
            val (here, new) = state.rows.partition { !it.isNew }
            here.forEach { row -> add(rowLine(row)) }
            if (new.isNotEmpty()) {
                add(SearchLine.Header(if (state.featured) FEATURED else dev.droidtop.library.integrations.GetGamesEntry.NEW_GAMES))
                new.forEach { row -> add(rowLine(row)) }
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
                !state.settled -> add(SearchLine.Note(if (text.isBlank()) "Asking your sources" else "Searching"))
                state.rows.isEmpty() && settingsHits.isEmpty() -> add(SearchLine.Note(if (text.isBlank()) "Nothing featured right now" else "No match"))
            }
            if (state.settled) {
                // Sources that wait for the person: one row that opens the approval, not a line per plugin.
                val waiting = state.unavailable.filter { it.waitingForApproval }
                if (waiting.isNotEmpty()) {
                    add(
                        SearchLine.Entry(
                            "waiting",
                            UnavailableSource.waitingTitle(waiting),
                            subtitle = if (waiting.singleOrNull()?.askable == true) "Press to allow it to get games" else UnavailableSource.waitingSubtitle(waiting),
                        ) {
                            // One source missing only the library.sources point: ask here, and search again once allowed (Droidtop/tracker#270).
                            val ask = waiting.singleOrNull()?.takeIf { it.askable }
                            if (ask == null) {
                                openPlugin(waiting.singleOrNull()?.pluginId)
                            } else {
                                coroutineScope.launch {
                                    if (AcquireContentSources.askSource(context, ask)) searchTick++
                                    else statusLine = "${ask.label} is still not allowed to get games"
                                }
                            }
                        },
                    )
                }
                state.unavailable.filter { !it.waitingForApproval }.forEach { off ->
                    add(SearchLine.Entry("off:${off.pluginId}", "${off.label} ${off.reason}", subtitle = "Open its page") { openPlugin(off.pluginId) })
                }
                // A source's own search options (docs/plugin-api.md 1.10, search.filter): its page, then the search again.
                state.sources.forEach { status ->
                    status.source.filterScreen(context)?.let { options ->
                        add(SearchLine.Entry("filters:${status.source.id}", "${status.source.label}: search options", subtitle = "Searches again after") { activeCatalog = options })
                    }
                }
                // A failed source is one compact row below the results (docs/SPEC.md 12a, "One search", Droidtop/tracker#746):
                // its icon, the attention glyph and its short reason, and A runs the one fix (SourceFix). Several fold into
                // one row until it is opened. Technical details stay one press away, and a second press copies them.
                val failed = state.sources.filter { it.failure != null }
                if (failed.size > 1 && !failuresOpen) {
                    add(
                        SearchLine.Entry(
                            "failures",
                            SourceFix.foldedTitle(failed.size),
                            subtitle = failed.joinToString(", ") { it.source.label },
                            mark = StatusMark.ATTENTION,
                        ) { failuresOpen = true },
                    )
                }
                if (failed.size == 1 || failuresOpen) failed.forEach { status ->
                    val source = status.source
                    val record = (source as? PluginGameSource)?.source?.record
                    val access = status.access
                    val runtime = status.runtimeNeed
                    val fix = SourceFix.of(
                        access = access,
                        siteKnown = record != null && PluginWebAccount.checkUrl(record) != null,
                        permissionOff = status.outcome?.permissionOff == true && record != null,
                        needsRuntime = runtime != null,
                    )
                    val label = if (fix == SourceFix.GET_RUNTIME && runtime != null) runtime.actionLabel else fix.label
                    add(
                        SearchLine.Entry(
                            "failed:${source.id}",
                            "${source.label}: ${status.failure}",
                            subtitle = if (fix == SourceFix.OPEN_PAGE) "$label. Allow it there, then search again" else "$label. Searches again after",
                            iconFile = record?.let { PluginStore.iconFile(context, it) },
                            mark = StatusMark.ATTENTION,
                        ) {
                            when (fix) {
                                SourceFix.SIGN_IN, SourceFix.OPEN_SITE -> if (record != null && access != null) {
                                    coroutineScope.launch {
                                        val kept = withContext(Dispatchers.IO) { PluginWebAccount.open(context, record, access) }
                                        if (kept && access == PluginWebAccount.Need.SIGN_IN) dev.droidtop.library.integrations.notifyWebSignedIn(context, record)
                                        if (kept) searchTick++ else statusLine = PluginWebAccount.notKept(record.manifest.label, access)
                                    }
                                }
                                SourceFix.OPEN_PAGE -> openPlugin(record?.manifest?.id)
                                SourceFix.GET_RUNTIME -> if (runtime != null) {
                                    coroutineScope.launch {
                                        val error = withContext(Dispatchers.IO) {
                                            PluginRuntimeNeeds.install(context, runtime) { statusLine = it }
                                        }
                                        if (error == null) {
                                            statusLine = null
                                            searchTick++
                                        } else {
                                            statusLine = "The ${runtime.runtime} runtime could not be installed: $error"
                                        }
                                    }
                                }
                                SourceFix.TRY_AGAIN -> searchTick++
                            }
                        },
                    )
                    val shown = technicalDetails[source.id]
                    add(
                        SearchLine.Entry(
                            "technical:${source.id}",
                            "Technical details",
                            subtitle = shown ?: source.label,
                            value = if (shown != null) "Copy" else null,
                        ) {
                            if (shown != null) {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(shown))
                                Notices.flash("Technical details copied", source = source.label)
                            } else {
                                coroutineScope.launch {
                                    val recorded = record?.let { withContext(Dispatchers.IO) { PluginStore.disabledDetail(context, it.manifest.id) } }
                                    technicalDetails = technicalDetails + (
                                        source.id to (listOfNotNull(status.technical, recorded).joinToString("\n").ifBlank { "The source reported nothing more." })
                                    )
                                }
                            }
                        },
                    )
                }
                // No source could be asked at all: one line that leads to Plugins (docs/SPEC.md 13, "There is no Get games
                // sheet"). A source waiting or turned off already has its own row above.
                GetGamesEntry.noSourceLine(state.sources.size, state.unavailable.size, scoped = systemId != null || sourcePlatform != null)?.let { line ->
                    add(SearchLine.Entry("no_source", line, subtitle = "Open Plugins") { openPlugin(null) })
                }
            }
            statusLine?.let { add(SearchLine.Note(it)) }
        }
    }
    val entries = lines.filterIsInstance<SearchLine.Stop>()

    if (activeCatalog == null) ShellDialog(
        // Back from the places of one game returns to the results, as B does.
        onDismissRequest = { if (choosing != null) choosing = null else onDismiss() },
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
                            // From the places of one game, B goes back to the results; from the results it closes.
                            if (choosing != null) choosing = null else onDismiss()
                            true
                        }
                        // On the source filter's row, Left and Right move between its chips.
                        GamepadAction.LEFT, GamepadAction.RIGHT -> {
                            val chips = entries.getOrNull(at) as? SearchLine.Chips
                            if (chips == null) {
                                false
                            } else {
                                val step = if (press.action == GamepadAction.RIGHT) 1 else -1
                                chipAt = (chips.at + step).coerceIn(0, chips.chips.lastIndex)
                                true
                            }
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
                                // The cursor is back in the field, and so is focus: the panel may hold it since Back
                                // hid the keyboard.
                                runCatching { fieldFocus.requestFocus() }
                                if (typing) androidKeyboard()
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
                            // A on the field is choosing to type.
                            typing = true
                            runCatching { fieldFocus.requestFocus() }
                            androidKeyboard()
                            true
                        }
                        else -> false
                    }
                }
                // Under the handler, so it always hears the pad: Back hiding the on-screen keyboard clears the
                // field's focus, and with no focus inside the panel D-pad Up and A did nothing while a tap still
                // worked (console, Droidtop/tracker#747). The rows take no focus (padRow), so the panel holds it.
                .holdKeyFocus(panelFocus)
                .clip(MenuTokens.OverlayShape)
                .background(MenuTokens.OverlaySurface)
                // The panel stays above the soft keyboard (the dialog window is edge to edge, so the keyboard's
                // height arrives as an inset).
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text(
                if (getGames) GetGamesEntry.LABEL else "Search",
                style = MaterialTheme.typography.titleLarge,
                color = MenuTokens.OnSurface,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            BasicTextField(
                value = field,
                onValueChange = {
                    val changed = it.text != field.text
                    if (changed) typing = true
                    field = it
                    if (changed) onTextChange(it.text)
                },
                singleLine = true,
                // Android's keyboard does not come up just because the field took the cursor: Compose raised it on
                // every focus, over the Gaming screen, even where droidtop's own keyboard serves the field on the
                // companion (Droidtop/tracker#832). The one rule every droidtop field follows (raisesKeyboardWithFocus).
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Search,
                    showKeyboardOnFocus = dev.droidtop.shell.gamepad.raisesKeyboardWithFocus,
                ),
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
                    // A tap on the field is choosing to type.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            typing = true
                        }
                    }
                    .focusRequester(fieldFocus)
                    .clip(dev.droidtop.shell.gamepad.Corners.Crisp)
                    .background(MenuTokens.SurfaceSelected)
                    .padding(12.dp),
            )
            // droidtop's own keyboard where it serves the field (the companion's Keys, or a screen Android draws none
            // on; SPEC 4c), once the person chose to type and while the cursor is in the field: on the rows it closes,
            // the companion's included (#485 rig step 1).
            dev.droidtop.shell.gamepad.OwnFieldKeyboard(active = typing && focusKey == null)
            Column(modifier = Modifier.padding(top = 8.dp)) {
                lines.forEach { line ->
                    when (line) {
                        is SearchLine.Header -> MenuSectionLabel(line.text)
                        is SearchLine.Note -> SourceNote(line.text)
                        is SearchLine.Entry -> SearchEntryRow(line, selected = line.key == focusKey) {
                            focusKey = line.key
                            line.onPick()
                        }
                        is SearchLine.Chips -> SourceChipRow(line, cursorAt = line.at.takeIf { line.key == focusKey }) { index ->
                            focusKey = line.key
                            chipAt = index
                            line.toggle(line.chips[index])
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
        // The one sheet with the ground painted (CatalogSheet): a bare Dialog let the screen behind show through (rig, 0.2.0-dev.2070).
        dev.droidtop.shell.gamepad.CatalogSheet(root = screen, onExit = close)
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

    /** A line the pad's cursor stops on; A runs [onPick]. */
    sealed interface Stop : SearchLine {
        /** Stable across a redraw, so the cursor stays on its row while sources answer above it. */
        val key: String
        val onPick: () -> Unit
    }

    /** Get games' source filter: one row of chips, Left and Right between them, A turns the one under the cursor over. */
    class Chips(override val key: String, val chips: List<SourceChip>, val at: Int, val toggle: (SourceChip) -> Unit) : Stop {
        override val onPick: () -> Unit = { chips.getOrNull(at)?.let(toggle) }
    }

    class Entry(
        override val key: String,
        val title: String,
        val subtitle: String? = null,
        val value: String? = null,
        val icon: android.graphics.Bitmap? = null,
        /** An on/off row's state, drawn as a switch (show, then say: Droidtop/tracker#738); null for any other row. */
        val on: Boolean? = null,
        /** A plugin's own icon as a file, for a row about the plugin itself (a failed source). */
        val iconFile: String? = null,
        /** A state glyph beside the title (a failed source's attention, Droidtop/tracker#746). */
        val mark: StatusMark? = null,
        /** A source result's box art, a file or a web address, drawn before the row (Droidtop/tracker#825). */
        val art: String? = null,
        override val onPick: () -> Unit,
    ) : Stop
}

/** One source in Get games' filter: on or off, still being asked, or failed (its short reason). */
internal data class SourceChip(val id: String, val label: String, val on: Boolean, val busy: Boolean, val failed: Boolean)

/** The filter's chips: the sources asked, in their order, then the ones turned off. Pure. */
internal fun sourceChips(state: UnifiedState): List<SourceChip> =
    state.sources.map { SourceChip(it.source.id, it.source.label, on = true, busy = it.pending, failed = it.failure != null) } +
        state.off.map { SourceChip(it.id, it.label, on = false, busy = false, failed = false) }

/** "from F95zone · LewdCorner" for a game more than one source has, else null (the row's own detail says it). Pure. */
internal fun sourcesLine(places: List<String>): String? =
    places.distinct().takeIf { it.size > 1 }?.joinToString(" · ", prefix = "from ")

/** The heading over what the sources feature when Get games has no text yet. */
private const val FEATURED = "Featured"

/** The source filter's row key. */
private const val SOURCE_CHIPS_KEY = "source_chips"

/** The source filter, drawn: a chip per source, its ring where the pad's cursor is ([cursorAt]). */
@Composable
private fun SourceChipRow(line: SearchLine.Chips, cursorAt: Int?, onPick: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        line.chips.forEachIndexed { index, chip ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                dev.droidtop.shell.gamepad.ShellChip(
                    label = if (chip.failed) "${chip.label} · failed" else chip.label,
                    on = chip.on,
                    selected = cursorAt == index,
                    onClick = { onPick(index) },
                )
                // Turning while its source is asked (show, then say).
                if (chip.busy) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.padding(start = 4.dp).size(14.dp),
                        strokeWidth = 2.dp,
                        color = MenuTokens.OnSurfaceMuted,
                    )
                }
            }
        }
    }
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
        entry.art?.let { art ->
            coil3.compose.AsyncImage(
                model = if (art.startsWith("https://") || art.startsWith("http://")) art else java.io.File(art),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.padding(end = 8.dp).size(width = 48.dp, height = 64.dp).clip(dev.droidtop.shell.gamepad.Corners.Crisp),
            )
        }
        MenuRow(
            title = entry.title,
            subtitle = entry.subtitle,
            value = entry.value,
            switchOn = entry.on,
            iconFile = entry.iconFile,
            statusMark = entry.mark,
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
