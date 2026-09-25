package dev.droidtop.shell.gamepad

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogIcon
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.FolderPickItem
import dev.droidtop.library.settings.GamingSettingsCatalog
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.SettingsSearchIndex
import dev.droidtop.library.settings.SettingsSearchResult
import dev.droidtop.library.settings.SliderItem
import dev.droidtop.library.settings.SubScreenItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import dev.droidtop.shell.gamepad.theme.ThemeBrowserScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The in-shell (gamepad-first, dark-chrome) renderer for the shared
 * settings catalogs (docs/SPEC.md settings architecture): catalogs own
 * which settings exist, their layout and their write paths; this
 * navigator just chromes them -- including nested management screens
 * ([CatalogScreen]: console systems, platform CRUD, ROM folders, scraper
 * credentials), which push onto a real nav stack in the same visual
 * language instead of bouncing to differently-styled activities.
 *
 * Input: Up/Down move the selection, Left/Right adjust the selected
 * value in place (choices cycle, sliders step -- real ES-DE's own menu
 * convention), A activates (toggles, opens pickers/nested screens/text
 * editors, runs actions), B pops one level and exits at the root.
 * Touch works on every row too.
 *
 * B has three routes into here, because on real hardware it arrives as
 * three different things and a screen with no way out is the worst
 * defect a menu can have (rig, build 539: the Settings tab sat on
 * "Windows games" with Game folders, Rescan library and Software updates
 * all unreachable): the system back DISPATCHER (BackHandler, which is
 * what KEYCODE_BACK and the hint row's own touch route become),
 * KEYCODE_BUTTON_B and Escape as ordinary key events (the branch in the
 * key handler), and the hint row itself, which the shell draws for this
 * section because Back always does something here.
 *
 * [nativeActions]: renderer-native fulfillments by catalog item id (see
 * the catalog doc comment) -- when present, activating that item calls
 * the override instead of the item's own default run.
 */
@Composable
fun CatalogNavigator(
    root: CatalogScreen,
    onExit: () -> Unit,
    nativeActions: Map<String, () -> Unit> = emptyMap(),
    // The settings home only (docs/SPEC.md settings architecture,
    // "search across settings") -- a nested management screen hosted
    // by its own CatalogNavigator (Console systems, Containers) does
    // not get a second search entry.
    showSearch: Boolean = false,
    /**
     * Changes when the host knows the screen's live data changed outside
     * any action taken here (the desktop session starting or stopping,
     * the host coming back to the front); the screen is re-read then.
     */
    refreshKey: Any? = null,
) {
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }
    val stack = remember { mutableStateListOf(root) }
    // Selection is per-depth so popping restores where the user was.
    val selectionByDepth = remember { mutableStateMapOf<Int, Int>() }
    // And so is the scroll: one list shows every depth, so coming back
    // from a sub-screen found the list where the sub-screen had left it
    // and scrolled the restored row to the top, and the row under the
    // finger was no longer the one below the row just used (rig,
    // dq-shell2-02: the Keyboard picker opened instead of Android settings).
    val scrollByDepth = remember { mutableStateMapOf<Int, Pair<Int, Int>>() }
    val listState = rememberLazyListState()
    // Live status text per item id (async progress/outcomes, pick errors).
    val statusById = remember { mutableStateMapOf<String, String>() }
    // Two-step confirm: the armed destructive item, reset on any move.
    var confirmArmedId by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf<TextInputItem?>(null) }
    var pickingChoice by remember { mutableStateOf<ChoiceItem?>(null) }
    // Y's Info sheet: the selected row's whole text. Rows show one line
    // of explanation; this is where the rest of it is.
    var infoRow by remember { mutableStateOf<CatalogItem?>(null) }
    var pendingFolderPick by remember { mutableStateOf<FolderPickItem?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchIndex by remember { mutableStateOf<List<SettingsSearchResult>>(emptyList()) }
    val scope = rememberCoroutineScope()

    val screen = stack.last()
    val depth = stack.lastIndex
    LaunchedEffect(searchOpen) {
        if (searchOpen && searchIndex.isEmpty()) {
            searchIndex = withContext(Dispatchers.IO) { SettingsSearchIndex.build(context, root) }
        }
    }
    // Suspend builder (real screens run Room queries / filesystem walks) --
    // rebuilt on every navigation and after every value change.
    // Tagged with the screen they belong to: right after a push or a pop
    // the list must not treat the previous screen's rows as this one's.
    val loaded by androidx.compose.runtime.produceState<Pair<CatalogScreen, List<CatalogGroup>>?>(null, screen, version, refreshKey) {
        value = screen to screen.groups(context)
    }
    val groups = loaded?.takeIf { it.first == screen }?.second ?: emptyList()
    val rows = remember(groups, showSearch, depth) {
        val built = groups.flatMap { group ->
            group.items.mapIndexed { index, item ->
                CatalogRow(item, headerAbove = if (index == 0) group.title else null)
            }
        }
        if (showSearch && depth == 0) {
            listOf(
                CatalogRow(
                    ActionItem(
                        id = SEARCH_ROW_ID,
                        title = "Search settings",
                        subtitle = "Find any setting by name",
                        icon = CatalogIcon.SEARCH,
                        run = {},
                    ),
                    headerAbove = null,
                ),
            ) + built
        } else {
            built
        }
    }
    val selected = (selectionByDepth[depth] ?: 0).coerceIn(0, (rows.lastIndex).coerceAtLeast(0))

    fun refresh() {
        version++
    }

    // Moving to a DIFFERENT row disarms a pending confirm. Re-selecting the
    // same row must not: a tap selects and activates in one gesture, so the
    // second tap of a two-step confirm arrives as "select this row again,
    // then activate" -- if that cleared the arm, touch could never confirm
    // (it could not remove a games root without a controller, 2026-09-11).
    fun setSelected(index: Int) {
        if (selectionByDepth[depth] != index) confirmArmedId = null
        selectionByDepth[depth] = index
    }

    var pendingDocumentPick by remember { mutableStateOf<DocumentPickItem?>(null) }
    val documentPickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val item = pendingDocumentPick
        pendingDocumentPick = null
        val uri = result.data?.data
        if (uri == null || item == null) return@rememberLauncherForActivityResult
        // onPicked reads or writes the document: never on the main thread.
        statusById[item.id] = "Working..."
        scope.launch {
            statusById[item.id] = withContext(Dispatchers.IO) { item.onPicked(context, uri) }
            refresh()
        }
    }

    val folderPickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val item = pendingFolderPick
        pendingFolderPick = null
        if (uri == null || item == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val error = item.onPicked(context, uri)
        if (error != null) statusById[item.id] = error else statusById.remove(item.id)
        refresh()
    }

    fun pop() {
        when {
            stack.size > 1 -> {
                stack.removeAt(stack.lastIndex)
                refresh()
            }
            else -> onExit()
        }
    }

    fun adjust(item: CatalogItem, direction: Int) {
        if (adjustCatalogItem(context, item, direction)) refresh()
    }

    fun activate(item: CatalogItem) {
        if (item.id == SEARCH_ROW_ID) {
            searchOpen = true
            return
        }
        val native = nativeActions[item.id]
        if (native != null) {
            native()
            return
        }
        when (item) {
            is ToggleItem -> adjust(item, +1)
            // Small sets cycle in place; big ones (a 100-system picker)
            // get a real selection list.
            is ChoiceItem -> if (item.options.size <= 6) adjust(item, +1) else pickingChoice = item
            is SliderItem -> {}
            is TextInputItem -> editingText = item
            is FolderPickItem -> {
                pendingFolderPick = item
                folderPickLauncher.launch(null)
            }
            is DocumentPickItem -> {
                pendingDocumentPick = item
                documentPickLauncher.launch(item.pickerIntent())
            }
            is ActionItem -> {
                if (item.confirmTitle != null && confirmArmedId != item.id) {
                    confirmArmedId = item.id
                    return
                }
                confirmArmedId = null
                item.run(context)
                refresh()
            }
            is AsyncActionItem -> {
                if (item.confirmTitle != null && confirmArmedId != item.id) {
                    confirmArmedId = item.id
                    return
                }
                confirmArmedId = null
                statusById[item.id] = "Working..."
                scope.launch {
                    statusById[item.id] = withContext(Dispatchers.IO) {
                        runCatching { item.run(context) { status -> statusById[item.id] = status } }
                            .getOrElse { "Failed: ${it.message}" }
                    }
                    refresh()
                }
            }
            is NestedScreenItem -> {
                val child = item.resolve()
                if (child != null) {
                    scrollByDepth[stack.lastIndex] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                    stack.add(child)
                    selectionByDepth[stack.lastIndex] = 0
                    refresh()
                } else {
                    statusById[item.id] = "Screen unavailable"
                }
            }
            is SubScreenItem -> context.startActivity(item.launchIntent(context))
        }
    }

    BackHandler { if (searchOpen) { searchOpen = false; searchQuery = "" } else pop() }

    if (searchOpen) {
        SettingsSearchOverlay(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            results = remember(searchIndex, searchQuery) { SettingsSearchIndex.search(searchIndex, searchQuery) },
            onPick = { result ->
                searchOpen = false
                searchQuery = ""
                if (result.target != screen) {
                    scrollByDepth[stack.lastIndex] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                    stack.add(result.target)
                    selectionByDepth[stack.lastIndex] = 0
                    refresh()
                }
            },
            onClose = { searchOpen = false; searchQuery = "" },
        )
        return
    }

    // Modal overlays render INSTEAD of the list so their own input wins.
    val textItem = editingText
    if (textItem != null) {
        TextEditDialog(
            title = textItem.title,
            subtitle = textItem.subtitle,
            initial = textItem.value,
            multiline = textItem.multiline,
            secret = textItem.secret,
            onCommit = { newValue ->
                editingText = null
                // Refresh after the write returns, so the re-read sees it.
                scope.launch {
                    textItem.onChange(context, newValue)
                    refresh()
                }
            },
            onDismiss = { editingText = null },
        )
    }
    val choiceItem = pickingChoice
    if (choiceItem != null) {
        CatalogChoicePicker(
            item = choiceItem,
            onPick = { value ->
                choiceItem.onSelect(context, value)
                pickingChoice = null
                refresh()
            },
            onDismiss = { pickingChoice = null },
        )
        return
    }

    infoRow?.let { item ->
        CatalogInfoSheet(item = item, status = statusById[item.id], onDismiss = { infoRow = null })
    }

    val listFocus = remember { FocusRequester() }
    LaunchedEffect(screen, textItem == null, infoRow == null) {
        if (textItem == null && infoRow == null) requestFocusWhenAttached(listFocus, "Settings catalog")
    }
    LaunchedEffect(rows, selected) {
        if (rows.isEmpty()) return@LaunchedEffect
        // Back at a depth left for a sub-screen: exactly where it was.
        scrollByDepth.remove(depth)?.let { (index, offset) -> listState.scrollToItem(index, offset) }
        listState.keepInView(selected.coerceIn(0, rows.lastIndex))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (stack.size > 1 || screen.subtitle != null) {
            MenuHeader(screen.title, screen.subtitle)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(listFocus)
                .focusable()
                .onKeyEvent { event ->
                    // B and Escape leave a screen the same way the system
                    // Back button does, on the UP edge like every other
                    // screen in the shell, with the DOWN edge consumed.
                    // Deliberately NOT Key.Back: that one is delivered
                    // through the back DISPATCHER (the BackHandler above),
                    // and handling it here as well would pop twice.
                    // Without this a pad whose B reports as
                    // KEYCODE_BUTTON_B had no way out of a settings screen
                    // at all, and neither did a keyboard. Popping on DOWN
                    // popped twice anyway: the UP went on to the shell's
                    // pad owner (Modifier.ownPadButtons), which sent Back
                    // again, so B from Settings > Android settings left
                    // Settings for the Games carousel (UI pass 2026-09-24,
                    // H3).
                    if (event.key == Key.ButtonB || event.key == Key.Escape) {
                        if (event.type == KeyEventType.KeyUp) pop()
                        return@onKeyEvent true
                    }
                    // Y: the selected row's Info sheet, on the UP edge like
                    // B, with the DOWN edge consumed so it goes nowhere else.
                    if (GamepadKeyMap.actionFor(event.key) == GamepadAction.Y) {
                        if (event.type == KeyEventType.KeyUp) rows.getOrNull(selected)?.let { infoRow = it.item }
                        return@onKeyEvent true
                    }
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            setSelected((selected + 1).coerceAtMost(rows.lastIndex))
                            true
                        }
                        Key.DirectionUp -> {
                            setSelected((selected - 1).coerceAtLeast(0))
                            true
                        }
                        Key.DirectionLeft -> {
                            rows.getOrNull(selected)?.let { adjust(it.item, -1) }
                            true
                        }
                        Key.DirectionRight -> {
                            rows.getOrNull(selected)?.let { adjust(it.item, +1) }
                            true
                        }
                        Key.ButtonA, Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                            rows.getOrNull(selected)?.let { activate(it.item) }
                            true
                        }
                        else -> false
                    }
                },
            contentPadding = MenuListContentPadding,
            verticalArrangement = Arrangement.spacedBy(MenuTokens.RowSpacing),
        ) {
            itemsIndexed(rows) { index, row ->
                Column {
                    row.headerAbove?.let { header -> MenuSectionLabel(header) }
                    CatalogRowView(
                        row = row,
                        isSelected = index == selected,
                        confirmArmed = confirmArmedId == row.item.id,
                        status = statusById[row.item.id],
                        onClick = {
                            setSelected(index)
                            activate(row.item)
                        },
                        onLongClick = {
                            setSelected(index)
                            infoRow = row.item
                        },
                        // The touch route to Left/Right. A SliderItem
                        // does nothing at all on activate (there is no
                        // "open" for a number), so without this it was
                        // pad-only in a screen a phone user has to use.
                        onAdjust = { direction ->
                            setSelected(index)
                            adjust(row.item, direction)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Gaming's Settings section: the Gaming settings catalog rendered by
 * [CatalogNavigator], with the shell's renderer-native fulfillments
 * (Browse themes opens
 * [ThemeBrowserScreen] inline, also reachable by deep link via
 * [browseThemesToken]).
 */
@Composable
internal fun SettingsCatalogView(
    onBack: () -> Unit,
    browseThemesToken: Int = 0,
    onHelpRowClaim: (HelpRowClaim) -> Unit = {},
) {
    var browseThemes by remember { mutableStateOf(false) }
    // Browse themes draws its own hint row (its A is "Download or update"),
    // so while it is up the shell's row stands down: both were drawn,
    // stacked (rig, dq-shell2-02). One row per screen (SPEC 7j).
    LaunchedEffect(browseThemes) {
        onHelpRowClaim(if (browseThemes) HelpRowClaim.SCREEN else HelpRowClaim.NONE)
    }

    LaunchedEffect(browseThemesToken) {
        if (browseThemesToken > 0) browseThemes = true
    }

    if (browseThemes) {
        ThemeBrowserScreen(onDismiss = { browseThemes = false })
        return
    }

    val root = remember {
        CatalogScreen(
            id = "gaming_settings",
            title = "Settings",
            groups = { ctx -> GamingSettingsCatalog.settingsGroups(ctx) },
        )
    }
    CatalogNavigator(
        root = root,
        onExit = onBack,
        nativeActions = mapOf(
            GamingSettingsCatalog.ID_BROWSE_THEMES to { browseThemes = true },
        ),
        showSearch = true,
    )
}

/**
 * Apply a value change to a catalog item -- the ONE definition of what a
 * Left/Right (or a tile press that cycles) means: choices wrap through
 * their options, sliders step within their range and clamp, toggles
 * flip. Returns whether anything actually changed, so a surface knows
 * whether to rebuild. Shared by the settings list and the Quick Menu's
 * tile grid ([QuickSettingsPanel]) rather than written once per surface.
 */
internal fun adjustCatalogItem(context: Context, item: CatalogItem, direction: Int): Boolean = when (item) {
    is ChoiceItem -> {
        if (item.options.isEmpty()) {
            false
        } else {
            val index = item.options.indexOfFirst { it.value == item.current }
            val size = item.options.size
            val next = item.options[((index + direction) % size + size) % size]
            item.onSelect(context, next.value)
            true
        }
    }
    is SliderItem -> {
        val next = (item.current + direction).coerceIn(item.min, item.max)
        if (next == item.current) {
            false
        } else {
            item.onChange(context, next)
            true
        }
    }
    is ToggleItem -> {
        item.onToggle(context, !item.current)
        true
    }
    else -> false
}

private data class CatalogRow(val item: CatalogItem, val headerAbove: String?)

/** The synthetic root-level row that opens search -- never a real catalog id. */
private const val SEARCH_ROW_ID = "__settings_search__"

@Composable
private fun CatalogRowView(
    row: CatalogRow,
    isSelected: Boolean,
    confirmArmed: Boolean,
    status: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onAdjust: ((Int) -> Unit)? = null,
) {
    val item = row.item
    val context = LocalContext.current
    // A chevron means "opens"; a value means "is set to". Keeping them
    // separate is what stopped nested screens rendering a chevron in
    // the value column.
    val chevron = item is NestedScreenItem || item is SubScreenItem
    val value = when (item) {
        is ChoiceItem -> item.currentLabel()
        is ToggleItem -> if (item.current) "On" else "Off"
        is SliderItem -> item.current.toString()
        is TextInputItem -> if (item.secret && item.value.isNotEmpty()) "••••" else item.value.ifEmpty { null }
        is NestedScreenItem -> item.valueLabel?.invoke(context)
        // The kinds with no state of their own carry it themselves
        // (CatalogItem.value): Network's connection, VPN's on/off, a
        // quick setting that is waiting on a permission.
        else -> item.value
    }
    val placeholder = value == null && item is TextInputItem
    MenuRow(
        title = if (confirmArmed) "${item.title}: press A again to confirm" else item.title,
        subtitle = status ?: item.subtitle,
        value = value ?: if (placeholder) "not set" else null,
        placeholder = placeholder,
        adjustable = (item is ChoiceItem && item.options.size <= 6) || item is ToggleItem || item is SliderItem,
        chevron = chevron,
        selected = isSelected,
        danger = confirmArmed,
        accent = (item as? NestedScreenItem)?.accent?.let { Color(it) },
        icon = item.icon,
        onClick = onClick,
        onLongClick = onLongClick,
        onAdjust = onAdjust,
    )
}

/**
 * A settings row in full: its name, what it is set to, and every word of
 * its explanation, which the row itself cuts to one line. Opened by Y on
 * the row, or a long press; closed by B, A or Y.
 */
@Composable
private fun CatalogInfoSheet(item: CatalogItem, status: String?, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        MenuPanel(
            modifier = Modifier.width(LocalShellWindow.current.panelWidth(520.dp)),
            focusLabel = "Settings info",
            onKey = { event ->
                val action = GamepadKeyMap.actionFor(event.key)
                val closes = action == GamepadAction.B || action == GamepadAction.BACK ||
                    action == GamepadAction.A || action == GamepadAction.Y || event.key == Key.Escape
                if (closes && event.type == KeyEventType.KeyUp) onDismiss()
                closes
            },
        ) {
            Text(item.title, color = MenuTokens.OnSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            val value = when (item) {
                is ChoiceItem -> item.currentLabel()
                is ToggleItem -> if (item.current) "On" else "Off"
                is SliderItem -> item.current.toString()
                else -> item.value
            }
            value?.let { Text(it, color = MenuTokens.Value, style = MaterialTheme.typography.bodyMedium) }
            item.subtitle?.let {
                Text(it, color = MenuTokens.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
            status?.let { Text(it, color = MenuTokens.Value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            TouchHintBar(
                hints = listOf(GamepadAction.B to "Close"),
                background = Color.Transparent,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Full-screen option list for large [ChoiceItem]s (system pickers etc.). */
@Composable
internal fun CatalogChoicePicker(
    item: ChoiceItem,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(item.options.indexOfFirst { it.value == item.current }.coerceAtLeast(0)) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus, "Choice picker") }
    LaunchedEffect(selected) { if (item.options.isNotEmpty()) listState.keepInView(selected) }
    BackHandler { onDismiss() }

    Column(Modifier.fillMaxSize()) {
        MenuHeader(item.title)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            selected = (selected + 1).coerceAtMost(item.options.lastIndex)
                            true
                        }
                        Key.DirectionUp -> {
                            selected = (selected - 1).coerceAtLeast(0)
                            true
                        }
                        Key.ButtonA, Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                            item.options.getOrNull(selected)?.let { onPick(it.value) }
                            true
                        }
                        else -> false
                    }
                },
            contentPadding = MenuListContentPadding,
            verticalArrangement = Arrangement.spacedBy(MenuTokens.RowSpacing),
        ) {
            itemsIndexed(item.options) { index, option ->
                MenuRow(
                    title = option.label,
                    selected = index == selected,
                    onClick = { onPick(option.value) },
                )
            }
        }
    }
}

/**
 * The shell's one text-entry dialog: a settings row's value, and anything
 * else a person types or pastes (an F95zone thread link on a game's
 * screen). Paste is there because what gets typed here is usually copied
 * from somewhere else.
 */
@Composable
internal fun TextEditDialog(
    title: String,
    subtitle: String?,
    initial: String,
    onCommit: (String) -> Unit,
    onDismiss: () -> Unit,
    multiline: Boolean = false,
    secret: Boolean = false,
) {
    var value by remember(title, initial) { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(MenuTokens.OverlayShape)
                .background(MenuTokens.OverlaySurface)
                .padding(20.dp),
        ) {
            Text(title, color = MenuTokens.OnSurface, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(it, color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = !multiline,
                // The keyboard's own Done key saves a one-line value: the
                // on-screen keyboard can cover the dialog's Save button
                // (rig, dq-desk2-02, a container rename in landscape).
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = if (multiline) androidx.compose.ui.text.input.ImeAction.Default else androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onCommit(value) }),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MenuTokens.OnSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MenuTokens.SurfaceSelected)
                    .padding(12.dp),
            )
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { value = it }
                }) { Text("Paste", color = MenuTokens.Accent) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel", color = MenuTokens.OnSurfaceMuted) }
                TextButton(onClick = { onCommit(value) }) { Text("Save", color = MenuTokens.Accent) }
            }
        }
    }
}

/**
 * Settings home only (docs/SPEC.md settings architecture, "search across
 * settings"): a text field and, once two characters are typed, the
 * matching rows from [SettingsSearchIndex], each showing which screen it
 * lives on. Picking one navigates there -- the same [MenuRow] anatomy and
 * the same A/B/touch input as the list it replaces, so this is one more
 * screen in the shell's own language rather than a second search UI.
 */
@Composable
private fun SettingsSearchOverlay(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<SettingsSearchResult>,
    onPick: (SettingsSearchResult) -> Unit,
    onClose: () -> Unit,
) {
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(fieldFocus, "Settings search") }
    Column(modifier = Modifier.fillMaxSize()) {
        MenuHeader("Search settings", "Type a setting's name")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalShellWindow.current.edgePadding, vertical = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MenuTokens.SurfaceSelected)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MenuTokens.OnSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MenuTokens.Accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(fieldFocus)
                    .onKeyEvent { event ->
                        // Escape closes search from the field itself --
                        // BackHandler alone does not see key events
                        // consumed by a focused text field.
                        if (event.key == Key.Escape && event.type == KeyEventType.KeyUp) {
                            onClose()
                            true
                        } else {
                            false
                        }
                    },
            )
            if (query.isNotEmpty()) {
                Text(
                    "Clear",
                    color = MenuTokens.Accent,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .clickable { onQueryChange("") },
                )
            }
        }
        when {
            query.trim().length < 2 ->
                Text(
                    "Keep typing -- at least two letters",
                    color = MenuTokens.OnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = LocalShellWindow.current.edgePadding, vertical = 12.dp),
                )
            results.isEmpty() ->
                Text(
                    "No settings match \"$query\"",
                    color = MenuTokens.OnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = LocalShellWindow.current.edgePadding, vertical = 12.dp),
                )
            else -> {
                var selected by remember { mutableStateOf(0) }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> { selected = (selected + 1).coerceAtMost(results.lastIndex); true }
                                Key.DirectionUp -> { selected = (selected - 1).coerceAtLeast(0); true }
                                Key.ButtonA, Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                                    results.getOrNull(selected)?.let(onPick)
                                    true
                                }
                                else -> false
                            }
                        },
                    contentPadding = MenuListContentPadding,
                    verticalArrangement = Arrangement.spacedBy(MenuTokens.RowSpacing),
                ) {
                    itemsIndexed(results) { index, result ->
                        MenuRow(
                            title = result.itemTitle,
                            subtitle = "In ${result.screenTitle}" + (result.itemSubtitle?.let { " -- $it" } ?: ""),
                            icon = result.icon,
                            chevron = true,
                            selected = index == selected,
                            onClick = { selected = index; onPick(result) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Scrolls only as far as it takes to show row [index] whole, and not at all
 * when it already is. Following the selection with `animateScrollToItem`
 * put the selected row at the TOP of the list on every change, a tap
 * included, so the rows moved under the finger and a second tap landed on
 * a different row (rig, dq-coordinator-23 F11 and dq-shell2-01: two
 * settings changed by accident).
 */
internal suspend fun androidx.compose.foundation.lazy.LazyListState.keepInView(index: Int) {
    val info = layoutInfo
    val row = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (row == null) {
        // Off screen: a pad moving past the edge, or a restored selection.
        animateScrollToItem(index)
        return
    }
    val top = info.viewportStartOffset
    val bottom = info.viewportEndOffset - info.afterContentPadding
    when {
        row.offset < top -> animateScrollBy((row.offset - top).toFloat())
        row.offset + row.size > bottom -> animateScrollBy((row.offset + row.size - bottom).toFloat())
    }
}
