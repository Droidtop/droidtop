package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.friendlyLocation
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage

import dev.droidtop.library.GameLinks
import dev.droidtop.library.UpdateSources
import dev.droidtop.library.GameNaming
import dev.droidtop.library.GameUpdates
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.groupingPath
import dev.droidtop.library.isUnscraped
import dev.droidtop.library.scraper.PcScraper
import dev.droidtop.library.LibraryGrouping
import dev.droidtop.library.ownership
import dev.droidtop.library.ownershipLabel
import dev.droidtop.library.scraper.FieldSources
import dev.droidtop.shell.gamepad.CatalogDetailStrip
import dev.droidtop.shell.gamepad.HintTip
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.LocalValueColumnWidth
import dev.droidtop.shell.gamepad.MenuRow
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.Corners
import dev.droidtop.shell.gamepad.FloodOrigin
import dev.droidtop.shell.gamepad.FocusGlideHost
import dev.droidtop.shell.gamepad.PageFlood
import dev.droidtop.shell.gamepad.QuickGlyph
import dev.droidtop.shell.gamepad.QuickGlyphIcon
import dev.droidtop.shell.gamepad.screenRect
import dev.droidtop.shell.gamepad.Motion
import dev.droidtop.shell.gamepad.ShellChip
import dev.droidtop.shell.gamepad.ShoulderGlyph

import dev.droidtop.shell.gamepad.Space
import dev.droidtop.shell.gamepad.TypeRole
import dev.droidtop.shell.gamepad.groundBackground
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.DeclareLayerHints
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.HideSystemBarsInThisDialog
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.LocalShellMenus
import dev.droidtop.shell.gamepad.input.menuStep
import dev.droidtop.shell.gamepad.input.onPad
import dev.droidtop.shell.gamepad.input.ownPadButtons
import dev.droidtop.shell.gamepad.keepInView
import dev.droidtop.shell.gamepad.requestFocusWhenAttached
import dev.droidtop.shell.gamepad.showsShoulderGlyphs
import dev.droidtop.shell.gamepad.selectionFrame
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import dev.droidtop.shell.gamepad.theme.UiSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * One PC game's own page (docs/SPEC.md 7i, "The game page", 2026-10-02):
 * the structure a storefront game page has, drawn in droidtop's own
 * tokens. From the top:
 *
 * - **The hero band**: the game's hero art full-bleed, 46 percent of the
 *   height in landscape, fading into the page, with its large logo (or its
 *   name) bottom-left. The whole page is ONE scrolling list, as Steam's: with
 *   the cursor down in the tab's rows the hero and the action band scroll
 *   away and the tab strip stays pinned at the top, gaining a plate once
 *   rows pass under it.
 * - **The action band**, one row: ONE large primary action that says what A
 *   does (Play, or the one setup step that makes it Play, or why it cannot --
 *   [PcPlayState]), a quiet facts strip beside it ([factsStrip]: last
 *   played, play time, the version installed against the latest known, size,
 *   what runs it, each a small capitals label over its value), small
 *   Favourite and Options (a gear) square buttons at the right edge, and the
 *   one-line reason under it.
 * - **The tab strip** (Overview, Versions and updates, Extras, Details),
 *   Steam's uppercase tab pills, centred under the band. It OWNS L1/R1 while the page is open: the page
 *   is a window of its own over the library, so its one `onPad` handler is
 *   what the shoulders reach, the same "nearest strip takes them" rule the
 *   shell applies (docs/SPEC.md 7j, "Gaming controls"). The glyphs sit at
 *   the strip's ends.
 * - **The tab's rows**: Settings' rows ([MenuRow], one uniform height, one
 *   content-sized value column) and, while the cursor is in them, the
 *   selected row's full text in the detail strip under the list
 *   (docs/SPEC.md 7k, "Text in rows and tiles"). A game of several parts
 *   lists them first on Overview ([partFacts]). Every fact the page always
 *   had is still a row, under the tab [pageTabOf] puts it in; nothing a
 *   person would look for is behind a menu (Droidtop/tracker#173).
 *
 * The pad comes through the one pipeline (docs/SPEC.md 6e,
 * Droidtop/tracker#178): this is a window of its own, so it gets the
 * pipeline's front ([GatePadInThisDialog]); one `onPad` handler on its
 * root moves ONE cursor through three zones (the action band's buttons,
 * the tabs, the tab's rows). Up and Down move between zones and rows, Left
 * and Right along the buttons or the tabs, L1/R1 the tab from anywhere, A
 * presses what the cursor is on, X toggles favourite, L2 opens the game's
 * menu, and B closes from the outermost node ([ownPadButtons]) -- so the
 * face-button swap applies to every button here, and no control takes
 * Compose focus of its own. A tap on a button, a tab or a row is the same
 * press. The D-pad never reaches the top bar: this window has none. The page
 * draws no hint row: it is a layer on the shell's one footer
 * ([DeclareLayerHints]) and stays clear of it.
 *
 * The page grows out of the capsule it was opened from and draws back into
 * it on B, and Play grows into the launch screen ([PageFlood], behind the
 * Animations switch).
 *
 * No disk work while drawing: everything shown is already on the
 * [LibraryEntry]; the lookups, the resolved runner, the folder's size and
 * the parts, run for this one game off the main thread.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun PcGamePage(
    entry: LibraryEntry,
    // Every folder and store row of this same game (docs/SPEC.md 7m), for
    // "Owned on", the versions and the parts; the entry itself when there is
    // nothing to group with.
    siblings: List<LibraryEntry>,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenOptions: () -> Unit,
    // "Choose a runner" on a game no runner offers: opens the Engine picker (Droidtop/tracker#287).
    onChooseEngine: () -> Unit = onOpenOptions,
    onClose: () -> Unit,
    // The library, for the game's update sources (the source link rows);
    // null draws the page without them.
    library: Library? = null,
) {
    val context = LocalContext.current
    val window = LocalShellWindow.current
    val (play, runner) = rememberPcPlayState(entry)
    val folderSize by produceState<Long?>(null, entry.id) {
        val path = entry.groupingPath() ?: return@produceState
        value = withContext(Dispatchers.IO) { folderSizeBytes(path) }
    }
    // A game nothing has matched shows a plain row that scrapes it through the
    // one PC scrape pipeline (docs/SPEC.md 7n); what the scrape said replaces
    // the row's value until the library publishes the result.
    val scope = rememberCoroutineScope()
    var scrapeStatus by remember(entry.id) { mutableStateOf<String?>(null) }
    // The game's update sources (docs/SPEC.md 7g, 7i): a link is the GAME's,
    // so it is read and written for every folder of it, through the same
    // library calls the options menu uses.
    val isFolder = entry.groupingPath() != null
    val gameIds = remember(entry, siblings) {
        siblings.filter { it.groupingPath() != null }.map { it.id }.ifEmpty { listOf(entry.id) }
    }
    val versions = remember(entry, siblings) { installedVersions(entry, siblings) }
    var linksToken by remember(entry.id) { mutableIntStateOf(0) }
    var editingSource by remember(entry.id) { mutableStateOf<UpdateSources.Source?>(null) }
    var sourceStatus by remember(entry.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val links by produceState<GameLinks?>(null, gameIds, linksToken) {
        value = if (library != null && isFolder) library.gameLinks(gameIds) else null
    }
    val updateSources by produceState(emptyList<UpdateSources.Source>(), library, isFolder) {
        value = if (library != null && isFolder) library.updateSources() else emptyList()
    }
    val rows = remember(entry, play, runner, siblings, scrapeStatus, links, sourceStatus, updateSources) {
        pageRows(
            entry, play, runner, siblings,
            scrapeStatus = scrapeStatus,
            latest = links?.latestKnown ?: entry.latestKnown,
            onOpenLink = { url ->
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            sourceRows = if (library != null && isFolder) {
                sourceRows(
                    sources = updateSources,
                    links = links,
                    statuses = sourceStatus,
                    onEdit = { editingSource = it },
                    onCheck = { source, link ->
                        scope.launch {
                            sourceStatus = sourceStatus + (source.key to "Checking...")
                            val line = checkSourceAndSay(library, gameIds, link.key, versions, entry.latestKnown)
                            sourceStatus = sourceStatus + (source.key to line)
                            linksToken++
                        }
                    },
                )
            } else {
                emptyList()
            },
            onScrape = {
                scope.launch {
                    scrapeStatus = "Looking it up..."
                    scrapeStatus = PcScraper.scrape(context, listOf(entry))
                }
            },
        )
    }
    val parts by produceState(emptyList<PageFact>(), entry.id, siblings) {
        value = withContext(Dispatchers.Default) { partFacts(siblings) }
    }
    // What plugins add to this game's page, under the tabs they name (docs/plugin-api.md 3 C4, C18).
    val pluginRows = rememberPluginPageRows(entry)
    // What the page learns from outside the library: play time, an emulator's compatibility list and, where a
    // service has them, achievements (docs/SPEC.md 7h, "Game info"). Asked for once when the page opens.
    var infoScreen by remember(entry.id) { mutableStateOf<dev.droidtop.library.settings.CatalogScreen?>(null) }
    val infoRows = dev.droidtop.shell.gamepad.rememberGameInfoRows(entry)
    // Which program a Windows game runs (docs/SPEC.md 7i, "Which program
    // runs"): the folder is read for this one open game, on IO, and again
    // when the choice sheet closes.
    var programScreen by remember(entry.id) { mutableStateOf<dev.droidtop.library.settings.CatalogScreen?>(null) }
    val windowsRoute = runner?.option?.strategy == dev.droidtop.library.GameLaunchStrategy.WINE_PREFIX
    val program by produceState<Pair<String, String>?>(null, entry.id, windowsRoute, programScreen) {
        if (!windowsRoute || programScreen != null) return@produceState
        value = withContext(Dispatchers.IO) {
            val folder = dev.droidtop.library.PcRunnerOptions.gameFolderFor(entry) ?: return@withContext null
            folder.absolutePath to (dev.droidtop.library.WindowsPrograms.choices(context, entry.id, folder).current ?: "")
        }
    }
    val programFact = program?.let { (folder, current) ->
        PageFact(
            "Program",
            current.ifEmpty { "Choose" },
            subtitle = if (current.isEmpty()) "droidtop can't tell which program is the game" else null,
            onActivate = { programScreen = dev.droidtop.library.WindowsPrograms.screen(entry.id, GameNaming.displayName(entry.title), folder) },
        )
    }
    val tabs = remember { PageTab.values() }
    val rowsByTab = remember(rows, parts, pluginRows.facts, programFact?.value, programFact?.subtitle, infoRows) {
        groupRowsByTab(
            rows + listOfNotNull(programFact) + pluginRows.facts + infoRows.map { it.toPageFact(context) { screen -> infoScreen = screen } },
            parts,
        )
    }
    val strip = remember(entry, runner, folderSize, siblings) {
        factsStrip(
            entry = entry,
            now = System.currentTimeMillis(),
            folderSizeBytes = folderSize,
            installedVersion = installedVersions(entry, siblings).firstOrNull(),
            runsWith = runner?.label,
            formatSize = { android.text.format.Formatter.formatShortFileSize(context, it) },
        )
    }

    // ONE cursor in three zones: the buttons, the tabs, the tab's rows.
    var zone by remember(entry.id) { mutableStateOf(PageZone.ACTIONS) }
    var button by remember(entry.id) { mutableIntStateOf(0) }
    var tab by remember(entry.id) { mutableIntStateOf(0) }
    var row by remember(entry.id) { mutableIntStateOf(0) }
    var heldStep by remember { mutableStateOf(false) }
    val current = rowsByTab[tabs[tab]].orEmpty()
    // ONE scrolling page, as Steam's: the hero, the play row, the tab strip
    // (sticky: it pins to the top once rows pass under it) and the tab's rows.
    val listState = rememberLazyListState()
    val tabState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus, "PC game page") }
    LaunchedEffect(tab) { tabState.keepInView(tab) }
    LaunchedEffect(zone, row, tab, current.size) {
        when (zone) {
            // The top of the page: the hero and the play row in full.
            PageZone.ACTIONS -> listState.keepInView(PAGE_ITEM_HERO, animate = !heldStep)
            PageZone.TABS -> listState.keepInView(PAGE_ITEM_TABS, animate = !heldStep)
            PageZone.CONTENT -> if (current.isNotEmpty()) {
                listState.keepInView(
                    PAGE_ITEM_FIRST_ROW + row.coerceIn(0, current.lastIndex),
                    animate = !heldStep,
                    under = PAGE_ITEM_TABS,
                )
            }
        }
    }
    // The strip gains its plate once rows have passed under it (Steam's frosted tab header; an opaque
    // plate here, droidtop draws no blur), within [PAGE_PLATE_MS].
    val stuck by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > PAGE_ITEM_TABS ||
                (listState.firstVisibleItemIndex == PAGE_ITEM_TABS && listState.firstVisibleItemScrollOffset > 0)
        }
    }
    val plate = animateFloatAsState(if (stuck) 1f else 0f, Motion.tw(PAGE_PLATE_MS), label = "tab plate")

    // The page grows out of the capsule it was opened from and draws back
    // into it on B (PageFlood.kt); Play grows into the launch screen.
    val view = LocalView.current
    val origin = remember(entry.id) { FloodOrigin.capsuleRect(entry.id) }
    var leaving by remember(entry.id) { mutableStateOf(false) }
    val closePage: () -> Unit = { if (!leaving) leaving = true }
    var playAt by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }

    val buttons = 3
    fun pressButton(index: Int) {
        when (index) {
            0 -> if (play.chooseEngine) {
                onChooseEngine()
            } else if (play.pressable) {
                // The setup step runs over the page, which stays: closing it
                // hid the page behind the offer (Droidtop/tracker#293).
                if (play.ready) playAt?.takeIf { it.isAttached }?.let { FloodOrigin.markLaunch(screenRect(it, view)) }
                if (play.ready || play.store != null) onClose()
                onPlay()
            }
            1 -> onToggleFavorite()
            2 -> onOpenOptions()
        }
    }

    // L1/R1, Left/Right on the strip, and a tap: one tab change.
    fun selectTab(next: Int) {
        val clamped = next.coerceIn(0, tabs.lastIndex)
        if (clamped != tab) EsDeNavigationSounds.play(UiSound.TAB)
        tab = clamped
        row = 0
        if (zone == PageZone.CONTENT && rowsByTab[tabs[clamped]].isNullOrEmpty()) zone = PageZone.TABS
    }

    val heroHeight = (window.heightDp * (if (window.portrait) PAGE_HERO_PORTRAIT else PAGE_HERO_LANDSCAPE)).dp
    val shoulderGlyphs = window.showsShoulderGlyphs()
    val shellMenus = LocalShellMenus.current

    // The shell's one footer names this page's buttons (a layer on the hint
    // bar); the page draws no hint row of its own. L1/R1 are named by the
    // glyphs at the tab strip's ends.
    val verb = play.verb
    val hints = remember(zone, button, row, tab, verb, play.pressable, current) {
        listOf(
            HintBinding(GamepadAction.A, if (zone == PageZone.ACTIONS) pageActionLabel(button, verb) else "Select") {
                when (zone) {
                    PageZone.ACTIONS -> button != 0 || play.pressable
                    PageZone.TABS -> current.isNotEmpty()
                    PageZone.CONTENT -> current.getOrNull(row)?.onActivate != null
                }
            },
            HintBinding(GamepadAction.X, "Favourite"),
            HintBinding(GamepadAction.L2, "Game options"),
            HintBinding(GamepadAction.B, "Back"),
        )
    }

    // Into and out of a game's page each have a cue (Steam's, docs/SPEC.md "Interface sounds").
    androidx.compose.runtime.DisposableEffect(Unit) {
        EsDeNavigationSounds.play(UiSound.PAGE_IN)
        onDispose { EsDeNavigationSounds.play(UiSound.PAGE_OUT) }
    }
    Dialog(
        onDismissRequest = closePage,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        GatePadInThisDialog()
        HideSystemBarsInThisDialog()
        DeclareLayerHints(hints, menusReachable = true)
        // A full page dims nothing: the shell shows round the flood until the page covers it.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindow?.setDimAmount(0f) }
        // The page is a window of its own: it hosts its own sliding ring.
        FocusGlideHost(Modifier.fillMaxSize()) {
          PageFlood(from = origin, leaving = leaving, onLeft = onClose) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Clear of the shell's footer, which stays visible under the page.
                    .padding(bottom = window.frameBarHeight)
                    // B from the outermost node; the cursor's own presses
                    // nearer the focus target, so they are answered first.
                    .ownPadButtons(onBack = closePage)
                    .onPad { press ->
                        heldStep = press.repeat
                        when (press.action) {
                            GamepadAction.UP -> when (zone) {
                                PageZone.ACTIONS -> Unit
                                // A zone is crossed by a fresh press only: a held
                                // direction stops at the edge instead of running
                                // through the whole page.
                                PageZone.TABS -> if (!press.repeat) {
                                    EsDeNavigationSounds.play(UiSound.MOVE)
                                    zone = PageZone.ACTIONS
                                }
                                PageZone.CONTENT -> if (row > 0) {
                                    EsDeNavigationSounds.play(UiSound.MOVE)
                                    row -= 1
                                } else if (!press.repeat) {
                                    EsDeNavigationSounds.play(UiSound.MOVE)
                                    zone = PageZone.TABS
                                }
                            }
                            GamepadAction.DOWN -> when (zone) {
                                PageZone.ACTIONS -> if (!press.repeat) {
                                    EsDeNavigationSounds.play(UiSound.MOVE)
                                    zone = PageZone.TABS
                                }
                                PageZone.TABS -> if (!press.repeat && current.isNotEmpty()) {
                                    EsDeNavigationSounds.play(UiSound.MOVE)
                                    zone = PageZone.CONTENT
                                    row = 0
                                }
                                PageZone.CONTENT -> {
                                    val next = menuStep(row, current.size, +1)
                                    if (next != row) EsDeNavigationSounds.play(UiSound.MOVE)
                                    row = next
                                }
                            }
                            GamepadAction.LEFT, GamepadAction.RIGHT -> {
                                val step = if (press.action == GamepadAction.LEFT) -1 else 1
                                when (zone) {
                                    PageZone.ACTIONS -> {
                                        val next = menuStep(button, buttons, step)
                                        if (next != button) EsDeNavigationSounds.play(UiSound.MOVE)
                                        button = next
                                    }
                                    PageZone.TABS -> selectTab(tab + step)
                                    PageZone.CONTENT -> return@onPad false
                                }
                            }
                            // The shoulders step the tab strip from anywhere on the
                            // page, and the strip owns them even at its end.
                            GamepadAction.L -> selectTab(tab - 1)
                            GamepadAction.R -> selectTab(tab + 1)
                            // This window never reaches the shell's root handler, so
                            // the two menu pills on the footer are answered here.
                            GamepadAction.START -> shellMenus?.openLeft?.invoke()
                            GamepadAction.R2 -> shellMenus?.openQuick?.invoke()
                            GamepadAction.A -> when (zone) {
                                PageZone.ACTIONS -> pressButton(button)
                                PageZone.TABS -> if (current.isNotEmpty()) {
                                    zone = PageZone.CONTENT
                                    row = 0
                                }
                                PageZone.CONTENT -> current.getOrNull(row)?.onActivate?.invoke()
                            }
                            GamepadAction.X -> onToggleFavorite()
                            GamepadAction.L2 -> onOpenOptions()
                            else -> return@onPad false
                        }
                        true
                    }
                    .focusRequester(focus)
                    .focusable()
                    .groundBackground(),
            ) {
                // One value column for the tab, content-sized to its widest
                // value (docs/SPEC.md 7k), as the Settings catalog does.
                val measurer = rememberTextMeasurer()
                val valueStyle = MaterialTheme.typography.bodyMedium
                val density = LocalDensity.current
                val valueColumnWidth = remember(current, valueStyle, density.fontScale) {
                    val widest = current.mapNotNull { it.value }
                        .maxOfOrNull { measurer.measure(it, valueStyle, maxLines = 1, softWrap = false).size.width } ?: 0
                    with(density) { widest.toDp() }.coerceIn(MenuTokens.ValueColumnMinWidth, MenuTokens.ValueColumnMaxWidth)
                }
                CompositionLocalProvider(LocalValueColumnWidth provides valueColumnWidth) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = Space.Lg),
                    ) {
                        item(key = "hero") { PageHero(entry, heroHeight) }
                        item(key = "band") {
                            Box(Modifier.padding(horizontal = window.edgePadding)) {
                                PageActionBand(
                                    play = play,
                                    favourite = entry.favorite,
                                    selectedButton = if (zone == PageZone.ACTIONS) button else null,
                                    strip = strip,
                                    portrait = window.portrait,
                                    onPlayPlaced = { playAt = it },
                                    onPress = { index ->
                                        zone = PageZone.ACTIONS
                                        button = index
                                        pressButton(index)
                                    },
                                )
                            }
                        }
                        stickyHeader(key = "tabs") {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    // Steam's sticky header: its plate, and a drop shadow under it (measured:
                                    // offset 4, blur 8, black at 50 percent), drawn as a short fade below the
                                    // strip. A platform elevation shadow spread 24dp over a dark ground and did
                                    // not show at all (rig, build 1535).
                                    .drawBehind {
                                        val p = plate.value
                                        if (p > 0.01f) {
                                            drawRect(MenuTokens.Scrim, alpha = p)
                                            val top = size.height + PAGE_PLATE_SHADOW_OFFSET_DP.dp.toPx() / 2f
                                            val fade = PAGE_PLATE_SHADOW_BLUR_DP.dp.toPx()
                                            drawRect(
                                                Brush.verticalGradient(
                                                    listOf(Color.Black.copy(alpha = PAGE_PLATE_SHADOW_ALPHA * p), Color.Transparent),
                                                    startY = size.height,
                                                    endY = top + fade,
                                                ),
                                                topLeft = Offset(0f, size.height),
                                                size = Size(size.width, top + fade - size.height),
                                            )
                                        }
                                    }
                                    .padding(horizontal = window.edgePadding),
                            ) {
                                PageTabStrip(
                                    tabs = tabs,
                                    tab = tab,
                                    cursorOnTabs = zone == PageZone.TABS,
                                    state = tabState,
                                    shoulderGlyphs = shoulderGlyphs,
                                    onSelect = { index ->
                                        zone = PageZone.TABS
                                        selectTab(index)
                                    },
                                )
                            }
                        }
                        if (current.isEmpty()) {
                            item(key = "empty:$tab") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = Space.Xxxl),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(tabs[tab].emptyLine, color = MenuTokens.OnSurfaceMuted, style = TypeRole.body)
                                }
                            }
                        } else {
                            itemsIndexed(current, key = { index, fact -> "$tab:$index:${fact.title}" }) { index, fact ->
                                HintTip(
                                    fact.tip,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = window.edgePadding, vertical = MenuTokens.RowSpacing / 2),
                                ) {
                                    MenuRow(
                                        title = fact.title,
                                        subtitle = fact.subtitle,
                                        value = fact.value,
                                        chevron = fact.onActivate != null,
                                        selected = zone == PageZone.CONTENT && row == index,
                                        uniformHeight = true,
                                        ownScrollKeeping = true,
                                        onClick = {
                                            zone = PageZone.CONTENT
                                            row = index
                                            fact.onActivate?.invoke()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                // The selected row in full, so no row has to grow to be read:
                // the whole description, a long value, where the facts came
                // from. Only while the cursor is in the rows.
                if (zone == PageZone.CONTENT && current.isNotEmpty()) {
                    val selected = current.getOrNull(row)
                    CatalogDetailStrip(
                        selected?.let { fact ->
                            listOfNotNull(
                                fact.value?.takeIf { it.length > 14 },
                                fact.subtitle,
                            ).joinToString("\n")
                        }.orEmpty(),
                    )
                }
            }
          }
        }
    }
    PluginPageScreen(pluginRows)
    programScreen?.let { screen -> dev.droidtop.shell.gamepad.CatalogSheet(root = screen, onExit = { programScreen = null }) }
    infoScreen?.let { screen -> dev.droidtop.shell.gamepad.CatalogSheet(root = screen, onExit = { infoScreen = null }) }
    val linking = editingSource
    if (linking != null && library != null) {
        SourceLinkSheet(
            library = library,
            gameIds = gameIds,
            gameTitle = entry.title,
            versions = versions,
            source = linking,
            current = links?.link(linking.key),
            scope = scope,
            say = { line ->
                sourceStatus = if (line == null) sourceStatus - linking.key else sourceStatus + (linking.key to line)
                linksToken++
            },
            onClose = { editingSource = null },
        )
    }
}

/** Where the page's one cursor is: the action band's buttons, the tab strip, or the tab's rows. */
private enum class PageZone { ACTIONS, TABS, CONTENT }

/** The page's one list: the hero, the action band, the sticky tab strip, then the tab's rows. */
internal const val PAGE_ITEM_HERO = 0
internal const val PAGE_ITEM_TABS = 2
internal const val PAGE_ITEM_FIRST_ROW = 3

/** The hero's share of the window's height: Steam's hero ends at about 46 percent. */
internal const val PAGE_HERO_LANDSCAPE = 0.46f
internal const val PAGE_HERO_PORTRAIT = 0.30f

/** How quickly the tab strip's plate appears once rows pass under it (Steam: 0.1 s). */
internal const val PAGE_PLATE_MS = 100

/** The pinned strip's drop shadow: Steam's offset 4, blur 8, black at 50 percent. */
private const val PAGE_PLATE_SHADOW_OFFSET_DP = 4
private const val PAGE_PLATE_SHADOW_BLUR_DP = 8
private const val PAGE_PLATE_SHADOW_ALPHA = 0.5f

/**
 * The hero band: the game's hero art edge to edge, darkened toward the
 * page's ground so what is laid over it stays legible whatever the art is,
 * with the logo (or the name) bottom-left.
 * With only portrait art that art sits at its own shape on the right of a
 * plate, never stretched across the band; with none, the plate carries the
 * name, never a stand-in cover. The logo is large, bottom-left, as on
 * Steam's page; nothing else is stacked on the art.
 */
@Composable
private fun PageHero(entry: LibraryEntry, height: Dp) {
    val window = LocalShellWindow.current
    val title = GameNaming.displayName(entry.title)
    val hero = entry.heroUri
    val portraitArt = entry.artworkUri
    Box(modifier = Modifier.fillMaxWidth().height(height).background(MenuTokens.Card)) {
        if (hero != null) {
            AsyncImage(
                model = hero,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (portraitArt != null) {
            AsyncImage(
                model = portraitArt,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterEnd,
                modifier = Modifier.fillMaxSize().padding(vertical = Space.Sm, horizontal = window.edgePadding),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, MenuTokens.Ground))),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = window.edgePadding, vertical = Space.Md),
        ) {
            val logo = entry.logoUri
            if (logo != null) {
                AsyncImage(
                    model = logo,
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier.height(96.dp).widthIn(max = 360.dp),
                )
            } else {
                Text(
                    title,
                    color = MenuTokens.OnSurface,
                    style = TypeRole.screenTitle,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The action band, one row as on Steam's page: the ONE large primary action
 * at the left, the quiet facts strip beside it, Favourite and Options as
 * small icon buttons at the right edge, and under them the one line saying
 * why the primary action is what it is. [selectedButton] is the button the
 * cursor is on (0 primary, 1 Favourite, 2 Options), null when the cursor is
 * elsewhere. In portrait the strip sits under the buttons.
 */
@Composable
private fun PageActionBand(
    play: PcPlayState,
    favourite: Boolean,
    selectedButton: Int?,
    strip: List<Pair<String, String>>,
    portrait: Boolean,
    // Where Play is, for the launch to grow out of (PageFlood.kt).
    onPlayPlaced: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit,
    onPress: (Int) -> Unit,
) {
    val primary: @Composable () -> Unit = {
        ShellChip(
            play.verb,
            modifier = Modifier.onGloballyPositioned(onPlayPlaced),
            primary = true,
            large = true,
            enabled = play.pressable,
            selected = selectedButton == 0,
            onClick = { onPress(0) },
        )
    }
    val icons: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.Sm), verticalAlignment = Alignment.CenterVertically) {
            PageIconButton(
                glyph = if (favourite) "\u2605" else "\u2606",
                description = if (favourite) "Remove from favourites" else "Add to favourites",
                on = favourite,
                selected = selectedButton == 1,
                onClick = { onPress(1) },
            )
            PageIconButton(
                glyph = null,
                description = "Game options",
                on = false,
                selected = selectedButton == 2,
                onClick = { onPress(2) },
            )
        }
    }
    Column(modifier = Modifier.padding(top = Space.Sm)) {
        if (portrait) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                primary()
                Spacer(Modifier.weight(1f))
                icons()
            }
            if (strip.isNotEmpty()) PageFactsStrip(strip, Modifier.fillMaxWidth().padding(top = Space.Sm))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                primary()
                if (strip.isNotEmpty()) {
                    PageFactsStrip(strip, Modifier.weight(1f).padding(horizontal = Space.Xl))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                icons()
            }
        }
        if (play.detail.isNotBlank()) {
            Text(
                play.detail,
                color = if (play.pressable) MenuTokens.Value else MenuTokens.OnSurfaceDisabled,
                style = TypeRole.supporting,
                modifier = Modifier.padding(top = Space.Sm),
            )
        }
    }
}

/**
 * A small square button for the cursor to rest on (Steam's gear and favourite beside the status panel): a
 * glyph, never a word, with a spoken description. At rest it is a quiet plate; under the cursor it turns
 * solid, as every quiet button does. Null [glyph] is the gear (the game's options), drawn, which turns a
 * quarter as the cursor arrives (DroidDeck's cog, ui/FrontEndWidgets.kt at 9310d19).
 */
@Composable
private fun PageIconButton(glyph: String?, description: String, on: Boolean, selected: Boolean, onClick: () -> Unit) {
    val lit = selected && dev.droidtop.shell.gamepad.input.PadModality.showsFocus
    val ink = when {
        lit -> MenuTokens.OnSelected
        on -> MenuTokens.Favourite
        else -> MenuTokens.OnSurface
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = description }
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
            .selectionFrame(selected, Corners.Crisp, selectedFill = MenuTokens.Selected),
    ) {
        if (glyph != null) {
            Text(glyph, color = ink, style = MaterialTheme.typography.titleLarge)
        } else {
            val turn = animateFloatAsState(if (lit) GEAR_TURN_DEGREES else 0f, Motion.focus(), label = "gear")
            QuickGlyphIcon(
                glyph = QuickGlyph.SETTINGS,
                tint = ink,
                modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = turn.value },
            )
        }
    }
}

/** How far the gear turns as the cursor reaches it. */
private const val GEAR_TURN_DEGREES = 90f

/**
 * The status panel beside Play (Steam's): each fact a small capitals label over its value, quiet, scrolling
 * sideways rather than clipping at a large text size.
 */
@Composable
private fun PageFactsStrip(facts: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Space.Xl),
    ) {
        facts.forEach { (label, value) ->
            Column(verticalArrangement = Arrangement.spacedBy(Space.Hair)) {
                Text(label.uppercase(), color = MenuTokens.OnSurfaceMuted, style = TypeRole.eyebrow, maxLines = 1)
                Text(value, color = MenuTokens.OnSurface, style = TypeRole.value, maxLines = 1)
            }
        }
    }
}

/**
 * The tab strip, L1 and R1 at its ends: while the page is open it owns
 * the shoulders. It scrolls sideways, so no tab is ever clipped at a large
 * text size or in portrait.
 */
@Composable
private fun PageTabStrip(
    tabs: Array<PageTab>,
    tab: Int,
    cursorOnTabs: Boolean,
    state: LazyListState,
    shoulderGlyphs: Boolean,
    onSelect: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // A shoulder with nowhere to go is drawn at half strength, as on the library's strip.
        if (shoulderGlyphs) ShoulderGlyph("L1", badge = true, dimmed = tab <= 0, modifier = Modifier.padding(end = Space.Sm))
        LazyRow(
            state = state,
            contentPadding = PaddingValues(vertical = Space.Sm),
            horizontalArrangement = Arrangement.spacedBy(Space.Sm, Alignment.CenterHorizontally),
            modifier = Modifier.weight(1f),
        ) {
            items(count = tabs.size, key = { "tab:$it" }) { index ->
                ShellChip(
                    tabs[index].label,
                    on = index == tab,
                    tab = true,
                    selected = cursorOnTabs && index == tab,
                    onClick = { onSelect(index) },
                )
            }
        }
        if (shoulderGlyphs) {
            ShoulderGlyph("R1", badge = true, dimmed = tab >= tabs.lastIndex, modifier = Modifier.padding(start = Space.Sm))
        }
    }
}

/** The A hint on the action band: what the button under the cursor does. */
internal fun pageActionLabel(button: Int, primaryVerb: String): String = when (button) {
    0 -> primaryVerb
    1 -> "Favourite"
    else -> "Options"
}

/** A fact from outside the library as a row of Overview: A opens its page of rows or its address. */
private fun dev.droidtop.shell.gamepad.GameInfoRow.toPageFact(
    context: android.content.Context,
    openScreen: (dev.droidtop.library.settings.CatalogScreen) -> Unit,
): PageFact {
    val page = screen
    val address = url
    val action: (() -> Unit)? = if (page != null) {
        ({ openScreen(page) })
    } else if (address != null) {
        ({ dev.droidtop.shell.gamepad.openAddress(context, address) })
    } else {
        null
    }
    return PageFact(title, value, subtitle, onActivate = action, tip = address, tab = PageTab.OVERVIEW)
}

/** One fact on the page: a row title, what it says, and what A does on it when it does anything. */
/**
 * The Ownership row of a game the account does not simply own: the holding's
 * label and one sentence, or null for an owned game, a folder game or a Wine
 * shortcut. The lender's name is not known to droidtop yet, so a shared game
 * names the store's family instead. Pure.
 */
internal fun ownershipSentence(entry: LibraryEntry): Pair<String, String>? {
    val holding = entry.pcInfo?.holding ?: return null
    val store = entry.storeLabel() ?: return null
    return when (holding) {
        dev.droidtop.library.stores.StoreHolding.OWNED -> null
        dev.droidtop.library.stores.StoreHolding.FAMILY -> holding.label to "Shared by another account in your $store family"
        dev.droidtop.library.stores.StoreHolding.FREE -> holding.label to "Free to play on $store. It counts as yours once you install it or play it"
        dev.droidtop.library.stores.StoreHolding.NOT_OWNED -> holding.label to "This game left your library. $store may refuse to start it"
    }
}

internal data class PageFact(
    val title: String,
    val value: String? = null,
    val subtitle: String? = null,
    val onActivate: (() -> Unit)? = null,
    /** The long form of the row (a full path, a link), in the tooltip only. */
    val tip: String? = null,
    /** The tab this row lives on when its title cannot say ([pageTabOf]). */
    val tab: PageTab? = null,
)

/**
 * The page's rows, in the order a store page lists them: how you have
 * played it, what you have of it, how it runs, what it is. Only facts that
 * exist; a missing fact is not a row saying "unknown".
 */
private fun pageRows(
    entry: LibraryEntry,
    play: PcPlayState,
    runner: dev.droidtop.library.ResolvedRunner?,
    siblings: List<LibraryEntry>,
    scrapeStatus: String?,
    sourceRows: List<PageFact> = emptyList(),
    latest: String? = null,
    onOpenLink: (String) -> Unit = {},
    onScrape: () -> Unit,
): List<PageFact> = buildList {
    // Each fact is on ONE tab, and none repeats the facts strip under the
    // action band (last played, play time, version, size, runs with): Overview
    // is the game, Versions what is installed against what is out, Extras
    // what comes with it, Details where it lives and what runs it.
    if (!entry.hideMetadata) {
        entry.description?.takeIf { it.isNotBlank() }?.let { add(PageFact("About", subtitle = it)) }
    }
    if (entry.isUnscraped()) {
        add(
            PageFact(
                "Not scraped yet",
                value = scrapeStatus ?: "Scrape",
                subtitle = "No cover or description yet",
                onActivate = onScrape,
            ),
        )
    }
    if (entry.pcInfo?.installed == false) add(PageFact("Install state", "Not installed"))
    if (entry.playCount > 0) add(PageFact("Times played", entry.playCount.toString()))
    entry.pcInfo?.compatibility?.let { add(PageFact("Compatibility", subtitle = it.summary() + ". Other people's results on other hardware, not a verdict.")) }

    // A version comes from a folder's own name (docs/SPEC.md 7m); a store
    // row has none to derive.
    val availableVersions = installedVersions(entry, siblings)
    if (availableVersions.isNotEmpty()) {
        add(PageFact("Version", availableVersions.first(), subtitle = if (availableVersions.size > 1) "Also here: ${availableVersions.drop(1).joinToString(", ")}" else null))
    }
    latest?.let { add(PageFact("Latest", it)) }
    entry.availableUpdate?.let { add(PageFact("Update", it, subtitle = GameUpdates.line(it))) }
    addAll(sourceRows)
    entry.pcInfo?.takeIf { entry.isStoreRow() && it.installed }?.let { pc ->
        pc.installedVersion?.takeIf { availableVersions.isEmpty() }?.let { add(PageFact("Version", it)) }
        // A store with no way to tell droidtop says so; it is never shown as up to date.
        when {
            // Already said above, under the same row.
            entry.availableUpdate != null -> Unit
            pc.update == dev.droidtop.library.StoreUpdate.UNKNOWN ->
                add(PageFact("Update", "Not known", subtitle = "${dev.droidtop.library.PcSource.of(entry)?.label() ?: "The store"} has not said whether a newer build exists yet; it is asked in the background, and an install made before its build was recorded is checked after its next update"))
            pc.update == dev.droidtop.library.StoreUpdate.CURRENT ->
                add(PageFact("Update", "Up to date", subtitle = "${dev.droidtop.library.PcSource.of(entry)?.label() ?: "The store"} says this is the newest build"))
            else -> Unit
        }
    }

    entry.manualUri?.let { add(PageFact("Manual", "Available")) }
    entry.videoUri?.let { add(PageFact("Video", "Available")) }
    entry.links.forEach { link ->
        add(PageFact(link.label, value = "Open", onActivate = { onOpenLink(link.url) }, tip = link.url, tab = PageTab.EXTRAS))
    }
    if (!entry.hideMetadata) {
        sourcesLine(entry)?.let { add(PageFact("Where these facts came from", subtitle = it)) }
    }

    val owned = siblings.mapNotNull { it.ownership() }
    val ownedLine = owned.ownershipLabel().removePrefix("Owned on ").takeIf { it.isNotBlank() }
    val folderPath = entry.groupingPath()
    add(PageFact("Store", ownedLine ?: (dev.droidtop.library.PcSource.of(entry) as? dev.droidtop.library.PcSource.Store)?.label() ?: "Your folders"))
    // How the account holds it, only when that is not simply owned (docs/SPEC.md 7g, "Ownership is a fact").
    ownershipSentence(entry)?.let { (value, sentence) -> add(PageFact("Ownership", value, subtitle = sentence)) }
    // Where it lives as a person names it; the whole path is the tooltip.
    folderPath?.let { add(PageFact("Install location", friendlyLocation(it), tip = it)) }
    // The name as it is on disk, beside the title drawn from it (docs/SPEC.md
    // 7n): the raw name is never altered, only parsed.
    folderPath?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() && it != GameNaming.displayName(entry.title) }
        ?.let { add(PageFact("Folder name", it)) }
    entry.engineLabel()?.let { add(PageFact("Engine", it)) }
    // The play state's own line (a store game's "Downloads 590 MB", an install step) is the action band's
    // and is not a statement about the runner, so it is never drawn under this label; a store stage with
    // no runner worked out leaves the row out (Droidtop/tracker#371).
    if (runner != null || play.store == null) {
        add(
            PageFact(
                "Runner",
                runner?.label ?: if (play.pressable && !play.chooseEngine) "" else play.verb,
                subtitle = if (runner != null) runner.reason else play.detail,
            ),
        )
    }
    if (!entry.hideMetadata) {
        aboutFacts(entry).forEach { (label, value) -> add(PageFact(label, value)) }
        entry.players?.takeIf { it.isNotBlank() }?.let { add(PageFact("Players", it)) }
    }
}

private data class FolderSizeStamp(val path: String, val modified: Long, val length: Long)

private val folderSizeCache = ConcurrentHashMap<FolderSizeStamp, Long>()

/** Folder sizes are read only for the open game page, on IO, and reused by path metadata stamp. */
private fun folderSizeBytes(path: String): Long {
    val folder = File(path)
    if (!folder.isDirectory) return 0L
    val stamp = FolderSizeStamp(folder.absolutePath, folder.lastModified(), folder.length())
    folderSizeCache[stamp]?.let { return it }
    val size = runCatching {
        folder.walkTopDown().sumOf { file -> if (file.isFile) file.length() else 0L }
    }.getOrDefault(0L)
    if (folderSizeCache.size > 256) folderSizeCache.clear()
    folderSizeCache[stamp] = size
    return size
}

/**
 * "2 h 15 min, played 7 times", "Never played": the one play-time sentence of
 * the facts strip and the Details tab. Pure, for the tests.
 */
internal fun playtimeLine(seconds: Long, playCount: Int): String {
    if (seconds <= 0 && playCount <= 0) return "Never played"
    val time = playtimeShort(seconds).takeIf { seconds > 0 }
    val times = when (playCount) {
        0 -> null
        1 -> "played once"
        else -> "played $playCount times"
    }
    return listOfNotNull(time, times).joinToString(", ").replaceFirstChar { it.uppercase() }
}

/**
 * The labelled "About this game" facts, in the order a store page lists
 * them; only the ones that exist (docs/SPEC.md 7h).
 */
internal fun aboutFacts(entry: LibraryEntry): List<Pair<String, String>> = listOfNotNull(
    entry.developer?.let { "Developer" to it },
    entry.publisher?.let { "Publisher" to it },
    entry.releaseDate?.let { formatReleaseDate(it) }?.let { "Released" to it },
    entry.genre?.let { "Genre" to it },
    entry.series?.let { "Series" to it },
    // ES-DE's 0-1 rating, shown on the five-star scale ES-DE draws it on.
    entry.rating?.let { "Rating" to String.format(java.util.Locale.US, "%.1f / 5", it * 5) },
)

/**
 * ES-DE's `YYYYMMDDT000000` as a date a person reads, in their locale;
 * null for anything that is not a full date (nothing here widens a year
 * into a day).
 */
internal fun formatReleaseDate(raw: String): String? {
    val parsed = runCatching {
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply {
            isLenient = false
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.parse(raw.take(8))
    }.getOrNull() ?: return null
    return java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }.format(parsed)
}

/**
 * "Description from IGDB. Cover and hero art from SteamGridDB." -- each
 * source once, with the fields it gave, in the order the fields are
 * listed ([FieldSources.LABELS]); "you" for what the metadata editor
 * changed. Null when nothing recorded a source (docs/SPEC.md 7h).
 */
internal fun sourcesLine(entry: LibraryEntry): String? {
    if (entry.fieldSources.isEmpty()) return null
    val order = FieldSources.LABELS.keys.toList()
    val bySource = entry.fieldSources.entries
        .sortedBy { order.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        .groupBy({ it.value }, { FieldSources.LABELS[it.key] ?: it.key })
    return bySource.entries.joinToString(" ") { (source, fields) ->
        val list = fields.mapIndexed { i, field -> if (i == 0) field else field.lowercase() }
        val joined = if (list.size == 1) list.single() else list.dropLast(1).joinToString(", ") + " and " + list.last()
        if (source == FieldSources.EDITED) "$joined edited by you." else "$joined from $source."
    }
}

/**
 * The page's tabs, in the order the strip draws them (docs/SPEC.md 7i, "The
 * game page"). [emptyLine] is what a tab with no row says, so an empty tab
 * is an answer and not a blank.
 */
internal enum class PageTab(val label: String, val emptyLine: String) {
    OVERVIEW("Overview", "Nothing is known about this game yet"),
    VERSIONS("Versions and updates", "No version information for this game"),
    EXTRAS("Extras", "No extras for this game yet"),
    DETAILS("Details", "No details for this game yet"),
}

/**
 * Which tab a fact row lives under, by its title: the one place that says
 * so, so a new row lands under a tab by adding its title here and the rows
 * ([pageRows]) stay a plain list. What is not named is a detail.
 */
internal fun pageTabOf(title: String): PageTab = when (title) {
    "About", "Not scraped yet", "Compatibility", "Install state", "Times played" -> PageTab.OVERVIEW
    "Version", "Latest", "Update", CHECK_ROW -> PageTab.VERSIONS
    "Manual", "Video", "Where these facts came from" -> PageTab.EXTRAS
    else -> PageTab.DETAILS
}

/** Every row under its tab, in row order; the multi-part list leads Overview. Pure, for the tests. */
internal fun groupRowsByTab(rows: List<PageFact>, parts: List<PageFact>): Map<PageTab, List<PageFact>> =
    PageTab.values().associateWith { tab ->
        val own = rows.filter { (it.tab ?: pageTabOf(it.title)) == tab }
        // An available update leads Versions: it is what a person opens the tab for.
        val ordered = if (tab == PageTab.VERSIONS) own.sortedBy { if (it.title == "Update") 0 else 1 } else own
        (if (tab == PageTab.OVERVIEW) parts else emptyList()) + ordered
    }


/**
 * The parts of a game of several (`Week 1`, `Week 2`, `Part3` ...) as rows
 * for Overview: one heading row with the count, then each part with the
 * newest version of it that is here. Empty for a game of one part. The fold
 * is one pass over this one game's folders, off the main thread.
 */
internal fun partFacts(siblings: List<LibraryEntry>): List<PageFact> {
    val game = LibraryGrouping.group(siblings)
        .map { it.game }
        .filter { it.segments.size > 1 }
        .maxByOrNull { it.segments.size }
        ?: return emptyList()
    return buildList {
        add(PageFact("Parts", "${game.segments.size} parts", subtitle = "Played in the order the folders name them"))
        game.segments.forEach { segment ->
            add(PageFact(segment.label, segment.versions.firstOrNull()?.version?.takeIf { it.isNotBlank() }))
        }
    }
}

/**
 * The versions a game's own folders name (docs/SPEC.md 7m), newest-first
 * as the folders listed them, this entry's first; a store row has none to
 * derive. The ONE place the page reads "installed version" from, for the
 * Version row and the facts strip alike.
 */
internal fun installedVersions(entry: LibraryEntry, siblings: List<LibraryEntry>): List<String> {
    val own = entry.groupingPath()?.let { GameNaming.derive(it).version.takeIf(String::isNotBlank) }
    val others = siblings.mapNotNull { it.groupingPath() }
        .mapNotNull { GameNaming.derive(it).version.takeIf { v -> v.isNotBlank() } }
    return (listOfNotNull(own) + others).distinct()
}

/**
 * "0.9.5" alone, or "0.9.5, 0.9.6 available" when a source knows a newer
 * one; the latest alone when nothing here names a version; null for
 * neither. The wording of "available" is [GameUpdates.line]'s, so the page
 * says it the way the card and the menu do.
 */
internal fun versionFact(installed: String?, latest: String?): String? = when {
    installed != null && latest != null -> "$installed, ${GameUpdates.line(latest)}"
    installed != null -> installed
    latest != null -> GameUpdates.line(latest)
    else -> null
}

/**
 * The page's quiet facts strip, label then value, only the facts that
 * exist: when it was last played, play time, the version installed against
 * the latest known, size on this device (or to download), and what runs
 * it. [formatSize] is the platform's byte formatter, passed in so this
 * stays pure.
 */
internal fun factsStrip(
    entry: LibraryEntry,
    now: Long,
    folderSizeBytes: Long?,
    installedVersion: String?,
    runsWith: String?,
    formatSize: (Long) -> String,
): List<Pair<String, String>> = listOfNotNull(
    "Last played" to (entry.lastPlayedEpochMs?.let { lastPlayedPhrase(now, it).replaceFirstChar { c -> c.uppercase() } } ?: "Never"),
    "Play time" to playtimeLine(entry.playtimeSeconds, entry.playCount),
    versionFact(installedVersion, entry.availableUpdate)?.let { "Version" to it },
    (folderSizeBytes ?: entry.pcInfo?.sizeBytes)?.takeIf { it > 0 }?.let { "Size" to formatSize(it) },
    runsWith?.takeIf { it.isNotBlank() }?.let { "Runs with" to it },
)

private const val DAY_MS = 24L * 60 * 60 * 1000

/** "today", "yesterday", "5 days ago", "3 weeks ago", "4 months ago". Pure, for the tests and the hero card. */
internal fun lastPlayedPhrase(now: Long, last: Long): String {
    val days = ((now - last) / DAY_MS).coerceAtLeast(0)
    return when {
        days < 1 -> "today"
        days == 1L -> "yesterday"
        days < 14 -> "$days days ago"
        days < 60 -> "${days / 7} weeks ago"
        days < 365 -> "${days / 30} months ago"
        else -> "over a year ago"
    }
}

/** "2 h 5 min", "45 min", "Under a minute", "Not played yet": the short play time of the strip and the hero card. */
internal fun playtimeShort(seconds: Long): String {
    if (seconds <= 0) return "Not played yet"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 && minutes > 0 -> "$hours h $minutes min"
        hours > 0 -> "$hours h"
        minutes > 0 -> "$minutes min"
        else -> "Under a minute"
    }
}

/** The one quiet line under the home's hero card: "Played yesterday · 2 h 5 min". */
internal fun heroCaption(entry: LibraryEntry, now: Long): String {
    val last = entry.lastPlayedEpochMs?.let { "Played ${lastPlayedPhrase(now, it)}" }
    val time = playtimeShort(entry.playtimeSeconds).takeIf { entry.playtimeSeconds > 0 }
    return listOfNotNull(last, time).joinToString(" · ").ifEmpty { "Not played yet" }
}
