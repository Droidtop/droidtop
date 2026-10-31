package dev.droidtop.shell.gamepad.pc

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.droidtop.library.GameNaming
import dev.droidtop.library.GameUpdates
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.isUnscraped
import dev.droidtop.shell.gamepad.ActionRow
import dev.droidtop.shell.gamepad.AnchoredMenu
import dev.droidtop.shell.gamepad.CatalogDetailStrip
import dev.droidtop.shell.gamepad.Corners
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.LocalValueColumnWidth
import dev.droidtop.shell.gamepad.MenuRow
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.ShellChip
import dev.droidtop.shell.gamepad.ShoulderGlyph
import dev.droidtop.shell.gamepad.Space
import dev.droidtop.shell.gamepad.TypeRole
import dev.droidtop.shell.gamepad.anchorFor
import dev.droidtop.shell.gamepad.fadeWhileMoreSideways
import dev.droidtop.shell.gamepad.groundBackground
import dev.droidtop.shell.gamepad.input.DeclareLayerHints
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.LocalShellMenus
import dev.droidtop.shell.gamepad.input.menuStep
import dev.droidtop.shell.gamepad.input.onPad
import dev.droidtop.shell.gamepad.input.ownPadButtons
import dev.droidtop.shell.gamepad.keepInView
import dev.droidtop.shell.gamepad.labelCase
import dev.droidtop.shell.gamepad.padRow
import dev.droidtop.shell.gamepad.rememberAnchor
import dev.droidtop.shell.gamepad.requestFocusWhenAttached
import dev.droidtop.shell.gamepad.selectionFrame
import dev.droidtop.shell.gamepad.showsShoulderGlyphs
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import dev.droidtop.shell.gamepad.theme.UiSound
import dev.droidtop.ui.shared.Motion

/**
 * The game page's frame, the one design every kind of game's page is drawn in (docs/SPEC.md 7i, "The page's
 * frame"; the owner, Droidtop/tracker#845: "One page for every kind of game"). The PC and store game page
 * ([PcGamePage]) and the console game's page ([dev.droidtop.shell.gamepad.EntryDetailScreen]) each work out their
 * own facts and hand them here; the frame draws them and moves the one cursor. From the top:
 *
 * - **The hero band** ([PageHero]): the game's hero art full-bleed, fading into the page, with its logo (or its
 *   name) bottom-left and the status row under it ([PageStatusRow]). The whole page is ONE scrolling list, as
 *   Steam's: with the cursor down in the tab's rows the hero and the action band scroll away and the tab strip
 *   stays pinned at the top, gaining a plate once rows pass under it.
 * - **The action band** ([PageActionBand]): ONE large primary action that says what A does ([PcPlayState]), the
 *   quiet facts strip beside it, then Edit metadata (when the page has [editMetadataRows]), Options and a small
 *   Favourite button, and the one-line reason under them. Edit metadata and Options open beside their buttons
 *   ([AnchoredMenu]) with the rows the page gives.
 * - **The tab strip** ([PageTabStrip]): every [PageTab], always drawn, owning L1/R1 while the page is open (the
 *   "nearest strip takes them" rule, docs/SPEC.md 7j, "Gaming controls").
 * - **The tab's rows** ([rowsByTab]): Settings' rows ([MenuRow]), an empty tab saying its [PageTab.emptyLine], and
 *   while the cursor is in them the selected row's full text in the detail strip under the list.
 *
 * Every word the frame draws itself (each tab's empty line, the status row's labels, the L2 hint, the shared Options
 * rows) names what the page is about by its [PageKind], read from the entry: "this game", or "this app" on an Android
 * app's page (the owner, Droidtop/tracker#845: "One page for every kind of game, apps and the source record").
 *
 * The pad comes through one `onPad` handler on the frame's root, which moves ONE cursor through three zones (the
 * band's buttons, the tabs, the tab's rows): Up and Down between zones and rows, Left and Right along the buttons
 * or the tabs, L1/R1 the tab from anywhere, A presses what the cursor is on, X is the primary action, Y Favourite,
 * L2 the game's menu ([onGameMenu], or Options when the page has no other), Select a row's own options or the
 * game's menu, and B is [onBack] from the outermost node ([ownPadButtons]). No control takes Compose focus of its
 * own, and a tap on a button, a tab, a row or a menu row is the same press. The footer names the presses
 * ([DeclareLayerHints]); the frame draws no hint row of its own.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun GamePageFrame(
    entry: LibraryEntry,
    play: PcPlayState,
    statuses: List<PageStatus>,
    strip: List<Pair<String, String>>,
    rowsByTab: Map<PageTab, List<PageFact>>,
    // Edit metadata's rows, Scrape first ([scrapeRow]), each given the menu's close; null draws no Edit metadata
    // button (an app has no metadata of its own to edit).
    editMetadataRows: ((close: () -> Unit) -> List<ActionRow>)?,
    // Options' rows, only the ones this game has, each given the menu's close.
    optionsRows: (close: () -> Unit) -> List<ActionRow>,
    // The primary action, from the band and from X; the page decides what it does in each [play] state.
    onPrimary: () -> Unit,
    onToggleFavorite: () -> Unit,
    // The game's whole menu, which L2 and Select open; null opens Options instead.
    onGameMenu: (() -> Unit)?,
    onBack: () -> Unit,
    // What the focus request is called in the log.
    focusLabel: String,
    modifier: Modifier = Modifier,
    // Where the primary button is, for the launch to grow out of (PageFlood.kt).
    onPlayPlaced: (LayoutCoordinates) -> Unit = {},
    // Whenever the cursor moves: a row armed for a second press disarms.
    onCursorMoved: () -> Unit = {},
) {
    val window = LocalShellWindow.current
    val tabs = remember { PageTab.values() }
    val kind = PageKind.of(entry)
    val hasEditMetadata = editMetadataRows != null
    val buttons = remember(hasEditMetadata) { pageButtons(hasEditMetadata) }
    var editOpen by remember(entry.id) { mutableStateOf(false) }
    var optionsOpen by remember(entry.id) { mutableStateOf(false) }
    val editAnchor = rememberAnchor()
    val optionsAnchor = rememberAnchor()

    // ONE cursor in three zones: the buttons, the tabs, the tab's rows.
    var zone by remember(entry.id) { mutableStateOf(PageZone.ACTIONS) }
    var button by remember(entry.id) { mutableIntStateOf(0) }
    var tab by remember(entry.id) { mutableIntStateOf(0) }
    var row by remember(entry.id) { mutableIntStateOf(0) }
    var heldStep by remember { mutableStateOf(false) }
    val current = rowsByTab[tabs[tab]].orEmpty()
    val selectedButton = buttons.getOrElse(button) { PageButton.PRIMARY }
    // ONE scrolling page, as Steam's: the hero, the play row, the tab strip
    // (sticky: it pins to the top once rows pass under it) and the tab's rows.
    val listState = rememberLazyListState()
    val tabState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus, focusLabel) }
    LaunchedEffect(tab) { tabState.keepInView(tab) }
    val cursorMoved by rememberUpdatedState(onCursorMoved)
    LaunchedEffect(zone, row, tab) { cursorMoved() }
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

    fun pressButton(which: PageButton) {
        when (which) {
            PageButton.PRIMARY -> onPrimary()
            PageButton.EDIT_METADATA -> editOpen = true
            PageButton.OPTIONS -> optionsOpen = true
            PageButton.FAVOURITE -> onToggleFavorite()
        }
    }
    val openGameMenu: () -> Unit = onGameMenu ?: { optionsOpen = true }

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
    val hints = remember(zone, selectedButton, row, tab, verb, play.pressable, play.chooseEngine, current, kind) {
        listOf(
            HintBinding(GamepadAction.A, if (zone == PageZone.ACTIONS) pageActionLabel(selectedButton, verb) else "Select") {
                when (zone) {
                    PageZone.ACTIONS -> selectedButton != PageButton.PRIMARY || play.pressable
                    PageZone.TABS -> current.isNotEmpty()
                    PageZone.CONTENT -> current.getOrNull(row)?.onActivate != null
                }
            },
            HintBinding(GamepadAction.X, verb) { play.pressable || play.chooseEngine },
            HintBinding(GamepadAction.Y, "Favourite"),
            HintBinding(GamepadAction.L2, kind.optionsHint),
            HintBinding(GamepadAction.SELECT, "Copy options") { zone == PageZone.CONTENT && current.getOrNull(row)?.onOptions != null },
            HintBinding(GamepadAction.B, "Back"),
        )
    }
    DeclareLayerHints(hints, menusReachable = true)

    Column(
        modifier = modifier
            .fillMaxSize()
            // B from the outermost node; the cursor's own presses
            // nearer the focus target, so they are answered first.
            .ownPadButtons(onBack = onBack)
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
                                val next = menuStep(button, buttons.size, step)
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
                    // A page in a window of its own never reaches the shell's root handler, so
                    // the two menu pills on the footer are answered here.
                    GamepadAction.START -> shellMenus?.openLeft?.invoke()
                    GamepadAction.R2 -> shellMenus?.openQuick?.invoke()
                    GamepadAction.A -> when (zone) {
                        PageZone.ACTIONS -> pressButton(selectedButton)
                        PageZone.TABS -> if (current.isNotEmpty()) {
                            zone = PageZone.CONTENT
                            row = 0
                        }
                        PageZone.CONTENT -> current.getOrNull(row)?.onActivate?.invoke()
                    }
                    // X plays from anywhere on the page, as on the library tile; Y is Favourite
                    // (owner decision, Droidtop/tracker#644 slice 4, proposal 6.C).
                    GamepadAction.X -> onPrimary()
                    GamepadAction.Y -> onToggleFavorite()
                    GamepadAction.L2 -> openGameMenu()
                    // Select on a copy's row is that copy's Options; anywhere else the game's menu.
                    GamepadAction.SELECT -> current.getOrNull(row)?.takeIf { zone == PageZone.CONTENT }?.onOptions?.invoke()
                        ?: openGameMenu()
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
                item(key = "hero") { PageHero(entry, heroHeight, statuses) }
                item(key = "band") {
                    Box(Modifier.padding(horizontal = window.edgePadding)) {
                        PageActionBand(
                            play = play,
                            favourite = entry.favorite,
                            buttons = buttons,
                            selectedButton = if (zone == PageZone.ACTIONS) selectedButton else null,
                            strip = strip,
                            portrait = window.portrait,
                            onPlayPlaced = onPlayPlaced,
                            editModifier = Modifier.anchorFor(editAnchor),
                            optionsModifier = Modifier.anchorFor(optionsAnchor),
                            onPress = { which ->
                                zone = PageZone.ACTIONS
                                button = buttons.indexOf(which).coerceAtLeast(0)
                                pressButton(which)
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
                            Text(tabs[tab].emptyLine(kind), color = MenuTokens.OnSurfaceMuted, style = TypeRole.body)
                        }
                    }
                } else {
                    // A tab whose rows have no second line keeps no room for one: a lone "Times played 14"
                    // was a four-line box with one line in it (Droidtop/tracker#662).
                    val summaryLines = if (current.any { it.subtitle != null }) 2 else 0
                    itemsIndexed(current, key = { index, fact -> "$tab:$index:${fact.title}" }) { index, fact ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = window.edgePadding, vertical = MenuTokens.RowSpacing / 2),
                        ) {
                            MenuRow(
                                title = fact.title,
                                subtitle = fact.subtitle,
                                value = fact.value,
                                switchOn = fact.on,
                                statusMark = fact.mark,
                                chevron = fact.onActivate != null,
                                selected = zone == PageZone.CONTENT && row == index,
                                uniformHeight = true,
                                uniformSummaryLines = summaryLines,
                                ownScrollKeeping = true,
                                onClick = {
                                    zone = PageZone.CONTENT
                                    row = index
                                    fact.onActivate?.invoke()
                                },
                                // A copy's Options, Select's on a pad, is a long press by touch (Droidtop/tracker#839).
                                onLongClick = fact.onOptions?.let { options ->
                                    {
                                        zone = PageZone.CONTENT
                                        row = index
                                        options()
                                    }
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
                        // The long form (a full path, a link) in full, for reading and Info's Copy.
                        fact.tip,
                    ).joinToString("\n")
                }.orEmpty(),
            )
        }
    }
    // Edit metadata and Options, each beside its button (the owner, Droidtop/tracker#845: "Keep "edit metadata" as a
    // button, put "scrape" IN the edit metadata panel, and move the rest to an options menu").
    if (editOpen && editMetadataRows != null) {
        AnchoredMenu(
            anchor = editAnchor.value,
            title = EDIT_METADATA,
            rows = editMetadataRows { editOpen = false },
            onDismiss = { editOpen = false },
        )
    }
    if (optionsOpen) {
        AnchoredMenu(
            anchor = optionsAnchor.value,
            title = OPTIONS,
            rows = optionsRows { optionsOpen = false },
            onDismiss = { optionsOpen = false },
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
 * What a page is about, and so the words the frame uses for it (docs/SPEC.md 7i, "Apps on the page"): a game, or an
 * Android app (the owner, Droidtop/tracker#845: "One page for every kind of game, apps and the source record"). The
 * one place those words are chosen: the tabs' empty lines, the status row, Activity's rows, the L2 hint and Options'
 * shared rows all read them from here, so no page keeps its own copy. [noun] is "this game"'s word, [verb] the
 * primary action's ("Play", "Open"), [done] what Activity says was done with it.
 */
internal enum class PageKind(val noun: String, val verb: String, val done: String, val timeTitle: String) {
    GAME("game", "play", "played", "Play time"),
    APP("app", "open", "opened", "Time used"),
    ;

    /** The primary action's word on the band ("Play", "Open"). */
    val primary: String get() = verb.replaceFirstChar { it.uppercase() }

    /** What L2 opens, as the footer names it. */
    val optionsHint: String get() = noun.replaceFirstChar { it.uppercase() } + " options"

    companion object {
        /** An installed Android app's page is an app's; every other entry's is a game's. */
        fun of(entry: LibraryEntry): PageKind = if (entry.kind == LibraryEntryKind.NATIVE_ANDROID_APP) APP else GAME
    }
}

/** The page's tabs, in the order the strip draws them (docs/SPEC.md 7i, "The game page"). */
internal enum class PageTab(val label: String) {
    OVERVIEW("Overview"),
    VERSIONS("Versions"),
    // How the person has played it (the owner, Droidtop/tracker#845: an Activity tab).
    ACTIVITY("Activity"),
    EXTRAS("Extras"),
    DETAILS("Details"),
    // The source's own thread (the owner, Droidtop/tracker#845: "put a "show thread" tab"); its first post comes later.
    THREAD("Show thread"),
    ;

    /** What this tab says with no row, so an empty tab is an answer and not a blank, worded for [kind]. Pure. */
    fun emptyLine(kind: PageKind): String = when (this) {
        OVERVIEW -> "Nothing is known about this ${kind.noun} yet"
        VERSIONS -> "No version information for this ${kind.noun}"
        ACTIVITY -> "You have not ${kind.done} this ${kind.noun} yet"
        EXTRAS -> "No extras for this ${kind.noun} yet"
        DETAILS -> "No details for this ${kind.noun} yet"
        THREAD -> "No thread is linked to this ${kind.noun}"
    }
}

/** One fact on the page: a row title, what it says, and what A does on it when it does anything. */
internal data class PageFact(
    val title: String,
    val value: String? = null,
    val subtitle: String? = null,
    val onActivate: (() -> Unit)? = null,
    /** The long form of the row (a full path, a link): on the detail strip while the row is selected. */
    val tip: String? = null,
    /**
     * The tab this row lives on, said by every row that makes one: a row is never placed by matching its title,
     * so renaming a row cannot move it, and a new row cannot land on a tab by accident (Droidtop/tracker#678).
     */
    val tab: PageTab,
    /** What Select does on this row (a copy's Options); null for a row that has none. */
    val onOptions: (() -> Unit)? = null,
    /** An on/off row's state, drawn as a switch (show, then say: Droidtop/tracker#738); null for any other row. */
    val on: Boolean? = null,
    /** A state drawn as its glyph beside the title (synced, offline, working); null for none. */
    val mark: dev.droidtop.library.settings.StatusMark? = null,
)

/** A fact from outside the library as a row of [tab]: A opens its page of rows or its address. */
internal fun dev.droidtop.shell.gamepad.GameInfoRow.toPageFact(
    context: android.content.Context,
    tab: PageTab,
    openScreen: (dev.droidtop.library.settings.CatalogScreen) -> Unit,
): PageFact {
    val page = screen
    val address = url
    val step = this.run
    val action: (() -> Unit)? = if (step != null) {
        ({ step(context) })
    } else if (page != null) {
        ({ openScreen(page) })
    } else if (address != null) {
        ({ dev.droidtop.library.Links.open(context, address) })
    } else {
        null
    }
    return PageFact(title, value, subtitle, onActivate = action, tip = address, tab = tab)
}

/** The action band's buttons, in the order they are drawn and the cursor moves along them. */
internal enum class PageButton { PRIMARY, EDIT_METADATA, OPTIONS, FAVOURITE }

/** The band's buttons: Edit metadata only on a page that has metadata to edit ([hasEditMetadata]). Pure. */
internal fun pageButtons(hasEditMetadata: Boolean): List<PageButton> =
    PageButton.values().filter { it != PageButton.EDIT_METADATA || hasEditMetadata }

/** The two buttons beside the primary action, and the panels they open (Droidtop/tracker#845). */
internal const val EDIT_METADATA = "Edit metadata"
internal const val OPTIONS = "Options"

/** Edit metadata's first row (the owner, Droidtop/tracker#845: "put "scrape" IN the edit metadata panel"). */
internal const val SCRAPE_ROW = "Scrape"

/** The Scrape row's line while a scrape it started runs. */
internal const val SCRAPING = "Looking it up…"

/**
 * Edit metadata's first row, the same on every page: what the last scrape said ([status], [SCRAPING] while one
 * runs, when A does nothing), else what Scrape does, saying so first for a game nothing matched yet. [sources] names
 * where the page's own scrape looks ("the PC sources", "your console scrapers").
 */
internal fun scrapeRow(entry: LibraryEntry, status: String?, sources: String, onScrape: () -> Unit): ActionRow =
    ActionRow(
        SCRAPE_ROW,
        status ?: PageKind.of(entry).noun.let { noun ->
            if (entry.isUnscraped()) "Not scraped yet. Looks this $noun up in $sources" else "Looks this $noun up in $sources again"
        },
        onSelect = if (status == SCRAPING) null else onScrape,
    )

/**
 * The Options rows every page offers in the same words, each worded for what the page is about ([PageKind]), so no
 * page keeps its own copy of them. Each takes the press; the page closes its menu and opens what the row names.
 */
internal object PageOptionRows {
    private const val SAVES = "Saves"
    private const val SIGN_IN = "Sign in for achievements"
    private const val CHOOSE_MATCH = "Choose match"
    private const val VIEW_MEDIA = "View media"
    private const val COLLECTIONS = "Collections"

    fun saves(value: String?, onSelect: () -> Unit) = ActionRow(SAVES, value, onSelect = onSelect)

    fun signIn(kind: PageKind, onSelect: () -> Unit) =
        ActionRow(SIGN_IN, "Shows your RetroAchievements progress on this ${kind.noun}", onSelect = onSelect)

    fun chooseMatch(kind: PageKind, onSelect: () -> Unit) =
        ActionRow(CHOOSE_MATCH, "Pick the right ${kind.noun} by hand when the scraper guessed wrong", onSelect = onSelect)

    fun viewMedia(kind: PageKind, count: Int, onSelect: () -> Unit) =
        ActionRow(VIEW_MEDIA, "$count images and videos scraped for this ${kind.noun}", onSelect = onSelect)

    fun collections(kind: PageKind, onSelect: () -> Unit) =
        ActionRow(COLLECTIONS, "Which of your collections this ${kind.noun} is in", onSelect = onSelect)
}

/** The A hint on the action band: what the button under the cursor does. */
internal fun pageActionLabel(button: PageButton, primaryVerb: String): String = when (button) {
    PageButton.PRIMARY -> primaryVerb
    PageButton.EDIT_METADATA -> EDIT_METADATA
    PageButton.OPTIONS -> OPTIONS
    PageButton.FAVOURITE -> "Favourite"
}

/**
 * The hero band: the game's hero art edge to edge, darkened toward the
 * page's ground so what is laid over it stays legible whatever the art is,
 * with the logo (or the name) bottom-left.
 * With only portrait art that art sits at its own shape on the right of a
 * plate, never stretched across the band, and the name keeps to the width
 * left of it; with none, the plate carries the
 * name, never a stand-in cover. The logo is large, bottom-left, as on
 * Steam's page; under it only the status row ([PageStatusRow]).
 */
@Composable
private fun PageHero(entry: LibraryEntry, height: Dp, statuses: List<PageStatus>) {
    val window = LocalShellWindow.current
    val title = GameNaming.displayName(entry.title)
    val hero = entry.heroUri
    // Only portrait art: it takes its own cover shape at the right and the name keeps to the width left of
    // it, wrapping, rather than running under the art with only the gradient between (Droidtop/tracker#666).
    val coverArt = entry.artworkUri.takeIf { hero == null }
    Box(modifier = Modifier.fillMaxWidth().height(height).background(MenuTokens.Card)) {
        if (hero != null) {
            AsyncImage(
                model = hero,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, MenuTokens.Ground))),
        )
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = window.edgePadding),
            horizontalArrangement = Arrangement.spacedBy(Space.Lg),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .align(Alignment.Bottom)
                    .padding(vertical = Space.Md),
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
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (statuses.isNotEmpty()) PageStatusRow(statuses, Modifier.padding(top = Space.Sm))
            }
            if (coverArt != null) {
                AsyncImage(
                    model = coverArt,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterEnd,
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(vertical = Space.Sm)
                        .aspectRatio(COVER_ASPECT, matchHeightConstraintsFirst = true),
                )
            }
        }
    }
}

/** A cover's shape, width over height: the 2:3 of a box cover and of Steam's library capsule. */
private const val COVER_ASPECT = 2f / 3f

/**
 * The action band, one row as on Steam's page: the ONE large primary action
 * at the left, the quiet facts strip beside it, Edit metadata (when the page
 * has it), Options and a small Favourite button at the right edge
 * (Droidtop/tracker#845), and under them the one line saying why the primary
 * action is what it is. [selectedButton] is the button the cursor is on, null
 * when the cursor is elsewhere. In portrait the strip sits under the buttons.
 */
@Composable
private fun PageActionBand(
    play: PcPlayState,
    favourite: Boolean,
    buttons: List<PageButton>,
    selectedButton: PageButton?,
    strip: List<Pair<String, String>>,
    portrait: Boolean,
    // Where Play is, for the launch to grow out of (PageFlood.kt).
    onPlayPlaced: (LayoutCoordinates) -> Unit,
    // Where Edit metadata and Options are, for their menus to open beside ([anchorFor]).
    editModifier: Modifier,
    optionsModifier: Modifier,
    onPress: (PageButton) -> Unit,
) {
    val primary: @Composable () -> Unit = {
        ShellChip(
            play.verb,
            modifier = Modifier.onGloballyPositioned(onPlayPlaced),
            primary = true,
            large = true,
            enabled = play.pressable,
            selected = selectedButton == PageButton.PRIMARY,
            onClick = { onPress(PageButton.PRIMARY) },
        )
    }
    val rest: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.Sm), verticalAlignment = Alignment.CenterVertically) {
            if (PageButton.EDIT_METADATA in buttons) {
                ShellChip(
                    EDIT_METADATA,
                    modifier = editModifier,
                    selected = selectedButton == PageButton.EDIT_METADATA,
                    onClick = { onPress(PageButton.EDIT_METADATA) },
                )
            }
            ShellChip(
                OPTIONS,
                modifier = optionsModifier,
                selected = selectedButton == PageButton.OPTIONS,
                onClick = { onPress(PageButton.OPTIONS) },
            )
            PageIconButton(
                glyph = if (favourite) "★" else "☆",
                description = if (favourite) "Remove from favourites" else "Add to favourites",
                on = favourite,
                selected = selectedButton == PageButton.FAVOURITE,
                onClick = { onPress(PageButton.FAVOURITE) },
            )
        }
    }
    val detail: @Composable () -> Unit = {
        if (play.detail.isNotBlank()) {
            Text(
                play.detail,
                color = if (play.pressable) MenuTokens.Value else MenuTokens.OnSurfaceDisabled,
                style = TypeRole.supporting,
                modifier = Modifier.padding(top = Space.Sm),
            )
        }
    }
    Column(modifier = Modifier.padding(top = Space.Sm)) {
        if (portrait) {
            // Four buttons outgrow a phone's width: the row scrolls sideways rather than clipping one.
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                primary()
                rest()
            }
            // Why the button says what it says sits right under it, before the facts (Droidtop/tracker#657); a
            // download's line is its time left and stays under its bar.
            if (play.look == null) detail()
            if (strip.isNotEmpty()) PageFactsStrip(strip, Modifier.fillMaxWidth().padding(top = Space.Sm))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                primary()
                if (strip.isNotEmpty()) {
                    PageFactsStrip(strip, Modifier.weight(1f).padding(horizontal = Space.Xl))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                rest()
            }
        }
        // The download's bar: how far, seen rather than read (Droidtop/tracker#643); the line under it says the time left.
        play.look?.let { look ->
            dev.droidtop.shell.gamepad.ShellProgressBar(
                play.progress ?: -1f,
                Modifier.fillMaxWidth().padding(top = Space.Sm),
                look,
            )
        }
        if (!portrait || play.look != null) detail()
    }
}

/**
 * A small square button for the cursor to rest on (Steam's favourite beside the status panel): a glyph, never a
 * word, with a spoken description. At rest it is a quiet plate; under the cursor it turns solid, as every quiet
 * button does.
 */
@Composable
private fun PageIconButton(glyph: String, description: String, on: Boolean, selected: Boolean, onClick: () -> Unit) {
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
            .padRow(onClick = onClick)
            .selectionFrame(selected, Corners.Crisp, selectedFill = MenuTokens.Selected),
    ) {
        Text(glyph, color = ink, style = MaterialTheme.typography.titleLarge)
    }
}

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
                Text(label.labelCase(), color = MenuTokens.OnSurfaceMuted, style = TypeRole.eyebrow, maxLines = 1)
                Text(value, color = MenuTokens.OnSurface, style = TypeRole.value, maxLines = 1)
            }
        }
    }
}

/**
 * The tab strip, L1 and R1 at its ends: while the page is open it owns
 * the shoulders. Every tab is drawn, an empty one too (the owner, Droidtop/tracker#845: "Empty tabs
 * shouldn't be hidden"). Where its names do not fit (a large text size, portrait) it scrolls sideways,
 * and the end it can scroll to fades, so a tab cut at the edge says there is more (Droidtop/tracker#661).
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
            modifier = Modifier.weight(1f).fadeWhileMoreSideways(state),
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

/** What a glyph of the status row stands for ([pageStatuses]). */
internal enum class PageStatusKind { READY, UPDATE, CLOUD_SAVES, SAVES, ACHIEVEMENTS, NOT_SCRAPED }

/** How a glyph of the status row is inked: done or there (the accent), a plain fact, or waiting on the person. */
internal enum class PageStatusTone { GOOD, PLAIN, WAITING }

/** One glyph of the status row: what it is, the words a screen reader says for it, and its ink (or its state glyph). */
internal data class PageStatus(
    val kind: PageStatusKind,
    val label: String,
    val tone: PageStatusTone,
    /** Drawn as the shared state glyph (synced, offline, failed, syncing) instead of the kind's own. */
    val mark: dev.droidtop.library.settings.StatusMark? = null,
)

/**
 * The status row under the title (the owner, Droidtop/tracker#845: "a set of icons to indicate the status of each
 * thing ... so we can confirm saves are synced, achievements are available, etc, visually"), from what the page
 * already knows, in this order: whether it is ready to play ([runsWith] what runs it: a runner, an emulator), an
 * update ([update]: the game's own, or the version an app catalog offers for an app), the store's cloud saves
 * ([cloudSaves], its words and glyph), droidtop's view of its saves ([saves], their value), its achievements
 * ([achievements], their value; [achievementsSignIn] when signing in would show them), and that it is not scraped yet
 * (a game's: an app's facts come from Android, not a scrape). The same for every kind of game, its words for the
 * page's [PageKind] ("Ready to play", "Ready to open"). Pure.
 */
internal fun pageStatuses(
    entry: LibraryEntry,
    play: PcPlayState,
    runsWith: String?,
    cloudSaves: Pair<String, dev.droidtop.library.settings.StatusMark?>?,
    saves: String?,
    achievements: String?,
    achievementsSignIn: Boolean,
    update: String? = entry.availableUpdate,
): List<PageStatus> {
    val kind = PageKind.of(entry)
    return listOfNotNull(
        if (play.ready) {
            PageStatus(
                PageStatusKind.READY,
                runsWith?.takeIf { it.isNotBlank() }?.let { "Ready to ${kind.verb} with $it" } ?: "Ready to ${kind.verb}",
                PageStatusTone.GOOD,
            )
        } else {
            PageStatus(PageStatusKind.READY, "Not ready to ${kind.verb}: " + play.detail.ifBlank { play.verb }, PageStatusTone.WAITING)
        },
        update?.let { PageStatus(PageStatusKind.UPDATE, "Update: " + GameUpdates.line(it), PageStatusTone.GOOD) },
        cloudSaves?.let { (line, mark) ->
            PageStatus(
                PageStatusKind.CLOUD_SAVES,
                "Cloud saves: $line",
                if (mark == dev.droidtop.library.settings.StatusMark.SYNCED) PageStatusTone.GOOD else PageStatusTone.WAITING,
                mark,
            )
        },
        saves?.let { PageStatus(PageStatusKind.SAVES, "Saves: $it", PageStatusTone.PLAIN) },
        when {
            achievements != null -> PageStatus(PageStatusKind.ACHIEVEMENTS, "Achievements: $achievements", PageStatusTone.GOOD)
            achievementsSignIn -> PageStatus(PageStatusKind.ACHIEVEMENTS, "Achievements: sign in to see them", PageStatusTone.WAITING)
            else -> null
        },
        if (kind == PageKind.GAME && entry.isUnscraped()) PageStatus(PageStatusKind.NOT_SCRAPED, "Not scraped yet", PageStatusTone.WAITING) else null,
    )
}

/** The status row's glyphs, each saying its words to a screen reader ([PageStatus.label]); nothing here is pressed. */
@Composable
private fun PageStatusRow(statuses: List<PageStatus>, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(Space.Sm), verticalAlignment = Alignment.CenterVertically) {
        statuses.forEach { status ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(PAGE_STATUS_GLYPH).clearAndSetSemantics { contentDescription = status.label },
            ) {
                val mark = status.mark
                if (mark != null) {
                    dev.droidtop.ui.shared.StatusGlyph(mark, MenuTokens.Accent, size = PAGE_STATUS_GLYPH)
                } else {
                    Icon(
                        status.kind.glyph(),
                        contentDescription = null,
                        tint = when (status.tone) {
                            PageStatusTone.GOOD -> MenuTokens.Accent
                            PageStatusTone.PLAIN -> MenuTokens.OnSurface
                            PageStatusTone.WAITING -> MenuTokens.OnSurfaceMuted
                        },
                        modifier = Modifier.size(PAGE_STATUS_GLYPH),
                    )
                }
            }
        }
    }
}

/** Each kind's glyph, from the outlined set the catalog and the state glyphs are drawn in. */
private fun PageStatusKind.glyph(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    PageStatusKind.READY -> Icons.Outlined.SportsEsports
    PageStatusKind.UPDATE -> Icons.Outlined.SystemUpdate
    PageStatusKind.CLOUD_SAVES -> Icons.Outlined.Cloud
    PageStatusKind.SAVES -> Icons.Outlined.Save
    PageStatusKind.ACHIEVEMENTS -> Icons.Outlined.EmojiEvents
    PageStatusKind.NOT_SCRAPED -> Icons.Outlined.Image
}

/** A status glyph's size: the state glyph's own on a row. */
private val PAGE_STATUS_GLYPH = 18.dp
