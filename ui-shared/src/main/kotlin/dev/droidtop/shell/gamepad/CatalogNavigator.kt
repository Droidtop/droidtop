package dev.droidtop.shell.gamepad

import androidx.compose.ui.res.stringResource
import dev.droidtop.runtime.FailureText
import dev.droidtop.shell.gamepad.input.PressWords
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.menuStep
import dev.droidtop.shell.gamepad.input.onPad
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.ButtonBarItem
import dev.droidtop.library.settings.ButtonRole
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.PageButton
import dev.droidtop.library.settings.PageHeaderItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogChip
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogIcon
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.FolderPickItem
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.NoticeItem
import dev.droidtop.library.settings.NoticePlacement
import dev.droidtop.library.settings.NoticePress
import dev.droidtop.library.settings.SettingsSearchIndex
import dev.droidtop.library.settings.SettingsSearchResult
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.library.settings.SliderItem
import dev.droidtop.library.settings.SubScreenItem
import dev.droidtop.library.settings.TextBlockItem
import dev.droidtop.library.settings.isInert
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.library.settings.confirmText
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.PadModality
import dev.droidtop.shell.gamepad.input.PadPress
import dev.droidtop.shell.gamepad.input.declaresHints
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import dev.droidtop.shell.gamepad.theme.UiSound
import dev.droidtop.ui.shared.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.droidtop.ui.shared.ButtonBarView
import dev.droidtop.ui.shared.FocusLine
import dev.droidtop.ui.shared.LocalNoticePalette
import dev.droidtop.ui.shared.NoticeCard
import dev.droidtop.ui.shared.PageColors
import dev.droidtop.ui.shared.PageHeaderView
import dev.droidtop.ui.shared.R

/**
 * The in-shell (gamepad-first, dark-chrome) renderer for the shared
 * settings catalogs (docs/SPEC.md settings architecture): catalogs own
 * which settings exist, their layout and their write paths; this
 * navigator just chromes them -- including nested management screens
 * ([CatalogScreen]: console systems, platform CRUD, ROM folders, scraper
 * credentials), which push onto a real nav stack in the same visual
 * language instead of bouncing to differently-styled activities.
 *
 * Layout (docs/SPEC.md "Settings layout"): a navigator that fills the
 * page and whose root has three or more categories ([settingsCategories])
 * is two panes, the category column on the left (about 30% of the width,
 * the current category marked, search at its top) and that category's
 * rows on the right; a screen a row opens replaces the pane, never the
 * column. Narrower than [TWO_PANE_MIN_WIDTH] (portrait) the column and
 * the pane take turns. A navigator inside a sheet or a dialog, or over a
 * root with fewer categories, is the one list it always was.
 *
 * Input: Up/Down move the selection, A activates (toggles, opens
 * pickers/nested screens/text editors, runs actions), B pops one level
 * (from the pane's first level, back to the column) and exits at the
 * root. Left/Right step a slider or a short choice in place; on any other
 * row Left moves to the category column and Right from the column moves
 * into the pane. Without a column, Left/Right adjust every adjustable row
 * as before. Touch works on every row and every category too.
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
    // The places the left menu already has (Settings only): a screen opened here leaves out its links to them.
    placeScreenIds: Set<String> = emptySet(),
) {
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }
    // Both place-holding states below are saveable because the Activity
    // recreate the Text size setting triggers (AccessibilityPrefs,
    // Droidtop/tracker#87) used to rebuild them from nothing: the user
    // changing their text size inside a pushed settings screen landed
    // back on this root with row 0 selected, their place gone.
    val stack = rememberSaveable(saver = catalogStackSaver(root)) { mutableStateListOf(root) }
    // Selection is per-depth so popping restores where the user was.
    val selectionByDepth = rememberSaveable(saver = selectionByDepthSaver) { mutableStateMapOf<Int, Int>() }
    // And so is the scroll: one list shows every depth, so coming back
    // from a sub-screen found the list where the sub-screen had left it
    // and scrolled the restored row to the top, and the row under the
    // finger was no longer the one below the row just used (rig,
    // dq-shell2-02: the Keyboard picker opened instead of Android settings).
    val scrollByDepth = remember { mutableStateMapOf<Int, Pair<Int, Int>>() }
    val listState = rememberLazyListState()
    val columnState = rememberLazyListState()
    // Live status text per item id (async progress/outcomes, pick errors).
    val statusById = remember { mutableStateMapOf<String, String>() }
    // The button under the cursor on a page's bar (docs/SPEC.md 7k, "Page anatomy"), per depth like the row.
    val barSlotByDepth = remember { mutableStateMapOf<Int, Int>() }
    // A page's "More": its overflow buttons as a picker; and a button that asks first, as Cancel-first choices.
    var moreBar by remember { mutableStateOf<ButtonBarItem?>(null) }
    var confirmingButton by remember { mutableStateOf<PageButton?>(null) }
    // Read-gates (TextBlockItem.gate): true once the text was scrolled to its end or READ_GATE_MS after it was first shown.
    val readGates = remember { mutableStateMapOf<String, Boolean>() }
    val gateTimers = remember { mutableSetOf<String>() }
    // Two-step confirm: the armed destructive item, reset on any move.
    var confirmArmedId by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf<TextInputItem?>(null) }
    var pickingChoice by remember { mutableStateOf<ChoiceItem?>(null) }
    // Y's Info sheet: the selected row's whole text. Rows show their
    // name and value; this is where the rest of it is.
    var infoRow by remember { mutableStateOf<CatalogItem?>(null) }
    var pendingFolderPick by remember { mutableStateOf<FolderPickItem?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    // null until the one-time index build below lands: the search overlay
    // reads null as "still indexing", not "no matches" -- the first search
    // straight after opening "Search settings" raced the build and answered
    // "No settings match" over an empty index.
    var searchIndex by remember { mutableStateOf<List<SettingsSearchResult>?>(null) }
    // A search result's row id (docs/SPEC.md 7k, "picking a search result
    // scrolls its screen ... and gives it initial focus"): set on pick,
    // and consumed once the CURRENT screen's rows actually contain it --
    // immediately for a result on the screen already showing, and once a
    // freshly pushed screen's groups() finish loading for one that isn't
    // (rig, dq-settingsui-02: a picked result landed on the right screen
    // with focus back at "Search settings" instead of on the row found).
    var pendingFocusId by remember { mutableStateOf<String?>(null) }
    // The category column (docs/SPEC.md "Settings layout"): which category
    // the pane shows, whether the pad is in the column or the pane, and
    // whether the column's cursor is on its search entry. Saveable for the
    // same recreate as the stack above.
    var categoryKey by rememberSaveable { mutableStateOf<String?>(null) }
    var inColumn by rememberSaveable { mutableStateOf(true) }
    var columnOnSearch by rememberSaveable { mutableStateOf(false) }
    // This navigator's own width, measured: the column is for a navigator
    // that fills the page, never for one in a sheet or a dialog.
    var hostWidth by remember { mutableStateOf(0.dp) }
    val scope = rememberCoroutineScope()

    val depth = stack.lastIndex
    LaunchedEffect(searchOpen) {
        if (searchOpen && searchIndex == null) {
            searchIndex = withContext(Dispatchers.IO) { SettingsSearchIndex.build(context, root) }
        }
    }
    // The root's rows, built once per change: they are the category
    // column, and the pane's rows for a plain category or a navigator
    // without a column. Suspend builder (real screens run Room queries /
    // filesystem walks), rebuilt after every value change.
    val rootGroups by produceState<List<CatalogGroup>?>(null, root, version, refreshKey) {
        value = withOfflineNotice(root, context, mergeShortScreens(root.groups(context)) { it.groups(context) })
    }
    val categories = remember(rootGroups, root) {
        rootGroups?.let { settingsCategories(it, root.title, nativeActions.keys, root.categoryOrder) }.orEmpty()
    }
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val fillsPage = hostWidth >= screenWidth * PAGE_WIDTH_FRACTION
    val categoryMode = fillsPage && categories.size >= MIN_SETTINGS_CATEGORIES
    // Side by side only on a landscape window: held upright the two take turns, however wide the
    // window is (a 1080x1920 tablet is 720dp wide, which once put the column beside the pane).
    val twoPane = categoryMode && hostWidth >= TWO_PANE_MIN_WIDTH && !LocalShellWindow.current.portrait
    val categoryIndex = categories.indexOfFirst { it.key == categoryKey }.coerceAtLeast(0)
    val category = if (categoryMode) categories.getOrNull(categoryIndex) else null
    // A linked category's screen, resolved once per category: an inline
    // screen is rebuilt with every root build, and the pane must not
    // reload for that alone.
    val linkScreens = remember(root) { mutableMapOf<String, CatalogScreen?>() }
    fun linkScreen(of: SettingsCategory): CatalogScreen? =
        of.link?.let { link -> linkScreens.getOrPut(of.key) { link.resolve() } }
    // What the pane shows: a pushed screen, else the category's linked
    // screen, else the root's own rows.
    val screen: CatalogScreen = if (depth > 0) stack.last() else category?.let { linkScreen(it) } ?: root
    // Tagged with the screen they belong to: right after a push or a pop
    // the list must not treat the previous screen's rows as this one's.
    val paneLoaded by produceState<Pair<CatalogScreen, List<CatalogGroup>>?>(null, screen, version, refreshKey) {
        if (screen === root) return@produceState
        // A linked category loads once the column's cursor rests on it, so
        // running down the column does not build every screen it passes.
        if (depth == 0) delay(PANE_LOAD_SETTLE_MS)
        // A screen inside Settings (Accounts and sources) does not repeat a place the left menu has either.
        value = screen to withOfflineNotice(screen, context, withoutPlaceLinks(mergeShortScreens(screen.groups(context)) { it.groups(context) }, placeScreenIds))
    }
    // A new feature that lives on this screen is explained once it is open, as the shell's confirm card
    // (docs/SPEC.md 7b, "Update onboarding"). A linked category waits, as its rows do, for the cursor to rest on it.
    LaunchedEffect(screen.id) {
        if (depth == 0) delay(PANE_LOAD_SETTLE_MS)
        dev.droidtop.library.settings.FeatureSteps.showAt(context, screen.id)
    }
    // A screen still building (the stores' list, a plugin's panel, which asks the plugin when it opens)
    // shows a loading row from the first frame instead of its title on black, after a moment so a fast one
    // does not flash it (Droidtop/tracker#502).
    val loadingPending = if (screen === root) rootGroups == null else paneLoaded?.first !== screen
    val loadingShown by produceState(false, screen, loadingPending) {
        value = false
        if (loadingPending) {
            delay(LOADING_SHOW_MS)
            value = true
        }
    }
    val groups = remember(screen, rootGroups, paneLoaded, category?.key, loadingShown) {
        when {
            // A category's screen is built after the column's cursor has rested on it; until then the pane says so
            // instead of standing blank (rig, build 1715: a store row showed nothing until Right or a tap).
            screen !== root -> paneLoaded?.takeIf { it.first === screen }?.second
                ?: if (depth == 0 || loadingShown) loadingGroups(screen.title) else emptyList()
            // A plain category: its groups. One group is unheaded (the column names it); several keep
            // their titles as section labels, except a title that repeats the category's own name.
            category != null -> rootGroups.orEmpty().filter { it.id in category.groupIds }
                .map { if (it.title == category.label || category.groupIds.size == 1) it.copy(title = null) else it }
            else -> rootGroups ?: if (loadingShown) loadingGroups(root.title) else emptyList()
        }
    }
    val rows = remember(groups, showSearch, depth, categoryMode) {
        val built = groups.flatMap { group ->
            group.items.mapIndexed { index, item ->
                CatalogRow(
                    item,
                    headerAbove = if (index == 0) group.title else null,
                    chipsAbove = if (index == 0) group.chips else emptyList(),
                )
            }
        }
            // One key per row in the list below: a merged screen must not
            // repeat a row the pane already has.
            .distinctBy { it.item.id }
        // With a column, search is the column's first entry instead.
        if (showSearch && depth == 0 && !categoryMode) {
            listOf(
                CatalogRow(
                    ActionItem(
                        id = SEARCH_ROW_ID,
                        title = "Search settings",
                        icon = CatalogIcon.SEARCH,
                    ),
                    headerAbove = null,
                ),
            ) + built
        } else {
            built
        }
    }
    // A notice with nothing to press or read is passed over (docs/SPEC.md 7k, "Notices"), so the cursor
    // never rests where A does nothing.
    // A page's header is never a stop: the cursor opens on its button bar (docs/SPEC.md 7k, "Page anatomy").
    fun isStop(index: Int): Boolean = when (val item = rows.getOrNull(index)?.item) {
        is NoticeItem -> item.notice.takesFocus()
        is PageHeaderItem -> false
        else -> true
    }
    fun nextStop(from: Int, direction: Int): Int? {
        var i = from + direction
        while (i in rows.indices) {
            if (isStop(i)) return i
            i += direction
        }
        return null
    }
    val selected = (selectionByDepth[depth] ?: 0).coerceIn(0, (rows.lastIndex).coerceAtLeast(0))
        .let { if (isStop(it)) it else nextStop(it, +1) ?: nextStop(it, -1) ?: it }

    fun refresh() {
        version++
    }

    val selectedBar = rows.getOrNull(selected)?.item as? ButtonBarItem
    val barSlot = (barSlotByDepth[depth] ?: selectedBar?.initialSlot() ?: 0)
        .coerceIn(0, (selectedBar?.slots?.lastIndex ?: 0).coerceAtLeast(0))

    // A screen of live data (the friends, a conversation) says when it changed; its rows are read
    // again then. The first value is what the screen was just built from, so only later ones count.
    LaunchedEffect(screen) { screen.live?.drop(1)?.collect { version++ } }

    // Coming back from another app's screen (Android's All files access page, a store page) re-reads the
    // rows, so a row that described what that screen changes shows the new state. The first resume is the
    // screen opening, which has just loaded the rows.
    var resumedOnce by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (resumedOnce) refresh() else resumedOnce = true
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
        statusById[item.id] = "Working..."
        scope.launch {
            val error = runCatching { item.onPicked(context, uri) }
                .getOrElse { "Couldn't share that folder: ${it.message ?: "an unknown error"}" }
            if (error != null) statusById[item.id] = error else statusById.remove(item.id)
            refresh()
        }
    }

    fun pop() {
        stack.lastOrNull()?.onLeave?.invoke()
        when {
            stack.size > 1 -> {
                stack.removeAt(stack.lastIndex)
                refresh()
            }
            else -> onExit()
        }
    }

    // B: a pushed screen pops; the pane's first level hands the pad back
    // to the column; the column (or a navigator without one) leaves.
    fun back() {
        EsDeNavigationSounds.play(UiSound.BACK)
        when {
            stack.size > 1 -> pop()
            categoryMode && !inColumn -> inColumn = true
            else -> pop()
        }
    }

    fun leavePushedScreens() {
        while (stack.size > 1) {
            stack.last().onLeave?.invoke()
            stack.removeAt(stack.lastIndex)
        }
    }

    // Another category: the pane shows it from its top, and whatever a
    // row of the last one had opened is left.
    fun chooseCategory(index: Int) {
        val target = categories.getOrNull(index) ?: return
        columnOnSearch = false
        if (target.key == category?.key) return
        leavePushedScreens()
        categoryKey = target.key
        confirmArmedId = null
        selectionByDepth[0] = 0
        scrollByDepth.clear()
        scope.launch { listState.scrollToItem(0) }
    }

    // L1/R1 step the categories from anywhere on the page, never wrapping, as they step a strip elsewhere (proposal
    // 6.H, Droidtop/tracker#644 slice 4).
    if (categoryMode) {
        OwnShoulders { step ->
            val next = menuStep(categoryIndex, categories.size, step)
            if (next != categoryIndex) {
                EsDeNavigationSounds.play(UiSound.TAB)
                chooseCategory(next)
            }
        }
    }

    fun adjust(item: CatalogItem, direction: Int) {
        if (item is ToggleItem) {
            EsDeNavigationSounds.play(if (item.current) UiSound.TOGGLE_OFF else UiSound.TOGGLE_ON)
            scope.launch {
                statusById[item.id] = "Working..."
                runCatching { item.onToggle(context, !item.current) }
                    .onFailure { statusById[item.id] = "Failed: ${it.message ?: "an unknown error"}" }
                if (statusById[item.id] == "Working...") statusById.remove(item.id)
                refresh()
            }
        } else if (adjustCatalogItem(context, item, direction)) {
            EsDeNavigationSounds.play(UiSound.SLIDER)
            refresh()
        } else {
            EsDeNavigationSounds.play(UiSound.BUMP)
        }
    }

    // A page button: a disabled one bumps (its label already says why), one that asks first arms on the
    // first press and runs on the second, as a row does; everything else runs and the page is re-read.
    fun pressButton(button: PageButton) {
        if (!button.enabled) {
            EsDeNavigationSounds.play(UiSound.BUMP)
            return
        }
        confirmArmedId = null
        if (button.confirmTitle != null) {
            confirmingButton = button
            return
        }
        button.run(context)
        refresh()
    }

    fun pressBar(bar: ButtonBarItem, slot: Int) {
        val button = bar.slots.getOrNull(slot)
        if (button != null) pressButton(button) else if (bar.more.isNotEmpty()) moreBar = bar
    }

    fun moveBar(direction: Int, repeat: Boolean) {
        val bar = selectedBar ?: return
        val next = barSlot + direction
        val moved = next in bar.slots.indices
        moveCue(moved, repeat)
        if (moved) barSlotByDepth[depth] = next
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
            // A and a tap open the choices, never change the value by
            // themselves (rig, build 1649: a tap on a selector cycled it, so
            // looking at a setting changed it). Left/Right, and the arrows a
            // touch screen draws on a short choice, still step it in place.
            is ChoiceItem -> if (item.options.isNotEmpty()) pickingChoice = item
            is SliderItem -> {}
            is TextInputItem -> editingText = item
            is FolderPickItem -> {
                pendingFolderPick = item
                folderPickLauncher.launch(null)
            }
            is DocumentPickItem -> {
                if (item.confirmTitle != null && confirmArmedId != item.id) {
                    confirmArmedId = item.id
                    return
                }
                confirmArmedId = null
                pendingDocumentPick = item
                documentPickLauncher.launch(item.pickerIntent())
            }
            is TextBlockItem, is PageHeaderItem -> {}
            is ButtonBarItem -> pressBar(item, barSlot)
            // A notice's action, else its Info (Y is the other way there), else nothing.
            is NoticeItem -> when (item.notice.aPress()) {
                NoticePress.ACTION -> if (item.opens != null) {
                    scrollByDepth[stack.lastIndex] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                    stack.add(item.opens ?: return)
                    selectionByDepth[stack.lastIndex] = 0
                    refresh()
                } else item.notice.action?.let { action ->
                    scope.launch {
                        action.run(context)
                        refresh()
                    }
                }
                NoticePress.INFO -> infoRow = item
                NoticePress.NONE -> {}
            }
            is ActionItem -> {
                // A fact or a state: A promises nothing on it, and does nothing (Droidtop/tracker#587).
                if (item.inert) return
                if (item.confirmTitle != null && confirmArmedId != item.id) {
                    confirmArmedId = item.id
                    return
                }
                confirmArmedId = null
                item.run(context)
                refresh()
            }
            is AsyncActionItem -> {
                val gate = item.gate
                if (gate != null && readGates[gate] != true) {
                    EsDeNavigationSounds.play(UiSound.BUMP)
                    return
                }
                if (item.confirmTitle != null && confirmArmedId != item.id) {
                    confirmArmedId = item.id
                    return
                }
                confirmArmedId = null
                statusById[item.id] = "Working..."
                scope.launch {
                    statusById[item.id] = withContext(Dispatchers.IO) {
                        runCatching { item.run(context) { status -> statusById[item.id] = status } }
                            .getOrElse { "Failed: ${FailureText.of("Settings", it)}" }
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

    // A picked search result: with a column, a result on a category's own
    // screen opens that category (its rows are the pane's first level);
    // anything deeper is pushed onto the pane as before.
    fun openSearchResult(result: SettingsSearchResult) {
        if (categoryMode) {
            inColumn = false
            columnOnSearch = false
            val linked = categories.indexOfFirst { linkScreen(it)?.id == result.target.id }
            val index = if (linked >= 0) {
                linked
            } else if (result.target.id == root.id) {
                categories.indexOfFirst { cat ->
                    rootGroups.orEmpty().any { group -> group.id in cat.groupIds && group.items.any { it.id == result.itemId } }
                }
            } else {
                -1
            }
            if (index >= 0) {
                if (categories[index].key != category?.key) chooseCategory(index) else leavePushedScreens()
                return
            }
        }
        if (result.target != screen) {
            scrollByDepth[stack.lastIndex] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            stack.add(result.target)
            selectionByDepth[stack.lastIndex] = 0
            refresh()
        }
        // else: same screen, already showing -- the effect below moves the
        // selection there once this recomposes past the search overlay's
        // early return.
    }

    BackHandler { if (searchOpen) { searchOpen = false; searchQuery = "" } else back() }

    if (searchOpen) {
        SettingsSearchOverlay(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            index = searchIndex,
            onPick = { result ->
                searchOpen = false
                searchQuery = ""
                pendingFocusId = result.itemId
                openSearchResult(result)
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
            search = textItem.search,
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

    moreBar?.let { bar ->
        CatalogChoicePicker(
            item = ChoiceItem(
                id = "${ButtonBarItem.ID}_more",
                title = ButtonBarItem.MORE_LABEL,
                options = bar.more.map { ChoiceOption(it.id, it.shownLabel) },
                current = null,
                onSelect = { _, _ -> },
            ),
            onPick = { id ->
                moreBar = null
                bar.more.firstOrNull { it.id == id }?.let { pressButton(it) }
            },
            onDismiss = { moreBar = null },
        )
        return
    }
    // A button that asks first: its question, Cancel first and under the cursor, then the button (docs/SPEC.md 7k,
    // copy rule 9: in a modal the safe choice comes first).
    confirmingButton?.let { button ->
        CatalogChoicePicker(
            item = ChoiceItem(
                id = "${ButtonBarItem.ID}_confirm",
                title = button.confirmTitle.orEmpty(),
                options = listOf(ChoiceOption(CONFIRM_CANCEL, "Cancel"), ChoiceOption(button.id, button.label)),
                current = CONFIRM_CANCEL,
                onSelect = { _, _ -> },
            ),
            onPick = { picked ->
                confirmingButton = null
                if (picked == button.id) {
                    button.run(context)
                    refresh()
                }
            },
            onDismiss = { confirmingButton = null },
        )
        return
    }

    infoRow?.let { item ->
        CatalogInfoSheet(item = item, status = statusById[item.id], onDismiss = { infoRow = null })
    }

    // Resolves pendingFocusId (search-result focus, above) once the rows
    // that should contain it are the ones actually built: for the screen
    // search was opened FROM, that is immediately (rows already reflect
    // it); for a screen search just pushed, only once its own groups()
    // finish loading replace the placeholder empty list.
    LaunchedEffect(rows, pendingFocusId) {
        val id = pendingFocusId ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.item.id == id }
        if (index >= 0) {
            setSelected(index)
            pendingFocusId = null
        }
    }

    val navFocus = remember { FocusRequester() }
    LaunchedEffect(screen, textItem == null, infoRow == null) {
        if (textItem == null && infoRow == null) requestFocusWhenAttached(navFocus, "Settings catalog")
    }
    // A held direction follows the cursor without animating each step.
    var heldStep by remember { mutableStateOf(false) }
    LaunchedEffect(rows, selected) {
        if (rows.isEmpty()) return@LaunchedEffect
        // Back at a depth left for a sub-screen: exactly where it was.
        scrollByDepth.remove(depth)?.let { (index, offset) -> listState.scrollToItem(index, offset) }
        // Steam's room round the cursor on a page (a sheet's short list keeps the plain rule).
        listState.keepInView(selected.coerceIn(0, rows.lastIndex), animate = !heldStep, keepRoom = fillsPage)
    }
    // The column's cursor, as an index into its own list (search first).
    val searchEntries = if (showSearch) 1 else 0
    val columnCursor = if (columnOnSearch && showSearch) 0 else categoryIndex + searchEntries
    LaunchedEffect(categoryMode, columnCursor) {
        if (categoryMode) columnState.keepInView(columnCursor, animate = !heldStep)
    }

    // One value column for the whole screen, content-sized to the widest
    // value any row can show (docs/SPEC.md "Text in rows and tiles").
    val valueMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val valueStyle = MaterialTheme.typography.bodyMedium
    val valueDensity = LocalDensity.current
    val valueColumnWidth = remember(rows, valueStyle, valueDensity.fontScale) {
        val widest = rows.flatMap { catalogValueCandidates(it.item, context) }
            .maxOfOrNull { valueMeasurer.measure("‹ $it ›", valueStyle, maxLines = 1, softWrap = false).size.width } ?: 0
        with(valueDensity) { widest.toDp() }.coerceIn(MenuTokens.ValueColumnMinWidth, MenuTokens.ValueColumnMaxWidth)
    }

    // The column's pad: Up/Down choose a category (the pane follows), A or
    // Right go into the pane, A on the search entry opens search, B leaves.
    // Up from the top entry is not used, so it never reaches a header.
    fun onColumnPad(press: PadPress): Boolean {
        when (press.action) {
            GamepadAction.UP -> {
                heldStep = press.repeat
                when {
                    columnOnSearch -> return press.repeat.also { moveCue(false, press.repeat) }
                    categoryIndex == 0 && showSearch -> columnOnSearch = true
                    categoryIndex == 0 -> return press.repeat.also { moveCue(false, press.repeat) }
                    else -> chooseCategory(categoryIndex - 1)
                }
                moveCue(true, press.repeat)
            }
            GamepadAction.DOWN -> {
                heldStep = press.repeat
                val moved = columnOnSearch || categoryIndex < categories.lastIndex
                moveCue(moved, press.repeat)
                if (columnOnSearch) chooseCategory(0) else chooseCategory(categoryIndex + 1)
            }
            GamepadAction.A -> {
                EsDeNavigationSounds.play(UiSound.CONFIRM)
                if (columnOnSearch) searchOpen = true else inColumn = false
            }
            GamepadAction.RIGHT -> if (!columnOnSearch) inColumn = false
            GamepadAction.B -> back()
            else -> return false
        }
        return true
    }

    // The pane's (or the one list's) pad, through the input pipeline
    // (docs/SPEC.md 6e): each press acts once, on the press, and a held
    // direction runs at the chrome cadence. B (and a keyboard's Escape)
    // goes back a level like the system back key's BackHandler above; its
    // release belongs to this press, so it can no longer reach the shell's
    // root and pop a second level (UI pass 2026-09-24, H3).
    fun onPanePad(press: PadPress): Boolean {
        val row = rows.getOrNull(selected)
        when (press.action) {
            GamepadAction.B -> back()
            // Y: the selected row's Info sheet.
            GamepadAction.Y -> row?.let { infoRow = it.item }
            GamepadAction.DOWN -> {
                heldStep = press.repeat
                val next = nextStop(selected, +1)
                moveCue(next != null, press.repeat)
                if (next != null) setSelected(next)
            }
            // Owned only while the selection can really move: a fresh Up at
            // the first row reaches Compose's focus search, which in safe
            // mode finds the banner's action above (docs/SPEC.md 10c) and
            // otherwise finds nothing -- the top bar cannot take focus
            // (docs/SPEC.md 7j). A held Up stops at the first row.
            GamepadAction.UP -> {
                val previous = nextStop(selected, -1) ?: return press.repeat.also { moveCue(false, press.repeat) }
                heldStep = press.repeat
                moveCue(true, press.repeat)
                setSelected(previous)
            }
            GamepadAction.LEFT -> when {
                // Along a page's bar first; from its first button Left goes where it always went.
                row?.item is ButtonBarItem && barSlot > 0 -> moveBar(-1, press.repeat)
                categoryMode && (row == null || !row.item.stepsInPlace()) -> inColumn = true
                row?.item is ButtonBarItem -> moveCue(false, press.repeat)
                row != null -> adjust(row.item, -1)
            }
            GamepadAction.RIGHT -> when {
                row?.item is ButtonBarItem -> moveBar(+1, press.repeat)
                row != null && (!categoryMode || row.item.stepsInPlace()) -> adjust(row.item, +1)
            }
            GamepadAction.A -> row?.let {
                // A toggle and a stepped choice make their own cue (adjust); everything else confirms.
                if (it.item !is ToggleItem) EsDeNavigationSounds.play(UiSound.CONFIRM)
                activate(it.item)
            }
            else -> return false
        }
        return true
    }

    val paneActive = !categoryMode || !inColumn
    // A pushed screen names itself over the pane; a category's own rows
    // do not (the column names them). Without a column, as before: a
    // pushed screen or a root that describes itself.
    val paneTitle = when {
        categoryMode -> screen.title.takeIf { depth > 0 }
        stack.size > 1 || screen.subtitle != null -> screen.title
        else -> null
    }
    val density = LocalDensity.current
    // A notice under the cursor names only the presses it answers (docs/SPEC.md 7k, "Notices").
    val selectedNotice = (rows.getOrNull(selected)?.item as? NoticeItem)?.notice?.takeIf { paneActive }
    val noticeHints = remember(selectedNotice) {
        selectedNotice?.let { notice ->
            listOfNotNull(
                when (notice.aPress()) {
                    NoticePress.ACTION -> notice.action?.let { HintBinding(GamepadAction.A, it.label) }
                    NoticePress.INFO -> HintBinding(GamepadAction.A, "Info")
                    NoticePress.NONE -> null
                },
                HintBinding(GamepadAction.Y, "Info").takeIf { !notice.details.isNullOrBlank() && notice.action != null },
                HintBinding(GamepadAction.B, "Back"),
            )
        }
    }
    // On a page's bar the hint row names the button under the cursor (and More), never a generic Select.
    val barHints = remember(selectedBar, barSlot, paneActive) {
        selectedBar?.takeIf { paneActive }?.let { bar ->
            val button = bar.slots.getOrNull(barSlot)
            listOf(
                HintBinding(GamepadAction.A, button?.label ?: ButtonBarItem.MORE_LABEL) { button?.enabled ?: true },
                HintBinding(GamepadAction.B, "Back"),
            )
        }
    }
    val selectedRowItem = rows.getOrNull(selected)?.item?.takeIf { paneActive && it !is NoticeItem && it !is ButtonBarItem }
    val rowHints = remember(selectedRowItem) {
        selectedRowItem?.let { item ->
            listOfNotNull(
                HintBinding(GamepadAction.A, "Select").takeIf { !item.isInert },
                // A row that steps in place says so: "◄ ► Change" (Droidtop/tracker#519). Left's label is empty so
                // the pair reads as one hint, keycaps then the word.
                HintBinding(GamepadAction.LEFT, "").takeIf { item.stepsInPlace() },
                HintBinding(GamepadAction.RIGHT, "Change").takeIf { item.stepsInPlace() },
                HintBinding(GamepadAction.Y, "Info").takeIf { hasInfo(item) },
                HintBinding(GamepadAction.B, "Back"),
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { hostWidth = with(density) { it.width.toDp() } }
            .focusRequester(navFocus)
            .then((noticeHints ?: barHints ?: rowHints)?.let { Modifier.declaresHints(it) } ?: Modifier)
            .focusable()
            .onPad { press -> if (categoryMode && inColumn) onColumnPad(press) else onPanePad(press) },
    ) {
        val pane: @Composable (Modifier, Dp) -> Unit = { modifier, startPadding ->
            CatalogPane(
                title = paneTitle,
                // A plugin's panel: its icon before its title, its rows' subtitles one line each (tracker#515).
                titleIcon = screen.iconFile.takeIf { paneTitle != null },
                panel = screen.panel,
                hint = screen.subtitle,
                rows = rows,
                selected = selected,
                barSlot = barSlot,
                onBarPress = { index, slot ->
                    inColumn = false
                    setSelected(index)
                    barSlotByDepth[depth] = slot
                    (rows.getOrNull(index)?.item as? ButtonBarItem)?.let { pressBar(it, slot) }
                },
                active = paneActive,
                state = listState,
                confirmArmedId = confirmArmedId,
                statusById = statusById,
                readGates = readGates,
                onBlockShown = { block ->
                    val gate = block.gate
                    if (gate != null) {
                        if (block.last) readGates[gate] = true
                        if (gateTimers.add(gate)) scope.launch { delay(READ_GATE_MS); readGates[gate] = true }
                    }
                },
                startPadding = startPadding,
                onClick = { index ->
                    inColumn = false
                    setSelected(index)
                    rows.getOrNull(index)?.let { activate(it.item) }
                },
                onLongClick = { index ->
                    inColumn = false
                    setSelected(index)
                    // Reading about a row never leaves it armed: the next press after Info is a fresh first press.
                    confirmArmedId = null
                    rows.getOrNull(index)?.let { infoRow = it.item }
                },
                // The touch route to Left/Right. A SliderItem does nothing
                // at all on activate (there is no "open" for a number), so
                // without this it was pad-only in a screen a phone user has
                // to use.
                onAdjust = { index, direction ->
                    inColumn = false
                    setSelected(index)
                    rows.getOrNull(index)?.let { adjust(it.item, direction) }
                },
                modifier = modifier,
            )
        }
        val column: @Composable (Modifier) -> Unit = { modifier ->
            SettingsCategoryColumn(
                categories = categories,
                current = categoryIndex,
                cursor = columnCursor.takeIf { inColumn },
                showSearch = showSearch,
                state = columnState,
                onSearch = {
                    columnOnSearch = true
                    searchOpen = true
                },
                onPick = { index ->
                    chooseCategory(index)
                    // Side by side the pane is already showing it; taking
                    // turns, a tap on a category is the way into it.
                    inColumn = twoPane
                },
                modifier = modifier,
            )
        }
        val edge = LocalShellWindow.current.edgePadding
        CompositionLocalProvider(LocalValueColumnWidth provides valueColumnWidth) {
            when {
                !categoryMode -> pane(Modifier.fillMaxSize(), edge)
                twoPane -> Row(Modifier.fillMaxSize()) {
                    column(Modifier.fillMaxHeight().width(SettingsLayout.columnWidth(hostWidth)))
                    pane(Modifier.weight(1f).fillMaxHeight(), 16.dp)
                }
                inColumn -> column(Modifier.fillMaxSize())
                else -> pane(Modifier.fillMaxSize(), edge)
            }
        }
    }
}

/** The pane: an optional title, then the rows, grouped under small section labels. */
@Composable
private fun CatalogPane(
    title: String?,
    titleIcon: String? = null,
    panel: Boolean = false,
    hint: String?,
    rows: List<CatalogRow>,
    selected: Int,
    barSlot: Int,
    onBarPress: (Int, Int) -> Unit,
    active: Boolean,
    state: LazyListState,
    confirmArmedId: String?,
    statusById: Map<String, String>,
    readGates: Map<String, Boolean>,
    onBlockShown: (TextBlockItem) -> Unit,
    startPadding: Dp,
    onClick: (Int) -> Unit,
    onLongClick: (Int) -> Unit,
    onAdjust: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The selected row's explanation goes in a strip of its own under the list, never in a bubble over
    // the next row (Droidtop/tracker#495); armed, the row says what pressing again will do (the app and
    // the exact permission or file) in place of its hint, at once.
    // An armed row says what pressing again does under touch too: tapped, it used to arm with only "press A again" in
    // its title, so a person never saw the key fingerprint they were trusting, and the next tap on the row confirmed
    // (rig, build 1908, Droidtop/tracker#270).
    val tipRow = rows.getOrNull(selected)?.takeIf {
        active && (PadModality.showsFocus || (confirmArmedId == it.item.id && it.item.confirmText != null)) && it.item !is TextBlockItem
    }
    val tipArmed = tipRow != null && confirmArmedId == tipRow.item.id && tipRow.item.confirmText != null
    // With "Info text: Under each row" a row's sentence is on the row itself, and the focus line keeps only
    // what changes (a status, an armed confirmation); the screen's own explanation is the line's fallback.
    val context = LocalContext.current
    val underRows = remember(rows) { dev.droidtop.library.settings.ReadingPrefs.infoUnderRows(context) }
    // In a narrow pane (the Quick Menu) the focused row's sentence opens under that row, and the strip at the foot
    // keeps only what changes, so the footer is the pane's hints, not a paragraph (Droidtop/tracker#739).
    val narrow = LocalNarrowPane.current
    val tip = tipRow?.let { catalogTip(it.item, statusById[it.item.id], confirmArmedId == it.item.id, explanation = !underRows && !narrow) }
        ?: hint?.takeIf { active }
    val reserveTip = hint != null || statusById.isNotEmpty() || rows.any { (!underRows && !narrow && it.item.subtitle != null) || it.item.confirmText != null }
    Column(modifier) {
        if (title != null) MenuHeader(title, iconFile = titleIcon)
        LazyColumn(
            state = state,
            // The list ends above the explanation strip (when the page has any explanations) and
            // runs to the bottom of the page otherwise: no room left for a bar that is drawn below
            // this view anyway. A row the edge cuts fades out instead of ending in a slice.
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .fadingEdges(state),
            contentPadding = PaddingValues(
                start = startPadding,
                end = LocalShellWindow.current.edgePadding,
                // Without a title the first row starts at the window's top edge, under the
                // floating clock and battery (console, build 1386: the pill sat on "Use droidtop
                // as home screen"); it starts below the cluster instead (StatusClusterRoom).
                top = if (title == null) maxOf(12.dp, StatusClusterRoom.size.height) else 12.dp,
                bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(MenuTokens.RowSpacing),
        ) {
            // A stable key per row (docs/SPEC.md "Settings scrolling
            // polish", 2026-09-28): without one, every recomposition of
            // this list re-keys rows by POSITION, so Compose cannot tell
            // "row 6 is still Bluetooth" from "row 6 is now something
            // else" -- any state change anywhere in the list (a toggle
            // flipping, a status line appearing) treated every visible
            // row as a fresh item instead of the one that actually
            // changed, costing a full re-measure/re-layout pass on
            // scroll and losing per-row remembered state (MenuRow's own
            // selection-follow effect) on the way. A catalog item's own
            // id is already what every other mechanism here keys by
            // (search results, pending focus) -- one identity, reused.
            itemsIndexed(rows, key = { _, row -> row.item.id }) { index, row ->
                val isSelected = active && index == selected
                Column {
                    if (row.chipsAbove.isNotEmpty()) CatalogChipRow(row.chipsAbove)
                    row.headerAbove?.let { header -> MenuSectionLabel(header) }
                    val block = row.item as? TextBlockItem
                    val notice = row.item as? NoticeItem
                    val header = row.item as? PageHeaderItem
                    val bar = row.item as? ButtonBarItem
                    if (header != null) {
                        PageHeaderView(
                            header = header.header,
                            glyph = header.header.icon?.glyph(),
                            image = header.header.imagePath?.let { path ->
                                {
                                    coil3.compose.AsyncImage(
                                        model = java.io.File(path),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize().clip(Corners.Crisp),
                                    )
                                }
                            },
                            colors = gamingPageColors(),
                        )
                    } else if (bar != null) {
                        ButtonBarView(
                            bar = bar,
                            selected = barSlot.takeIf { isSelected },
                            onPress = { slot -> onBarPress(index, slot) },
                            ring = { on -> Modifier.focusRing(on && PadModality.showsFocus, Corners.Crisp) },
                            colors = gamingPageColors(),
                        )
                    } else if (block != null) {
                        TextBlockRow(block, isSelected, onShown = { onBlockShown(block) }, onClick = { onClick(index) })
                    } else if (notice != null) {
                        val page = notice.placement == NoticePlacement.Page
                        NoticeCard(
                            notice = notice.notice,
                            placement = notice.placement,
                            modifier = Modifier
                                // The banner sits under the header with room below it (docs/SPEC.md 7k, "Notices").
                                .padding(bottom = if (page) 12.dp else 0.dp)
                                .focusRing(isSelected && PadModality.showsFocus, if (page) Corners.Crisp else Corners.Icon),
                            onAction = notice.notice.action?.let { { onClick(index) } },
                            onInfo = { onLongClick(index) },
                        )
                    } else {
                        CatalogRowView(
                            row = row,
                            infoUnderRow = underRows || (narrow && isSelected),
                            oneLineSummary = panel,
                            locked = (row.item as? AsyncActionItem)?.gate?.let { readGates[it] != true } ?: false,
                            isSelected = isSelected,
                            confirmArmed = confirmArmedId == row.item.id,
                            status = statusById[row.item.id],
                            onClick = { onClick(index) },
                            onLongClick = { onLongClick(index) },
                            onAdjust = { direction -> onAdjust(index, direction) },
                        )
                    }
                }
            }
        }
        if (reserveTip) CatalogTipStrip(tip, immediate = tipArmed)
    }
}

/** Whether a row has Info: a sentence beyond its title (the glyph, the Y Info hint, "More info available"). */
internal fun hasInfo(item: CatalogItem): Boolean = !item.subtitle.isNullOrBlank()

/** What a row says about itself: the armed confirmation, else its live status and its explanation. */
private fun catalogTip(item: CatalogItem, status: String?, confirmArmed: Boolean, explanation: Boolean = true): String? =
    (if (confirmArmed) item.confirmText else null)
        ?: listOfNotNull(status, item.subtitle?.takeIf { explanation }).joinToString("\n").ifEmpty { null }

/**
 * The selected row's explanation in the fixed-height strip under a settings list, so the list never
 * changes size and nothing is drawn over the next row. Shows after the pad has rested on the row,
 * at once for an armed confirmation ([immediate]).
 */
@Composable
private fun CatalogTipStrip(text: String?, immediate: Boolean) {
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(text, immediate) {
        settled = immediate
        if (!immediate && text != null) {
            delay(TIP_DELAY_MS)
            settled = true
        }
    }
    // The focus line (docs/SPEC.md 7k, "Page anatomy"): three lines' room kept, so the list above never
    // changes size as the cursor moves; past three lines it ends "Y Info".
    FocusLine(
        if (settled) text.orEmpty() else "",
        modifier = Modifier
            .padding(horizontal = LocalShellWindow.current.edgePadding)
            .heightIn(min = with(LocalDensity.current) { (20.sp * FOCUS_LINE_LINES).toDp() } + 8.dp),
        colors = gamingPageColors(),
    )
}

private const val FOCUS_LINE_LINES = 3

/** The page pieces' colours in Gaming: the theme's own (the primary is the launch fill, danger the notice danger fill). */
@Composable
internal fun gamingPageColors(): PageColors {
    val notices = LocalNoticePalette.current
    return PageColors(
        primary = MenuTokens.Launch,
        onPrimary = MenuTokens.OnSurface,
        secondary = MenuTokens.Surface,
        onSecondary = MenuTokens.OnSurface,
        danger = notices.dangerFill,
        onDanger = notices.onDangerFill,
        outline = MenuTokens.CardOutline,
        text = MenuTokens.OnSurface,
        muted = MenuTokens.OnSurfaceMuted,
    )
}

/**
 * The category column: search at its top, then one entry per category,
 * a hub's links under its title, on a plate of its own that runs the
 * page's height from its left edge (Steam's settings list, [SettingsLayout]).
 * [current] is marked (Steam's quieter accent gradient and edge) wherever
 * the pad is; [cursor] is the entry the pad is on while it is in the column
 * (null while the pane has it), drawn with the stronger gradient and the
 * shell's one selection ring.
 */
@Composable
private fun SettingsCategoryColumn(
    categories: List<SettingsCategory>,
    current: Int,
    cursor: Int?,
    showSearch: Boolean,
    state: LazyListState,
    onSearch: () -> Unit,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val offset = if (showSearch) 1 else 0
    LazyColumn(
        state = state,
        modifier = modifier
            .background(MenuTokens.Card)
            .fadingEdges(state),
        // The entries reach the plate's left edge, where their accent edge is drawn.
        contentPadding = PaddingValues(top = Space.Md, bottom = Space.Md),
        verticalArrangement = Arrangement.spacedBy(Space.Xs),
    ) {
        if (showSearch) {
            item(key = SEARCH_ROW_ID) {
                // It searches settings only, so it says so: "Search" alone read as the global search (Droidtop/tracker#680).
                CategoryEntry(label = "Search settings", icon = CatalogIcon.SEARCH, current = false, cursor = cursor == 0, onClick = onSearch)
            }
        }
        itemsIndexed(categories, key = { _, category -> category.key }) { index, category ->
            Column {
                category.sectionAbove?.let { MenuSectionLabel(it, Modifier.padding(start = LocalShellWindow.current.edgePadding)) }
                CategoryEntry(
                    label = category.label,
                    icon = category.icon,
                    iconFile = category.iconFile,
                    current = index == current,
                    cursor = cursor == index + offset,
                    onClick = { onPick(index) },
                )
            }
        }
    }
}

/**
 * One entry of the category column (docs/SPEC.md "Settings layout", [SettingsLayout]): under the cursor it is the one
 * row focus every menu has ([rowFocus], Droidtop/tracker#644: the accent at 22 percent and a 4 dp accent bar at the
 * leading edge, nothing growing, no ring); the category being shown keeps a short accent pill on that edge, whether or
 * not the pad is in the column, and its name in the full text colour.
 */
@Composable
private fun CategoryEntry(label: String, icon: CatalogIcon?, current: Boolean, cursor: Boolean, onClick: () -> Unit, iconFile: String? = null) {
    val shown = cursor && PadModality.showsFocus
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MenuTokens.CategoryRowMinHeight)
            // The one row focus (Droidtop/tracker#644): the accent fill and bar under the cursor, the short pill on the
            // category on show.
            .rowFocus(shown, current = current)
            .padRow(onClick = onClick)
            .padding(start = LocalShellWindow.current.edgePadding, end = Space.Md, top = Space.Sm, bottom = Space.Sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null || iconFile != null) {
                CatalogGlyph(
                    icon,
                    iconFile,
                    tint = if (current || cursor) MenuTokens.OnSurface else MenuTokens.OnSurfaceMuted,
                    modifier = Modifier.size(SettingsLayout.CategoryIcon),
                )
            } else {
                Spacer(Modifier.size(SettingsLayout.CategoryIcon))
            }
            Spacer(Modifier.width(Space.Md))
            Text(
                label,
                color = if (current || cursor) MenuTokens.OnSurface else MenuTokens.OnSurfaceMuted,
                style = TypeRole.rowTitle,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Fades a scrolling list's content out over [edge] at whichever end has
 * more to scroll to, so a row the viewport cuts reads as "more this way"
 * rather than a slice.
 */
private fun Modifier.fadingEdges(state: LazyListState, edge: Dp = 24.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = edge.toPx().coerceAtMost(size.height / 2)
        if (state.canScrollBackward) {
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = 0f, endY = h),
                size = Size(size.width, h),
                blendMode = BlendMode.DstOut,
            )
        }
        if (state.canScrollForward) {
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = size.height - h, endY = size.height),
                topLeft = Offset(0f, size.height - h),
                size = Size(size.width, h),
                blendMode = BlendMode.DstOut,
            )
        }
    }

/** The navigator fills the page when it is at least this share of the screen's width. */
private const val PAGE_WIDTH_FRACTION = 0.8f

/** Narrower than this, the column and the pane take turns instead of sitting side by side. */
private val TWO_PANE_MIN_WIDTH = 600.dp

/** How long the column's cursor rests on a linked category before its screen is built. */
private const val PANE_LOAD_SETTLE_MS = 120L

/** How long a screen may build before its loading row shows. */
private const val LOADING_SHOW_MS = 250L

/** The loading row: what is being waited for, and the shell's own progress track (its size is not known). */
private fun loadingGroups(title: String) = listOf(
    CatalogGroup(
        id = "pane_loading",
        title = null,
        items = listOf(ActionItem(id = "pane_loading_row", title = if (title.isBlank()) "Loading" else "Loading $title", progress = -1f)),
    ),
)

/** A row Left/Right steps in place (a slider, a short choice); anything else is opened or flipped with A. */
private fun CatalogItem.stepsInPlace(): Boolean = this is SliderItem || (this is ChoiceItem && options.size <= 6)

/**
 * The settings navigator's screen stack as one savable value: the live
 * [CatalogScreen]s carry builder lambdas and cannot go into saved state,
 * so the stack saves as its screens' ids and re-resolves the pushed ones
 * through [SettingsScreenRegistry.resolveStack] on the way out -- the one
 * definition of that restore, shared with the Preference surface's
 * navigator. Every screen the Settings tab itself can push is a
 * registered one -- the root catalogs reference them by registryId
 * precisely so this module does not depend on their data -- and an id
 * that resolves to nothing ends the restore at the last resolvable
 * screen rather than dropping the whole stack. The root always comes
 * from the caller, never from the saved ids, so a call site that changed
 * its root keeps its new one.
 */
fun catalogStackSaver(root: CatalogScreen): Saver<SnapshotStateList<CatalogScreen>, Any> = listSaver(
    save = { screens -> screens.map { it.id } },
    restore = { ids ->
        if (ids.firstOrNull() != root.id) {
            null
        } else {
            mutableStateListOf(root).apply { addAll(SettingsScreenRegistry.resolveStack(ids.drop(1))) }
        }
    },
)

/** Each depth's selected row, flattened into one savable list. */
val selectionByDepthSaver: Saver<SnapshotStateMap<Int, Int>, Any> = listSaver(
    save = { byDepth -> byDepth.flatMap { (depth, row) -> listOf(depth, row) } },
    restore = { values ->
        mutableStateMapOf<Int, Int>().apply {
            var i = 0
            while (i + 1 < values.size) {
                val depth = values[i] as? Int ?: break
                val row = values[i + 1] as? Int ?: break
                put(depth, row)
                i += 2
            }
        }
    },
)

/**
 * Apply a value change to a catalog item -- the ONE definition of what a
 * Left/Right (or a tile press that cycles) means: choices wrap through
 * their options, sliders step within their range and clamp, toggles
 * flip. Returns whether anything actually changed, so a surface knows
 * whether to rebuild. Shared by the settings list and the Quick Menu's
 * tile grid ([QuickSettingsPanel]) rather than written once per surface.
 */
fun adjustCatalogItem(context: Context, item: CatalogItem, direction: Int): Boolean = when (item) {
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
    else -> false
}

private data class CatalogRow(val item: CatalogItem, val headerAbove: String?, val chipsAbove: List<CatalogChip> = emptyList())

/**
 * A group's status chips ([CatalogGroup.chips]): a page's facts at a glance (DroidDeck's
 * StorePage header chips, ui/StorePage.kt at 9310d19), never a focus stop. A good state ("Signed
 * in") stands out on the selected-row plate; the rest are quiet on the row plate.
 */
@Composable
private fun CatalogChipRow(chips: List<CatalogChip>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Space.Sm),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = Space.Sm, bottom = Space.Xs),
    ) {
        chips.forEach { chip ->
            StatusChip(
                text = chip.label,
                ink = if (chip.ok) MenuTokens.OnSurface else MenuTokens.Value,
                fill = if (chip.ok) MenuTokens.SurfaceSelected else MenuTokens.Surface,
            )
        }
    }
}

/** The synthetic root-level row that opens search -- never a real catalog id. */
private const val SEARCH_ROW_ID = "__settings_search__"

/**
 * One settings row (docs/SPEC.md "Settings layout"): its name, and what it
 * is set to in the shared value column -- a switch for a toggle, a track
 * and a number for a slider, a chevron for a row that opens something. Its
 * explanation is never a line on the row: it is shown in the pane's strip
 * ([CatalogTipStrip]) while the pad rests on it, and in the Y Info sheet. A live
 * status (working, a failure) takes the value column while it lasts.
 */
@Composable
private fun CatalogRowView(
    row: CatalogRow,
    infoUnderRow: Boolean,
    locked: Boolean,
    // Every subtitle shown as one line, as [CatalogItem.summaryShown] asks for one row (a plugin's panel, tracker#515).
    oneLineSummary: Boolean = false,
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
    val toggle = item as? ToggleItem
    val slider = item as? SliderItem
    val value = if (toggle != null) null else catalogRowValue(item, context)
    val placeholder = status == null && value == null && item is TextInputItem
    MenuRow(
        title = if (confirmArmed) "${item.title}: ${PressWords.again} to confirm" else item.title,
        value = if (locked) "Read it first" else status ?: value ?: if (placeholder) (if ((item as? TextInputItem)?.search == true) "Type to search" else "not set") else null,
        chip = item.chip?.label,
        // A state with a tone (a plugin's Allowed / Ask first / Blocked) is a chip in that tone (tracker#519).
        valueChip = status == null && !locked && (item as? ChoiceItem)?.toned == true,
        valueTone = (item as? ChoiceItem)?.currentTone(),
        placeholder = placeholder,
        adjustable = status == null && item.stepsInPlace(),
        chevron = chevron,
        selected = isSelected,
        danger = confirmArmed,
        accent = (item as? NestedScreenItem)?.accent?.let { Color(it) },
        icon = item.icon,
        iconFile = item.iconFile,
        onClick = onClick,
        onLongClick = onLongClick,
        onAdjust = onAdjust,
        // The enclosing LazyColumn (CatalogPane, above) already runs
        // `listState.keepInView` on every selection change -- see
        // MenuRow's own `ownScrollKeeping` doc comment for why a second,
        // independent scroll animation here was the real jank.
        ownScrollKeeping = true,
        uniformHeight = true,
        // The row's sentence: under the row when the person chose that (Info text), else on the focus line,
        // with the info glyph saying there is one (docs/SPEC.md 7k, "Explanations").
        // A summary row (a plugin card, a catalog row: tracker#514) always shows its one line, whatever the choice.
        subtitle = item.subtitle.takeIf { infoUnderRow || item.summaryShown || oneLineSummary },
        uniformSummaryLines = when {
            item.subtitle == null -> 0
            infoUnderRow -> 2
            item.summaryShown || oneLineSummary -> 1
            else -> 0
        },
        info = !infoUnderRow && !item.summaryShown && !oneLineSummary && hasInfo(item),
        onInfo = onLongClick,
        // A system state an action opens (Airplane mode, Bluetooth, VPN) is the same switch.
        switchOn = if (status == null) toggle?.current ?: (item as? ActionItem)?.state else null,
        sliderFraction = slider?.let { if (it.max > it.min) (it.current - it.min).toFloat() / (it.max - it.min) else 0f },
        progress = item.progress,
        progressLook = item.progressLook,
        progressParts = item.progressParts,
        statusMark = item.statusMark,
    )
}

private const val CONFIRM_CANCEL = "cancel"

/** How long after a gated text is first shown its Accept opens even if the text was not scrolled to the end (owner, 2026-10-09). */
private const val READ_GATE_MS = 3000L

/** How long the cursor rests on a row before the focus line shows its sentence (not at once, so a held direction does not flicker it). */
private const val TIP_DELAY_MS = 600L

/**
 * A paragraph of [TextBlockItem] in full: no cut, no value column, a heading when it has one. It is a stop of the pad
 * like any row, which is how a long text is scrolled; pressing it does nothing. Shown means the gate may open
 * ([onShown], once per composition of the row).
 */
@Composable
private fun TextBlockRow(block: TextBlockItem, selected: Boolean, onShown: () -> Unit, onClick: () -> Unit) {
    LaunchedEffect(block.id) { onShown() }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MenuTokens.RowShape)
            .selectionFrame(selected, MenuTokens.RowShape, row = true)
            .padRow(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = MenuTokens.RowVerticalPadding),
    ) {
        if (block.title.isNotBlank()) {
            Text(block.title, color = MenuTokens.OnSurface, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
        }
        Text(block.text, color = MenuTokens.OnSurface, style = MaterialTheme.typography.bodyMedium)
        if (block.busy) ShellLoading(Modifier.padding(top = Space.Sm).width(ProgressLook.LoadingWidth))
    }
}

/** What a settings row shows in its value column (null: none). */
private fun catalogRowValue(item: CatalogItem, context: Context): String? = when (item) {
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

/**
 * The strings a row's value column may show over its life, for sizing the
 * screen's ONE shared value column: every label of a small choice (so
 * cycling never moves the arrows), else the current value.
 */
private fun catalogValueCandidates(item: CatalogItem, context: Context): List<String> = when (item) {
    is ChoiceItem -> if (item.options.size <= 6) item.options.map { it.label } else listOfNotNull(item.currentLabel())
    is ToggleItem -> listOf("On", "Off")
    is SliderItem -> listOf(item.min.toString(), item.max.toString())
    else -> listOfNotNull(catalogRowValue(item, context))
}

/**
 * The detail strip under a page's list (the PC game page's facts): the
 * selected row's whole text (title and value when the row had to cut
 * them, and its full summary), in a fixed-height area so the list above
 * never changes size. Four lines of the summary type scale; Y opens the
 * Info sheet for more. Settings uses it with [lines] = 3 for the selected
 * row's explanation ([CatalogTipStrip], docs/SPEC.md "Settings layout").
 */
@Composable
fun CatalogDetailStrip(text: String, lines: Int = 4) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val style = MaterialTheme.typography.bodySmall
    val lineHeight = with(density) {
        (if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize * 1.4f).toDp()
    }
    Text(
        text,
        color = MenuTokens.OnSurfaceMuted,
        style = style,
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .height(lineHeight * lines + 16.dp)
            .padding(horizontal = LocalShellWindow.current.edgePadding, vertical = 8.dp),
    )
}

/**
 * A settings row in full: its name, what it is set to, and every word of
 * its explanation, for a value or title the row still has to cut. Opened by Y on
 * the row, or a long press; closed by B, A or Y.
 */
@Composable
private fun CatalogInfoSheet(item: CatalogItem, status: String?, onDismiss: () -> Unit) {
    if (item is NoticeItem) {
        NoticeInfoSheet(item, onDismiss)
        return
    }
    ShellDialog(onDismissRequest = onDismiss) {
        GatePadInThisDialog()
        MenuPanel(
            modifier = Modifier.width(LocalShellWindow.current.panelWidth(520.dp)),
            focusLabel = "Settings info",
            hints = listOf(HintBinding(GamepadAction.B, "Close")),
            onPad = { press ->
                val closes = press.action == GamepadAction.B || press.action == GamepadAction.A || press.action == GamepadAction.Y
                if (closes) onDismiss()
                true
            },
        ) {
            Text(item.title, color = MenuTokens.OnSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            // Show, then say (Droidtop/tracker#738): a switch's state is the switch, as on its row.
            if (item is ToggleItem) ShellSwitch(item.current, Modifier.padding(top = 4.dp).semantics { stateDescription = if (item.current) "On" else "Off" })
            val value = when (item) {
                is ChoiceItem -> item.currentLabel()
                is ToggleItem -> null
                is SliderItem -> item.current.toString()
                else -> item.value
            }
            value?.let { Text(it, color = MenuTokens.Value, style = MaterialTheme.typography.bodyMedium) }
            item.subtitle?.let {
                Text(it, color = MenuTokens.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
            status?.let { Text(it, color = MenuTokens.Value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

/**
 * A notice's Info sheet (docs/SPEC.md 7k, "Notices"): its title, its body and its details, which may name
 * exact technical terms, and a Copy chip on Select that puts the details on the clipboard so a person can
 * paste them into a report. B, A or Y close it.
 */
@Composable
internal fun NoticeInfoSheet(item: NoticeItem, onDismiss: () -> Unit) {
    val notice = item.notice
    val details = notice.details?.takeIf { it.isNotBlank() }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember(item.id) { mutableStateOf(false) }
    val copy = {
        if (details != null) {
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(details))
            copied = true
        }
    }
    val hints = remember(details != null) {
        listOfNotNull(
            HintBinding(GamepadAction.SELECT, "Copy").takeIf { details != null },
            HintBinding(GamepadAction.B, "Close"),
        )
    }
    ShellDialog(onDismissRequest = onDismiss) {
        GatePadInThisDialog()
        MenuPanel(
            modifier = Modifier.width(LocalShellWindow.current.panelWidth(520.dp)),
            focusLabel = "Notice info",
            hints = hints,
            onPad = { press ->
                when (press.action) {
                    GamepadAction.SELECT -> copy()
                    GamepadAction.B, GamepadAction.A, GamepadAction.Y -> onDismiss()
                    else -> {}
                }
                true
            },
        ) {
            Text(notice.title, color = MenuTokens.OnSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            notice.source?.let { Text("from $it", color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall) }
            notice.body?.let { Text(it, color = MenuTokens.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
            if (details != null) {
                Text(
                    details,
                    color = MenuTokens.OnSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
                    modifier = Modifier.padding(top = 8.dp),
                )
                StatusChip(
                    text = if (copied) "Copied" else "Copy",
                    ink = MenuTokens.OnSurface,
                    fill = MenuTokens.SurfaceSelected,
                    modifier = Modifier.padding(top = 12.dp).padRow { copy() },
                )
            }
        }
    }
}

/** Full-screen option list for large [ChoiceItem]s (system pickers etc.). */
@Composable
fun CatalogChoicePicker(
    item: ChoiceItem,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(item.options.indexOfFirst { it.value == item.current }.coerceAtLeast(0)) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus, "Choice picker") }
    var heldStep by remember { mutableStateOf(false) }
    LaunchedEffect(selected) { if (item.options.isNotEmpty()) listState.keepInView(selected, animate = !heldStep) }
    BackHandler { onDismiss() }

    Column(Modifier.fillMaxSize()) {
        MenuHeader(item.title)
        LazyColumn(
            state = listState,
            // The height under the header, not the page's: the full height ran the last options off the screen,
            // where the cursor reached them without the list scrolling (Droidtop/tracker#616).
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusRequester(focus)
                .focusable()
                .onPad { press ->
                    when (press.action) {
                        GamepadAction.UP, GamepadAction.DOWN -> {
                            heldStep = press.repeat
                            selected = menuStep(selected, item.options.size, if (press.action == GamepadAction.UP) -1 else 1)
                        }
                        GamepadAction.A -> item.options.getOrNull(selected)?.let { onPick(it.value) }
                        GamepadAction.B -> onDismiss()
                        else -> return@onPad false
                    }
                    true
                },
            contentPadding = MenuListContentPadding,
            verticalArrangement = Arrangement.spacedBy(MenuTokens.RowSpacing),
        ) {
            itemsIndexed(item.options, key = { _, option -> option.value }) { index, option ->
                MenuRow(
                    title = option.label,
                    selected = index == selected,
                    onClick = { onPick(option.value) },
                    // This screen's own listState.keepInView (above)
                    // already follows `selected` -- see MenuRow's
                    // `ownScrollKeeping` doc comment.
                    ownScrollKeeping = true,
                )
            }
        }
    }
}

/**
 * The shell's one text-entry dialog: a settings row's value, and anything
 * else a person types or pastes (a source link on a game's screen).
 * Paste is there because what gets typed here is usually copied
 * from somewhere else.
 */
@Composable
fun TextEditDialog(
    title: String,
    subtitle: String?,
    initial: String,
    onCommit: (String) -> Unit,
    onDismiss: () -> Unit,
    multiline: Boolean = false,
    secret: Boolean = false,
    // A search box: the keyboard's Search key runs it, the button says Search, and there is nothing to paste.
    search: Boolean = false,
) {
    var value by remember(title, initial) { mutableStateOf(initial) }
    ShellDialog(onDismissRequest = onDismiss) {
        GatePadInThisDialog()
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
                    imeAction = when {
                        multiline -> androidx.compose.ui.text.input.ImeAction.Default
                        search -> androidx.compose.ui.text.input.ImeAction.Search
                        else -> androidx.compose.ui.text.input.ImeAction.Done
                    },
                    showKeyboardOnFocus = raisesKeyboardWithFocus,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onCommit(value) }, onSearch = { onCommit(value) }),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MenuTokens.OnSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(Corners.Crisp)
                    .background(MenuTokens.SurfaceSelected)
                    .opensFocused("Text field")
                    .padding(12.dp),
            )
            // On a screen Android draws no keyboard on (the add-on display), droidtop's own (SPEC 4c, tracker#314).
            OwnFieldKeyboard()
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                if (!search) {
                    TextButton(
                        onClick = { clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { value = it } },
                        modifier = Modifier.keepsFieldFocusOnTouch(),
                    ) { Text(stringResource(R.string.ui_shared_paste), color = MenuTokens.Accent) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss, modifier = Modifier.keepsFieldFocusOnTouch()) {
                    Text(stringResource(R.string.ui_shared_cancel), color = MenuTokens.OnSurfaceMuted)
                }
                TextButton(onClick = { onCommit(value) }, modifier = Modifier.keepsFieldFocusOnTouch()) {
                    Text(if (search) "Search" else "Save", color = MenuTokens.Accent)
                }
            }
        }
    }
}

/**
 * A button of [TextEditDialog] that a finger presses without taking focus from the field above it.
 * A button is focusable, and a touch moved the focus onto it on the press: the field lost focus, Android
 * took its keyboard down, the dialog resized, and the button was no longer under the finger when it lifted,
 * so the first tap on Save only dismissed the keyboard and a second one saved (Droidtop/tracker#307). With
 * the focus left in the field the press is a plain tap. Only on a touch: with a pad or a keyboard the
 * buttons stay on the focus path, which is how they are reached.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Modifier.keepsFieldFocusOnTouch(): Modifier {
    val input = LocalInputModeManager.current
    return focusProperties { canFocus = input.inputMode != InputMode.Touch }
}

/**
 * Settings home only (docs/SPEC.md settings architecture, "search across
 * settings"): a text field and, once two characters are typed, the
 * matching rows from [SettingsSearchIndex], each showing which screen it
 * lives on. Picking one navigates there -- the same [MenuRow] anatomy and
 * the same A/B/touch input as the list it replaces, so this is one more
 * screen in the shell's own language rather than a second search UI.
 *
 * [index] is null while the caller's one-time [SettingsSearchIndex.build]
 * is still running on IO: the overlay shows "Indexing settings..." rather
 * than a false "No settings match", and the query re-runs when the index
 * lands (the first search right after opening "Search settings" raced the
 * build and answered over an empty index, Droidtop/tracker#101).
 */
@Composable
private fun SettingsSearchOverlay(
    query: String,
    onQueryChange: (String) -> Unit,
    index: List<SettingsSearchResult>?,
    onPick: (SettingsSearchResult) -> Unit,
    onClose: () -> Unit,
) {
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(fieldFocus, "Settings search") }
    // Re-runs on every keystroke and once more when the index lands: search
    // is pure and in-memory, and a null index (still building) filters to
    // nothing so the loading line below is what shows.
    val results = remember(index, query) { SettingsSearchIndex.search(index ?: emptyList(), query) }
    var selected by remember { mutableStateOf(0) }
    LaunchedEffect(results.size) {
        selected = selected.coerceIn(0, (results.size - 1).coerceAtLeast(0))
    }
    Column(modifier = Modifier.fillMaxSize()) {
        MenuHeader("Search settings", "Type a setting's name")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalShellWindow.current.edgePadding, vertical = 8.dp)
                .clip(Corners.Crisp)
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
                    // B, or a keyboard's Escape, closes search from the
                    // field itself: BackHandler alone does not see key
                    // events a focused text field consumed, and the field
                    // takes the keys it types before this sees them.
                    .onPad { press ->
                        if (press.action == GamepadAction.B) {
                            onClose()
                            true
                        } else if (results.isNotEmpty()) {
                            when (press.action) {
                                GamepadAction.UP, GamepadAction.DOWN -> {
                                    selected = menuStep(selected, results.size, if (press.action == GamepadAction.UP) -1 else 1)
                                    true
                                }
                                GamepadAction.A -> {
                                    results.getOrNull(selected)?.let(onPick)
                                    true
                                }
                                else -> false
                            }
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
                        .padRow { onQueryChange("") },
                )
            }
        }
        when {
            query.trim().length < 2 ->
                Text(
                    "Keep typing: at least two letters",
                    color = MenuTokens.OnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = LocalShellWindow.current.edgePadding, vertical = 12.dp),
                )
            index == null ->
                Text(
                    "Indexing settings...",
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
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusable()
                        .onPad { press ->
                            when (press.action) {
                                GamepadAction.UP, GamepadAction.DOWN ->
                                    selected = menuStep(selected, results.size, if (press.action == GamepadAction.UP) -1 else 1)
                                GamepadAction.A -> results.getOrNull(selected)?.let(onPick)
                                else -> return@onPad false
                            }
                            true
                        },
                    contentPadding = MenuListContentPadding,
                    verticalArrangement = Arrangement.spacedBy(MenuTokens.RowSpacing),
                ) {
                    itemsIndexed(results, key = { _, result -> result.itemId }) { index, result ->
                        MenuRow(
                            title = result.itemTitle,
                            subtitle = result.screenTitle,
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
 * [groups] headed by one "You're offline" notice when [screen] needs the network and the device has none
 * (Droidtop/tracker#594): a page whose actions would only fail says so before one is pressed. Read on the IO
 * dispatcher with the screen's own rows; the page is read again on its next refresh, so the notice goes once a
 * connection is back and something on the page changes.
 */
internal suspend fun withOfflineNotice(screen: CatalogScreen, context: Context, groups: List<CatalogGroup>): List<CatalogGroup> {
    if (!screen.needsNetwork) return groups
    val offline = withContext(Dispatchers.IO) {
        runCatching { dev.droidtop.runtime.systemstatus.SystemStatus.snapshot(context).network }.getOrNull() ==
            dev.droidtop.runtime.systemstatus.NetworkKind.NONE
    }
    if (!offline) return groups
    val notice = dev.droidtop.library.settings.NoticeItem(
        id = "${screen.id}_offline",
        notice = dev.droidtop.library.settings.Notice(
            dev.droidtop.library.settings.NoticeTone.Caution,
            "You're offline",
            body = "This page needs a connection. What it shows may be out of date, and its actions wait until you're back online.",
        ),
    )
    return listOf(CatalogGroup(id = "${screen.id}_offline_group", title = null, items = listOf(notice))) + groups
}
