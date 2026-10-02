package dev.droidtop.shell.gamepad.pc

import dev.droidtop.shell.gamepad.groundBackground
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
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
import dev.droidtop.library.ownership
import dev.droidtop.library.ownershipLabel
import dev.droidtop.library.scraper.FieldSources
import dev.droidtop.shell.gamepad.CatalogDetailStrip
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.LocalValueColumnWidth
import dev.droidtop.shell.gamepad.MenuRow
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.ShellChip
import dev.droidtop.shell.gamepad.Space
import dev.droidtop.shell.gamepad.TypeRole
import dev.droidtop.shell.gamepad.groundBackground
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.HideSystemBarsInThisDialog
import dev.droidtop.shell.gamepad.input.menuStep
import dev.droidtop.shell.gamepad.input.onPad
import dev.droidtop.shell.gamepad.input.ownPadButtons
import dev.droidtop.shell.gamepad.keepInView
import dev.droidtop.shell.gamepad.requestFocusWhenAttached
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * One PC game's own page (docs/SPEC.md 7i, 2026-10-01): what the Steam
 * Deck shows when you open a game. Its art large on one side; on the
 * other, ONE big primary button that says what A does (Play, or the one
 * setup step that makes it Play, or why it cannot -- [PcPlayState]),
 * Favourite and Options beside it, and under them every fact droidtop has
 * about this game as rows: play time, last played, size, the stores it is
 * owned on, the runner, the engine, an update, the scraped facts, the
 * community compatibility reports and the description. Nothing a person
 * would look for is behind a menu (Droidtop/tracker#173).
 *
 * The rows are Settings' rows: the same `MenuRow` at the same uniform
 * height, one content-sized value column, and the selected row's full text
 * in the detail strip under the list (docs/SPEC.md 7k, "Text in rows and
 * tiles"), so a long description is read there instead of growing a row.
 *
 * The pad comes through the one pipeline (docs/SPEC.md 6e,
 * Droidtop/tracker#178): this is a window of its own, so it gets the
 * pipeline's front ([GatePadInThisDialog]); one `onPad` handler on its
 * root moves ONE cursor over the button row and the fact rows, A presses
 * what the cursor is on, X toggles favourite, L2 opens the game's menu, and
 * B closes from the outermost node ([ownPadButtons]) -- so the face-button
 * swap applies to every button here, and no control takes Compose focus of
 * its own. A tap on a button or a row is the same press.
 *
 * No disk work while drawing: everything shown is already on the
 * [LibraryEntry]; the one lookup, the resolved runner, runs for this one
 * game off the main thread ([rememberPcPlayState]).
 */
@Composable
internal fun PcGamePage(
    entry: LibraryEntry,
    // Every folder and store row of this same game (docs/SPEC.md 7m), for
    // "Owned on" and the versions line; the entry itself when there is
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

    // ONE cursor: row 0 is the button row, rows 1.. are the facts.
    var row by remember(entry.id) { mutableIntStateOf(0) }
    var button by remember(entry.id) { mutableIntStateOf(0) }
    var heldStep by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus, "PC game page") }
    LaunchedEffect(row, rows.size) {
        if (row > 0 && rows.isNotEmpty()) listState.keepInView((row - 1).coerceIn(0, rows.lastIndex), animate = !heldStep)
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

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        GatePadInThisDialog()
        HideSystemBarsInThisDialog()
        val art: @Composable () -> Unit = {
            PageArt(entry, modifier = Modifier.fillMaxWidth())
        }
        val header: @Composable () -> Unit = {
            Text(
                GameNaming.displayName(entry.title),
                color = MenuTokens.OnSurface,
                style = TypeRole.screenTitle,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(entry.sourceLabel(), entry.engineLabel()).joinToString(" · "),
                color = MenuTokens.OnSurfaceMuted,
                style = TypeRole.supporting,
                modifier = Modifier.padding(top = Space.Xs),
            )
        }
        val actions: @Composable () -> Unit = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShellChip(
                    play.verb,
                    primary = true,
                    large = true,
                    enabled = play.pressable,
                    selected = row == 0 && button == 0,
                    onClick = {
                        row = 0
                        button = 0
                        pressButton(0)
                    },
                )
                ShellChip(
                    "Favourite",
                    on = entry.favorite,
                    selected = row == 0 && button == 1,
                    onClick = {
                        row = 0
                        button = 1
                        pressButton(1)
                    },
                )
                ShellChip(
                    "Options",
                    selected = row == 0 && button == 2,
                    onClick = {
                        row = 0
                        button = 2
                        pressButton(2)
                    },
                )
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
        val facts: @Composable (Modifier) -> Unit = { modifier ->
            // One value column for the page, content-sized to its widest
            // value (docs/SPEC.md 7k), as the Settings catalog does.
            val measurer = rememberTextMeasurer()
            val valueStyle = MaterialTheme.typography.bodyMedium
            val density = LocalDensity.current
            val valueColumnWidth = remember(rows, valueStyle, density.fontScale) {
                val widest = rows.mapNotNull { it.value }
                    .maxOfOrNull { measurer.measure(it, valueStyle, maxLines = 1, softWrap = false).size.width } ?: 0
                with(density) { widest.toDp() }.coerceIn(MenuTokens.ValueColumnMinWidth, MenuTokens.ValueColumnMaxWidth)
            }
            Column(modifier) {
                CompositionLocalProvider(LocalValueColumnWidth provides valueColumnWidth) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(MenuTokens.RowSpacing),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Space.Lg),
                    ) {
                        itemsIndexed(rows, key = { _, fact -> fact.title }) { index, fact ->
                            MenuRow(
                                title = fact.title,
                                subtitle = fact.subtitle,
                                value = fact.value,
                                chevron = fact.onActivate != null,
                                selected = row == index + 1,
                                uniformHeight = true,
                                ownScrollKeeping = true,
                                onClick = {
                                    row = index + 1
                                    fact.onActivate?.invoke()
                                },
                            )
                        }
                    }
                }
                // The selected row in full, so no row has to grow to be
                // read: the whole description, a long value, where the
                // facts came from.
                val selected = rows.getOrNull(row - 1)
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

        Box(
            modifier = Modifier
                .fillMaxSize()
                // B from the outermost node; the cursor's own presses
                // nearer the focus target, so they are answered first.
                .ownPadButtons(onBack = onClose)
                .onPad { press ->
                    heldStep = press.repeat
                    when (press.action) {
                        GamepadAction.UP -> {
                            // A held Up stops at the top; a fresh one too (a
                            // page has nothing above its first row).
                            val next = menuStep(row, rows.size + 1, -1)
                            if (next != row) EsDeNavigationSounds.play("scroll")
                            row = next
                        }
                        GamepadAction.DOWN -> {
                            val next = menuStep(row, rows.size + 1, +1)
                            if (next != row) EsDeNavigationSounds.play("scroll")
                            row = next
                        }
                        GamepadAction.LEFT, GamepadAction.RIGHT -> {
                            if (row != 0) return@onPad false
                            val next = menuStep(button, buttons, if (press.action == GamepadAction.LEFT) -1 else 1)
                            if (next != button) EsDeNavigationSounds.play("scroll")
                            button = next
                        }
                        GamepadAction.A -> {
                            if (row == 0) pressButton(button) else rows.getOrNull(row - 1)?.onActivate?.invoke()
                        }
                        GamepadAction.X -> onToggleFavorite()
                        GamepadAction.L2 -> onOpenOptions()
                        else -> return@onPad false
                    }
                    true
                }
                .focusRequester(focus)
                .focusable()
                .groundBackground()
                .padding(start = window.edgePadding, end = window.edgePadding, top = Space.Lg, bottom = Space.Lg),
        ) {
            if (window.portrait) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxWidth().height((window.heightDp * 0.3f).dp)) { art() }
                    Column(modifier = Modifier.padding(top = Space.Md)) {
                        header()
                        Column(modifier = Modifier.padding(top = Space.Md)) { actions() }
                    }
                    facts(Modifier.weight(1f).fillMaxWidth().padding(top = Space.Lg))
                }
            } else {
                Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Space.Xl)) {
                    Column(modifier = Modifier.weight(0.38f).fillMaxHeight()) {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) { art() }
                        Column(modifier = Modifier.padding(top = Space.Md)) { header() }
                    }
                    Column(modifier = Modifier.weight(0.62f).fillMaxHeight()) {
                        actions()
                        facts(Modifier.weight(1f).fillMaxWidth().padding(top = Space.Lg))
                    }
                }
            }
        }
    }
}

/**
 * The game's art, as large as its column allows at its own shape: the hero
 * (16:9) when one was scraped, else the capsule (2:3). With neither, the
 * same plate every capsule without art draws, carrying the name -- never
 * a stand-in cover.
 */
@Composable
private fun PageArt(entry: LibraryEntry, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val hero = entry.heroUri
    val art = hero ?: entry.artworkUri
    val ratio = if (hero != null) 16f / 9f else CAPSULE_ASPECT
    Box(modifier = modifier, contentAlignment = Alignment.TopStart) {
        Box(
            modifier = Modifier
                .aspectRatio(ratio)
                .clip(shape)
                .background(MenuTokens.Card, shape),
        ) {
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    GameNaming.displayName(entry.title),
                    color = MenuTokens.OnSurface,
                    style = TypeRole.rowTitle,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(Space.Lg),
                )
            }
        }
    }
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
    val versions = siblings.mapNotNull { sibling -> sibling.groupingPath() }
        .mapNotNull { GameNaming.derive(it).version.takeIf { v -> v.isNotBlank() } }
        .distinct()
    val availableVersions = (versions + listOfNotNull(folderPath?.let { GameNaming.derive(it).version.takeIf(String::isNotBlank) })).distinct()
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
