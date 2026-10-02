package dev.droidtop.shell.gamepad.pc

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
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
import dev.droidtop.library.GameNaming
import dev.droidtop.library.GameUpdates
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.groupingPath
import dev.droidtop.library.isUnscraped
import dev.droidtop.library.scraper.PcScraper
import dev.droidtop.library.LibraryGrouping
import dev.droidtop.library.ownership
import dev.droidtop.library.ownershipLabel
import dev.droidtop.library.scraper.FieldSources
import dev.droidtop.shell.gamepad.CatalogDetailStrip
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.LocalValueColumnWidth
import dev.droidtop.shell.gamepad.MenuRow
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.Motion
import dev.droidtop.shell.gamepad.ShellChip
import dev.droidtop.shell.gamepad.ShoulderGlyph
import dev.droidtop.shell.gamepad.Space
import dev.droidtop.shell.gamepad.TypeRole
import dev.droidtop.shell.gamepad.groundBackground
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.HideSystemBarsInThisDialog
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow
import dev.droidtop.shell.gamepad.input.menuStep
import dev.droidtop.shell.gamepad.input.onPad
import dev.droidtop.shell.gamepad.input.ownPadButtons
import dev.droidtop.shell.gamepad.keepInView
import dev.droidtop.shell.gamepad.requestFocusWhenAttached
import dev.droidtop.shell.gamepad.showsShoulderGlyphs
import dev.droidtop.shell.gamepad.selectionFrame
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
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
 * - **The hero band**: the game's hero art full-bleed with its logo (or its
 *   name) and where it came from. With the cursor down in the tab content
 *   the band compresses to a strip, so the content gets the screen.
 * - **The action band**: ONE large primary action that says what A does
 *   (Play, or the one setup step that makes it Play, or why it cannot --
 *   [PcPlayState]), a small Favourite and Options icon button beside it,
 *   and a quiet facts strip ([factsStrip]: last played, play time, the
 *   version installed against the latest known, size, what runs it), with
 *   the one-line reason under it.
 * - **The tab strip** (Overview, Versions and updates, Extras, Details),
 *   pinned under the band. It OWNS L1/R1 while the page is open: the page
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
 * press. The D-pad never reaches the top bar: this window has none.
 *
 * No disk work while drawing: everything shown is already on the
 * [LibraryEntry]; the lookups, the resolved runner, the folder's size and
 * the parts, run for this one game off the main thread.
 */
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
    onClose: () -> Unit,
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
    val rows = remember(entry, play, runner, siblings, folderSize, scrapeStatus) {
        pageRows(
            context, entry, play, runner, siblings, folderSize,
            scrapeStatus = scrapeStatus,
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
    val tabs = remember { PageTab.values() }
    val rowsByTab = remember(rows, parts) { groupRowsByTab(rows, parts) }
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
    val listState = rememberLazyListState()
    val tabState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus, "PC game page") }
    LaunchedEffect(tab) {
        listState.scrollToItem(0)
        tabState.keepInView(tab)
    }
    LaunchedEffect(zone, row, current.size) {
        if (zone == PageZone.CONTENT && current.isNotEmpty()) {
            listState.keepInView(row.coerceIn(0, current.lastIndex), animate = !heldStep)
        }
    }

    val buttons = 3
    fun pressButton(index: Int) {
        when (index) {
            0 -> if (play.pressable) {
                onClose()
                onPlay()
            }
            1 -> onToggleFavorite()
            2 -> onOpenOptions()
        }
    }

    // L1/R1, Left/Right on the strip, and a tap: one tab change.
    fun selectTab(next: Int) {
        val clamped = next.coerceIn(0, tabs.lastIndex)
        if (clamped != tab) EsDeNavigationSounds.play("scroll")
        tab = clamped
        row = 0
        if (zone == PageZone.CONTENT && rowsByTab[tabs[clamped]].isNullOrEmpty()) zone = PageZone.TABS
    }

    val compact = zone == PageZone.CONTENT
    val fullHero = (window.heightDp * (if (window.portrait) 0.26f else 0.36f)).dp
    val heroHeight by animateDpAsState(
        targetValue = if (compact) COMPACT_HERO_HEIGHT else fullHero,
        animationSpec = Motion.panelIn(),
        label = "page hero",
    )
    val shoulderGlyphs = window.showsShoulderGlyphs()

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        GatePadInThisDialog()
        HideSystemBarsInThisDialog()
        Column(
            modifier = Modifier
                .fillMaxSize()
                // B from the outermost node; the cursor's own presses
                // nearer the focus target, so they are answered first.
                .ownPadButtons(onBack = onClose)
                .onPad { press ->
                    heldStep = press.repeat
                    when (press.action) {
                        GamepadAction.UP -> when (zone) {
                            PageZone.ACTIONS -> Unit
                            // A zone is crossed by a fresh press only: a held
                            // direction stops at the edge instead of running
                            // through the whole page.
                            PageZone.TABS -> if (!press.repeat) {
                                EsDeNavigationSounds.play("scroll")
                                zone = PageZone.ACTIONS
                            }
                            PageZone.CONTENT -> if (row > 0) {
                                EsDeNavigationSounds.play("scroll")
                                row -= 1
                            } else if (!press.repeat) {
                                EsDeNavigationSounds.play("scroll")
                                zone = PageZone.TABS
                            }
                        }
                        GamepadAction.DOWN -> when (zone) {
                            PageZone.ACTIONS -> if (!press.repeat) {
                                EsDeNavigationSounds.play("scroll")
                                zone = PageZone.TABS
                            }
                            PageZone.TABS -> if (!press.repeat && current.isNotEmpty()) {
                                EsDeNavigationSounds.play("scroll")
                                zone = PageZone.CONTENT
                                row = 0
                            }
                            PageZone.CONTENT -> {
                                val next = menuStep(row, current.size, +1)
                                if (next != row) EsDeNavigationSounds.play("scroll")
                                row = next
                            }
                        }
                        GamepadAction.LEFT, GamepadAction.RIGHT -> {
                            val step = if (press.action == GamepadAction.LEFT) -1 else 1
                            when (zone) {
                                PageZone.ACTIONS -> {
                                    val next = menuStep(button, buttons, step)
                                    if (next != button) EsDeNavigationSounds.play("scroll")
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
            PageHero(entry, heroHeight, compact)
            Column(modifier = Modifier.padding(horizontal = window.edgePadding)) {
                PageActionBand(
                    play = play,
                    favourite = entry.favorite,
                    selectedButton = if (zone == PageZone.ACTIONS) button else null,
                    strip = strip,
                    compact = compact,
                    portrait = window.portrait,
                    onPress = { index ->
                        zone = PageZone.ACTIONS
                        button = index
                        pressButton(index)
                    },
                )
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
            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (current.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(tabs[tab].emptyLine, color = MenuTokens.OnSurfaceMuted, style = TypeRole.body)
                    }
                } else {
                    // One value column for the tab, content-sized to its
                    // widest value (docs/SPEC.md 7k), as the Settings catalog does.
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
                            verticalArrangement = Arrangement.spacedBy(MenuTokens.RowSpacing),
                            contentPadding = PaddingValues(
                                start = window.edgePadding,
                                end = window.edgePadding,
                                top = Space.Xs,
                                bottom = Space.Lg,
                            ),
                        ) {
                            itemsIndexed(current, key = { index, fact -> "$index:${fact.title}" }) { index, fact ->
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
                    // The selected row in full, so no row has to grow to be
                    // read: the whole description, a long value, where the
                    // facts came from. Only while the cursor is down here,
                    // when the band above has made the room.
                    if (compact) {
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
                    HintBinding(GamepadAction.L, "Previous tab") { tab > 0 },
                    HintBinding(GamepadAction.R, "Next tab") { tab < tabs.lastIndex },
                    HintBinding(GamepadAction.B, "Back"),
                )
            }
            HintRow(bindings = hints)
        }
    }
}

/** Where the page's one cursor is: the action band's buttons, the tab strip, or the tab's rows. */
private enum class PageZone { ACTIONS, TABS, CONTENT }

/** The hero band shrinks to this when the cursor goes down into the tab's rows. */
private val COMPACT_HERO_HEIGHT = 64.dp

/**
 * The hero band: the game's hero art edge to edge, darkened toward the
 * page's ground so what is laid over it stays legible whatever the art is,
 * with the logo (or the name) and where the game came from bottom-left.
 * With only portrait art that art sits at its own shape on the right of a
 * plate, never stretched across the band; with none, the plate carries the
 * name, never a stand-in cover. [compact] is the band while the cursor is
 * in the tab's rows: one line.
 */
@Composable
private fun PageHero(entry: LibraryEntry, height: Dp, compact: Boolean) {
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
                .padding(horizontal = window.edgePadding, vertical = if (compact) Space.Sm else Space.Md),
        ) {
            val logo = entry.logoUri
            if (logo != null && !compact) {
                AsyncImage(
                    model = logo,
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier.height(56.dp).widthIn(max = 280.dp),
                )
            } else {
                Text(
                    title,
                    color = MenuTokens.OnSurface,
                    style = if (compact) TypeRole.rowTitle else TypeRole.screenTitle,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!compact) {
                Text(
                    listOfNotNull(entry.sourceLabel(), entry.engineLabel()).joinToString(" · "),
                    color = MenuTokens.OnSurfaceMuted,
                    style = TypeRole.supporting,
                    modifier = Modifier.padding(top = Space.Xs),
                )
            }
        }
    }
}

/**
 * The action band: the one large primary action, Favourite and Options as
 * small icon buttons, the quiet facts strip, and under them the one line
 * saying why the primary action is what it is. [selectedButton] is the
 * button the cursor is on (0 primary, 1 Favourite, 2 Options), null when
 * the cursor is elsewhere. [compact] drops the strip and the line while
 * the cursor is in the rows below.
 */
@Composable
private fun PageActionBand(
    play: PcPlayState,
    favourite: Boolean,
    selectedButton: Int?,
    strip: List<Pair<String, String>>,
    compact: Boolean,
    portrait: Boolean,
    onPress: (Int) -> Unit,
) {
    val buttons: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.Md), verticalAlignment = Alignment.CenterVertically) {
            ShellChip(
                play.verb,
                primary = true,
                large = true,
                enabled = play.pressable,
                selected = selectedButton == 0,
                onClick = { onPress(0) },
            )
            PageIconButton(
                glyph = if (favourite) "★" else "☆",
                description = if (favourite) "Remove from favourites" else "Add to favourites",
                on = favourite,
                selected = selectedButton == 1,
                onClick = { onPress(1) },
            )
            PageIconButton(
                glyph = "⋯",
                description = "Game options",
                on = false,
                selected = selectedButton == 2,
                onClick = { onPress(2) },
            )
        }
    }
    Column(modifier = Modifier.padding(top = Space.Sm)) {
        if (portrait) {
            buttons()
            if (!compact && strip.isNotEmpty()) PageFactsStrip(strip, Modifier.fillMaxWidth().padding(top = Space.Sm))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                buttons()
                if (!compact && strip.isNotEmpty()) {
                    PageFactsStrip(strip, Modifier.weight(1f).padding(start = Space.Xl))
                }
            }
        }
        if (!compact && play.detail.isNotBlank()) {
            Text(
                play.detail,
                color = if (play.pressable) MenuTokens.Value else MenuTokens.OnSurfaceDisabled,
                style = TypeRole.supporting,
                modifier = Modifier.padding(top = Space.Sm),
            )
        }
    }
}

/** A small round button for the cursor to rest on: a glyph, never a word, with a spoken description. */
@Composable
private fun PageIconButton(glyph: String, description: String, on: Boolean, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = description }
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
            .selectionFrame(selected, CircleShape),
    ) {
        Text(
            glyph,
            color = if (on) MenuTokens.Favourite else MenuTokens.OnSurface,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

/** The facts strip: a label over a value, quiet, scrolling sideways rather than clipping at a large text size. */
@Composable
private fun PageFactsStrip(facts: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Space.Xl),
    ) {
        facts.forEach { (label, value) ->
            Column {
                Text(label, color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
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
        if (shoulderGlyphs) ShoulderGlyph("L1", badge = true, modifier = Modifier.padding(end = Space.Sm))
        LazyRow(
            state = state,
            contentPadding = PaddingValues(vertical = Space.Sm),
            horizontalArrangement = Arrangement.spacedBy(Space.Sm),
            modifier = Modifier.weight(1f),
        ) {
            items(count = tabs.size, key = { "tab:$it" }) { index ->
                ShellChip(
                    tabs[index].label,
                    on = index == tab,
                    selected = cursorOnTabs && index == tab,
                    onClick = { onSelect(index) },
                )
            }
        }
        if (shoulderGlyphs) ShoulderGlyph("R1", badge = true, modifier = Modifier.padding(start = Space.Sm))
    }
}

/** The A hint on the action band: what the button under the cursor does. */
internal fun pageActionLabel(button: Int, primaryVerb: String): String = when (button) {
    0 -> primaryVerb
    1 -> "Favourite"
    else -> "Options"
}

/** One fact on the page: a row title, what it says, and what A does on it when it does anything. */
internal data class PageFact(
    val title: String,
    val value: String? = null,
    val subtitle: String? = null,
    val onActivate: (() -> Unit)? = null,
)

/**
 * The page's rows, in the order a store page lists them: how you have
 * played it, what you have of it, how it runs, what it is. Only facts that
 * exist; a missing fact is not a row saying "unknown".
 */
private fun pageRows(
    context: android.content.Context,
    entry: LibraryEntry,
    play: PcPlayState,
    runner: dev.droidtop.library.ResolvedRunner?,
    siblings: List<LibraryEntry>,
    folderSizeBytes: Long?,
    scrapeStatus: String?,
    onScrape: () -> Unit,
): List<PageFact> = buildList {
    val now = System.currentTimeMillis()
    add(PageFact("Play time", playtimeLine(entry.playtimeSeconds, entry.playCount)))
    entry.lastPlayedEpochMs?.let { last ->
        add(
            PageFact(
                "Last played",
                android.text.format.DateUtils.getRelativeTimeSpanString(
                    last, now, android.text.format.DateUtils.DAY_IN_MILLIS,
                ).toString(),
            ),
        )
    }
    entry.pcInfo?.let { pc ->
        val sizeBytes = if (entry.groupingPath() != null) folderSizeBytes ?: pc.sizeBytes else pc.sizeBytes
        if (sizeBytes > 0) {
            add(
                PageFact(
                    "Size",
                    android.text.format.Formatter.formatShortFileSize(context, sizeBytes),
                    subtitle = if (pc.installed) "On this device" else "To download",
                ),
            )
        } else if (!pc.installed) {
            add(PageFact("Install state", "Not installed"))
        }
    }
    val owned = siblings.mapNotNull { it.ownership() }
    val ownedLine = owned.ownershipLabel().removePrefix("Owned on ").takeIf { it.isNotBlank() }
    val folderPath = entry.groupingPath()
    add(
        PageFact(
            "Owned on",
            folderPath ?: ownedLine ?: if (entry.pcInfo?.source == null || entry.pcInfo?.source == "Folder") "Your folders" else entry.sourceLabel(),
            subtitle = if (folderPath != null) "Your folders" else null,
        ),
    )
    // The name as it is on disk, beside the title drawn from it (docs/SPEC.md
    // 7n): the raw name is never altered, only parsed.
    // An unidentified folder is drawn by its path: its own name says nothing.
    if (folderPath != null && entry.title == GameNaming.UNIDENTIFIED) {
        add(PageFact("Folder", folderPath))
    } else {
        folderPath?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() && it != GameNaming.displayName(entry.title) }
            ?.let { add(PageFact("Folder name", it)) }
    }
    // A version comes from a folder's own name (docs/SPEC.md 7m); a store
    // row has none to derive.
    val availableVersions = installedVersions(entry, siblings)
    if (availableVersions.isNotEmpty()) {
        add(PageFact("Version", availableVersions.first(), subtitle = if (availableVersions.size > 1) "Also here: ${availableVersions.drop(1).joinToString(", ")}" else null))
    }
    entry.availableUpdate?.let { add(PageFact("Update", it, subtitle = GameUpdates.line(it))) }
    if (entry.isUnscraped()) {
        add(
            PageFact(
                "Not scraped yet",
                value = scrapeStatus ?: "Scrape",
                subtitle = "No cover or description yet. Press A to look this game up in your PC scrape source; " +
                    "Options has Choose match when it finds the wrong game.",
                onActivate = onScrape,
            ),
        )
    }
    entry.pcInfo?.takeIf { entry.isStoreRow() && it.installed }?.let { pc ->
        pc.installedVersion?.takeIf { availableVersions.isEmpty() }?.let { add(PageFact("Version", it)) }
        // A store with no way to tell droidtop says so; it is never shown as up to date.
        when {
            // Already said above, under the same row.
            entry.availableUpdate != null -> Unit
            pc.update == dev.droidtop.library.StoreUpdate.UNKNOWN ->
                add(PageFact("Update", "Not known", subtitle = "${pc.source} does not tell droidtop whether a newer build exists"))
            pc.update == dev.droidtop.library.StoreUpdate.CURRENT ->
                add(PageFact("Update", "Up to date", subtitle = "${pc.source} says this is the newest build"))
            else -> Unit
        }
    }
    add(
        PageFact(
            "Runs with",
            runner?.label ?: if (play.pressable) "" else play.verb,
            subtitle = runner?.reason ?: play.detail,
        ),
    )
    entry.engineLabel()?.let { add(PageFact("Engine", it)) }
    entry.pcInfo?.compatibility?.let { add(PageFact("Compatibility", subtitle = it.summary() + ". Other people's results on other hardware, not a verdict.")) }
    if (!entry.hideMetadata) {
        aboutFacts(entry).forEach { (label, value) -> add(PageFact(label, value)) }
        entry.players?.takeIf { it.isNotBlank() }?.let { add(PageFact("Players", it)) }
        entry.description?.takeIf { it.isNotBlank() }?.let { add(PageFact("About", subtitle = it)) }
        sourcesLine(entry)?.let { add(PageFact("Where these facts came from", subtitle = it)) }
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
 * "2 h 15 min, played 7 times", "Never played". Pure, for the tests.
 */
internal fun playtimeLine(seconds: Long, playCount: Int): String {
    if (seconds <= 0 && playCount <= 0) return "Never played"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val time = when {
        seconds <= 0 -> null
        hours > 0 -> "$hours h $minutes min"
        minutes > 0 -> "$minutes min"
        else -> "Under a minute"
    }
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
    "About", "Not scraped yet", "Runs with", "Compatibility", "Install state" -> PageTab.OVERVIEW
    "Version", "Update", "Owned on", "Folder", "Folder name", "Size" -> PageTab.VERSIONS
    "Players", "Engine", "Where these facts came from" -> PageTab.EXTRAS
    else -> PageTab.DETAILS
}

/** Every row under its tab, in row order; the multi-part list leads Overview. Pure, for the tests. */
internal fun groupRowsByTab(rows: List<PageFact>, parts: List<PageFact>): Map<PageTab, List<PageFact>> =
    PageTab.values().associateWith { tab ->
        (if (tab == PageTab.OVERVIEW) parts else emptyList()) + rows.filter { pageTabOf(it.title) == tab }
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
    "Play time" to playtimeShort(entry.playtimeSeconds),
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
