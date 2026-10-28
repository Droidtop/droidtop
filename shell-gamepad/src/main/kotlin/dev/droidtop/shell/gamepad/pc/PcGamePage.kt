package dev.droidtop.shell.gamepad.pc

import dev.droidtop.shell.gamepad.padRow
import dev.droidtop.library.friendlyLocation
import dev.droidtop.library.runsInEnginehost
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
import dev.droidtop.shell.gamepad.fadeWhileMoreSideways
import dev.droidtop.shell.gamepad.input.PressWords
import dev.droidtop.shell.gamepad.CatalogDetailStrip
import dev.droidtop.shell.gamepad.labelCase
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
import dev.droidtop.shell.gamepad.TextEditDialog
import androidx.compose.runtime.collectAsState
import dev.droidtop.ui.shared.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import dev.droidtop.shell.gamepad.ShellDialog

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
 * - **The tab strip** (Overview, Versions, Extras, Details),
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
 *   had is still a row, under the tab it names ([PageFact.tab]); nothing a
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
    // A copy's own Install, Update or Play from its row on Versions (docs/SPEC.md 7i, "Which copy"); null draws no copy rows' action.
    onPlayCopy: ((LibraryEntry) -> Unit)? = null,
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
    // A linked source's record page (docs/SPEC.md 7g, "Getting an update"): the source, its record, the tab it opens on.
    var viewingRecord by remember(entry.id) { mutableStateOf<Triple<UpdateSources.Source, String, RecordTab>?>(null) }
    var updateStatus by remember(entry.id) { mutableStateOf<String?>(null) }
    val links by produceState<GameLinks?>(null, gameIds, linksToken) {
        value = if (library != null && isFolder) library.gameLinks(gameIds) else null
    }
    val updateSources by produceState(emptyList<UpdateSources.Source>(), library, isFolder) {
        value = if (library != null && isFolder) library.updateSources() else emptyList()
    }
    // Which copy the button acts on, and the person's choice (docs/SPEC.md 7i, "Which copy").
    val cardKey = remember(entry.title) { dev.droidtop.library.CopyChoices.cardKey(entry.title) }
    val choiceToken by dev.droidtop.library.CopyChoices.changes.collectAsState()
    val chosenCopy by produceState<String?>(null, cardKey, choiceToken) {
        value = withContext(Dispatchers.IO) { dev.droidtop.library.CopyChoices.all(context)[cardKey] }
    }
    var copyOptions by remember(entry.id) { mutableStateOf<LibraryEntry?>(null) }
    val copies = remember(siblings, chosenCopy) {
        copyRows(
            siblings,
            chosenCopy,
            acting = entry.id,
            origin = { dev.droidtop.library.originLabel(it, 1).full },
            onRun = { copy -> if (copy.id == entry.id) onPlay() else onPlayCopy?.invoke(copy) },
            onOptions = { copy -> copyOptions = copy },
        )
    }
    // The game's other version folders, each removable once its saves are moved here (docs/SPEC.md 7i; Droidtop/tracker#624).
    val olds = remember(entry, siblings) { dev.droidtop.library.integrations.OldVersions.of(entry, siblings) }
    val oldSizes by produceState(emptyMap<String, dev.droidtop.library.FolderSizes.Size>(), olds) {
        value = withContext(Dispatchers.IO) {
            olds.mapNotNull { old -> dev.droidtop.library.FolderSizes.measure(old.folder.absolutePath)?.let { old.folder.absolutePath to it } }.toMap()
        }
    }
    var armedRemove by remember(entry.id) { mutableStateOf<String?>(null) }
    var removing by remember(entry.id) { mutableStateOf(false) }
    var removeStatus by remember(entry.id) { mutableStateOf<String?>(null) }
    val oldRows = remember(olds, oldSizes, armedRemove, removing, removeStatus) {
        oldVersionRows(olds, oldSizes, armedRemove, removing, removeStatus, PressWords.againSentence) { old ->
            val path = old.folder.absolutePath
            if (removing) return@oldVersionRows
            if (armedRemove != path) {
                armedRemove = path
                return@oldVersionRows
            }
            armedRemove = null
            removing = true
            scope.launch {
                removeStatus = withContext(Dispatchers.IO) {
                    dev.droidtop.library.integrations.OldVersions.remove(
                        context,
                        old,
                        java.io.File(entry.id),
                        dev.droidtop.library.GamesRoots.configured(context),
                    )
                }
                removing = false
            }
        }
    }
    var versionToken by remember(entry.id) { mutableIntStateOf(0) }
    var editingVersion by remember(entry.id) { mutableStateOf(false) }
    var moreOpen by remember(entry.id) { mutableStateOf(false) }
    val setVersion by produceState<String?>(null, entry.id, versionToken) {
        value = withContext(Dispatchers.IO) { dev.droidtop.library.SetVersions.get(context, entry.id) }
    }
    var cloudToken by remember(entry.id) { mutableIntStateOf(0) }
    var cloudStatus by remember(entry.id) { mutableStateOf<String?>(null) }
    val cloudStore = remember(entry) {
        (dev.droidtop.library.PcSource.of(entry) as? dev.droidtop.library.PcSource.Store)?.let { dev.droidtop.library.stores.StoreLibraries.byId(it.storeId) }
    }
    val lastSync by produceState<dev.droidtop.library.stores.StoreSaves.LastSync?>(null, entry.id, cloudToken) {
        value = if (cloudStore?.hasCloudSaves == true) withContext(Dispatchers.IO) { dev.droidtop.library.stores.StoreSaves.lastSync(context, entry.id) } else null
    }
    val online = remember(cloudToken) { isOnline(context) }
    val cloudRow = remember(cloudStore, lastSync, online, cloudStatus, entry.pcInfo?.installed) {
        cloudStore?.takeIf { entry.pcInfo?.installed == true }?.let { store ->
            val line = cloudSavesLine(store.label, store.hasCloudSaves, lastSync, online, System.currentTimeMillis())
            PageFact(
                "Cloud saves",
                cloudStatus ?: line.first,
                subtitle = line.second,
                mark = cloudSavesMark(store.hasCloudSaves, lastSync, online, syncing = cloudStatus == SYNCING),
                onActivate = if (store.hasCloudSaves && entry.isStoreRow()) {
                    {
                        scope.launch {
                            cloudStatus = SYNCING
                            cloudStatus = dev.droidtop.library.stores.StoreSaves
                                .sync(context, entry.id, GameNaming.displayName(entry.title), dev.droidtop.library.stores.SaveSyncPhase.MANUAL)
                                ?.line ?: "No cloud saves for this game"
                            cloudToken++
                        }
                    }
                } else {
                    null
                },
                tab = PageTab.DETAILS,
            )
        }
    }
    // The game's versions on the person's other devices (docs/SPEC.md 7o, "Versions").
    val elsewhere by produceState(emptyList<dev.droidtop.library.computers.ComputerLibrary.VersionElsewhere>(), entry.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                dev.droidtop.library.computers.ComputerLibrary.versionsElsewhere(
                    context,
                    dev.droidtop.library.computers.ComputerLibrary.keyOf(entry),
                    dev.droidtop.net.peer.DeviceIdentity.id(context),
                )
            }.getOrDefault(emptyList())
        }
    }
    val pairedComputers by produceState(emptyList<dev.droidtop.net.peer.Computer>(), entry.id) {
        value = withContext(Dispatchers.IO) { runCatching { dev.droidtop.net.peer.Computers.list(context) }.getOrDefault(emptyList()) }
    }
    val originText by produceState<String?>(null, entry.id) {
        value = withContext(Dispatchers.IO) { dev.droidtop.library.originLabel(entry, 1, roots = pcRootsOf(context)).full }
    }
    val sizeLine = (folderSize ?: entry.pcInfo?.sizeBytes)?.takeIf { it > 0 }?.let { dev.droidtop.library.format.ValueFormat.size(it) }
    // Whether it plays with no network (Droidtop/tracker#422): the art the game draws from the internet is looked up in
    // the image cache once, off the main thread, as the page opens; the rest is the entry's own facts.
    val artNotSaved by produceState(0, entry.id, entry.artworkUri, entry.heroUri, entry.logoUri) {
        val remote = dev.droidtop.library.OfflineReadiness.remoteArt(entry)
        value = if (remote.isEmpty()) {
            0
        } else {
            withContext(Dispatchers.IO) {
                val cache = coil3.SingletonImageLoader.get(context).diskCache
                remote.count { url -> cache?.openSnapshot(url)?.use { true } != true }
            }
        }
    }
    val offlineRow = remember(entry, artNotSaved) {
        val (value, subtitle) = dev.droidtop.library.OfflineReadiness.line(dev.droidtop.library.OfflineReadiness.need(entry), artNotSaved)
        PageFact(OFFLINE_ROW, value, subtitle = subtitle, tab = PageTab.DETAILS)
    }
    val rows = remember(entry, play, runner, siblings, scrapeStatus, updateStatus, links, updateSources, setVersion, moreOpen, cloudRow, sizeLine, originText, elsewhere, pairedComputers, offlineRow) {
        pageRows(
            entry, play, runner, siblings,
            origin = originText,
            size = sizeLine,
            offline = offlineRow,
            cloudSaves = cloudRow,
            setVersion = setVersion,
            onSetVersion = if (isFolder) ({ editingVersion = true }) else null,
            onClearVersion = {
                scope.launch {
                    withContext(Dispatchers.IO) { dev.droidtop.library.SetVersions.set(context, entry.id, null) }
                    versionToken++
                }
            },
            moreOpen = moreOpen,
            onToggleMore = { moreOpen = !moreOpen },
            scrapeStatus = scrapeStatus,
            latest = links?.latestKnown ?: entry.latestKnown,
            onOpenLink = { url ->
                dev.droidtop.library.Links.open(context, url)
            },
            sourceRows = if (library != null && isFolder) {
                sourceRows(
                    sources = updateSources,
                    links = links,
                    // A linked source opens its record; an unlinked one, the sheet that links it.
                    onEdit = { source ->
                        val link = links?.link(source.key)
                        if (link != null) viewingRecord = Triple(source, link.externalId, RecordTab.OVERVIEW) else editingSource = source
                    },
                    onOpen = { url ->
                        dev.droidtop.library.Links.open(context, url)
                    },
                )
            } else {
                emptyList()
            } + elsewhereRows(
                elsewhere,
                here = dev.droidtop.library.SetVersions.shown(entry, setVersion)?.first,
                computers = pairedComputers,
                onGet = if (entry.groupingPath() != null) {
                    { computer, version -> dev.droidtop.library.computers.ComputerGames.get(context, computer, entry, version) }
                } else {
                    null
                },
                onSend = if (entry.groupingPath() != null) {
                    { computer -> dev.droidtop.library.computers.ComputerGames.send(context, computer, entry) }
                } else {
                    null
                },
            ),
            // The update's row opens the record of the source that named it, at Downloads; when that cannot be told, the
            // first linked source's, and with none linked, the sheet that links one. It never does nothing.
            onOpenUpdate = entry.availableUpdate?.let {
                val named = updateSourceOf(updateSources, links, entry.availableUpdate)
                    ?: updateSources.firstNotNullOfOrNull { source -> links?.link(source.key)?.let { source to it.externalId } }
                val open: (() -> Unit)? = when {
                    named != null -> fun() { viewingRecord = Triple(named.first, named.second, RecordTab.DOWNLOADS) }
                    updateSources.isNotEmpty() && isFolder -> fun() { editingSource = updateSources.first() }
                    else -> null
                }
                open
            },
            updateStatus = updateStatus,
            onOpenCopy = { copy -> copyOptions = copy },
            onGetInstaller = { marker ->
                val store = dev.droidtop.library.stores.StoreLibraries.byId(marker.storeId)
                val page = store?.webPages?.accountLibrary
                if (store != null && page != null) dev.droidtop.library.stores.StoreWeb.open(context, store, page)
            },
            onScrape = {
                scope.launch {
                    scrapeStatus = "Looking it up..."
                    scrapeStatus = PcScraper.scrape(context, listOf(entry), filter = dev.droidtop.library.scraper.ScrapeFilter.ALL)
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
    val infoRows = dev.droidtop.shell.gamepad.rememberGameInfoRows(entry, copies = siblings)
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
            tab = PageTab.DETAILS,
        )
    }
    // The kernel drivers this Windows game has installed (docs/SPEC.md 5b, "Kernel drivers"; Droidtop/tracker#607):
    // one line, read on IO, that opens its Drivers list.
    val driversLine by produceState<String?>(null, entry.id, windowsRoute, infoScreen) {
        if (!windowsRoute || infoScreen != null) return@produceState
        value = withContext(Dispatchers.IO) { dev.droidtop.library.GameDrivers.pageLine(context, entry.id) }
    }
    val driversFact = driversLine?.let { line ->
        PageFact(
            "Kernel drivers",
            line,
            onActivate = { infoScreen = dev.droidtop.library.GameDrivers.screen(entry.id, GameNaming.displayName(entry.title)) },
            tab = PageTab.DETAILS,
        )
    }
    val tabs = remember { PageTab.values() }
    val rowsByTab = remember(rows, parts, pluginRows.facts, programFact?.value, programFact?.subtitle, driversFact?.value, infoRows, copies, oldRows) {
        groupRowsByTab(
            rows + copies + oldRows + listOfNotNull(programFact, driversFact) + pluginRows.facts + infoRows.map { it.toPageFact(context) { screen -> infoScreen = screen } },
            parts,
        )
    }
    val strip = remember(entry, runner, folderSize, siblings, setVersion) {
        factsStrip(
            entry = entry,
            now = System.currentTimeMillis(),
            folderSizeBytes = folderSize,
            installedVersion = installedVersions(entry, siblings, setVersion).firstOrNull(),
            runsWith = runner?.label,
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
    // An armed Remove is for the row it was armed on: moving off it disarms it.
    LaunchedEffect(zone, row, tab) { armedRemove = null }
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
    val hints = remember(zone, button, row, tab, verb, play.pressable, play.chooseEngine, current) {
        listOf(
            HintBinding(GamepadAction.A, if (zone == PageZone.ACTIONS) pageActionLabel(button, verb) else "Select") {
                when (zone) {
                    PageZone.ACTIONS -> button != 0 || play.pressable
                    PageZone.TABS -> current.isNotEmpty()
                    PageZone.CONTENT -> current.getOrNull(row)?.onActivate != null
                }
            },
            HintBinding(GamepadAction.X, verb) { play.pressable || play.chooseEngine },
            HintBinding(GamepadAction.Y, "Favourite"),
            HintBinding(GamepadAction.L2, "Game options"),
            HintBinding(GamepadAction.SELECT, "Copy options") { zone == PageZone.CONTENT && current.getOrNull(row)?.onOptions != null },
            HintBinding(GamepadAction.B, "Back"),
        )
    }

    // Into and out of a game's page each have a cue (Steam's, docs/SPEC.md "Interface sounds").
    androidx.compose.runtime.DisposableEffect(Unit) {
        EsDeNavigationSounds.play(UiSound.PAGE_IN)
        onDispose { EsDeNavigationSounds.play(UiSound.PAGE_OUT) }
    }
    ShellDialog(
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
                            // X plays from anywhere on the page, as on the library tile; Y is Favourite
                            // (owner decision, Droidtop/tracker#644 slice 4, proposal 6.C).
                            GamepadAction.X -> pressButton(0)
                            GamepadAction.Y -> onToggleFavorite()
                            GamepadAction.L2 -> onOpenOptions()
                            // Select on a copy's row is that copy's Options; anywhere else the game's.
                            GamepadAction.SELECT -> current.getOrNull(row)?.takeIf { zone == PageZone.CONTENT }?.onOptions?.invoke()
                                ?: onOpenOptions()
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
          }
        }
    }
    PluginPageScreen(pluginRows)
    programScreen?.let { screen -> dev.droidtop.shell.gamepad.CatalogSheet(root = screen, onExit = { programScreen = null }) }
    infoScreen?.let { screen -> dev.droidtop.shell.gamepad.CatalogSheet(root = screen, onExit = { infoScreen = null }) }
    copyOptions?.let { copy ->
        val from = dev.droidtop.library.originLabel(copy, 1).full
        dev.droidtop.shell.gamepad.CatalogSheet(
            root = dev.droidtop.library.settings.CatalogScreen(
                id = "pc_copy_options",
                title = from,
                subtitle = GameNaming.displayName(entry.title),
                groups = {
                    listOf(
                        dev.droidtop.library.settings.CatalogGroup(
                            id = "pc_copy_options_group",
                            title = null,
                            items = listOfNotNull(
                                dev.droidtop.library.settings.ActionItem(
                                    id = "pc_copy_use",
                                    title = "Use this copy for this game",
                                    subtitle = "The button plays it whenever it is installed",
                                    run = { ctx ->
                                        copyOptions = null
                                        scope.launch { withContext(Dispatchers.IO) { dev.droidtop.library.CopyChoices.choose(ctx, cardKey, copy.id) } }
                                    },
                                ),
                                dev.droidtop.library.settings.ActionItem(
                                    id = "pc_copy_auto",
                                    title = "Let droidtop choose",
                                    subtitle = "An installed copy, in the stores' order",
                                    run = { ctx ->
                                        copyOptions = null
                                        scope.launch { withContext(Dispatchers.IO) { dev.droidtop.library.CopyChoices.choose(ctx, cardKey, null) } }
                                    },
                                ).takeIf { chosenCopy == copy.id },
                            ),
                        ),
                    )
                },
            ),
            onExit = { copyOptions = null },
        )
    }
    if (editingVersion) {
        // Prefilled with what the folder's name says, or what was set; blank goes back to the folder's name.
        TextEditDialog(
            title = "Set version",
            subtitle = "Blank uses the folder's name",
            initial = setVersion ?: dev.droidtop.library.SetVersions.shown(entry, null)?.first.orEmpty(),
            onCommit = { text ->
                editingVersion = false
                scope.launch {
                    withContext(Dispatchers.IO) { dev.droidtop.library.SetVersions.set(context, entry.id, text) }
                    versionToken++
                }
            },
            onDismiss = { editingVersion = false },
        )
    }
    val viewing = viewingRecord
    if (viewing != null && library != null) {
        SourceRecordPage(
            library = library,
            source = viewing.first,
            externalId = viewing.second,
            gameName = links?.gameName ?: entry.title,
            newest = entry,
            scope = scope,
            say = { updateStatus = it },
            onRelink = {
                viewingRecord = null
                editingSource = viewing.first
            },
            onOpen = { url ->
                dev.droidtop.library.Links.open(context, url)
            },
            onClose = { viewingRecord = null },
            startTab = viewing.third,
            // This copy's version first ([installedVersions]).
            installedVersion = versions.firstOrNull(),
        )
    }
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
            // What linking found shows on the source's own row once the links are read again.
            say = { linksToken++ },
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
 * plate, never stretched across the band, and the name keeps to the width
 * left of it; with none, the plate carries the
 * name, never a stand-in cover. The logo is large, bottom-left, as on
 * Steam's page; nothing else is stacked on the art.
 */
@Composable
private fun PageHero(entry: LibraryEntry, height: Dp) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                primary()
                Spacer(Modifier.weight(1f))
                icons()
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
                icons()
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
            .padRow(onClick = onClick)
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
                Text(label.labelCase(), color = MenuTokens.OnSurfaceMuted, style = TypeRole.eyebrow, maxLines = 1)
                Text(value, color = MenuTokens.OnSurface, style = TypeRole.value, maxLines = 1)
            }
        }
    }
}

/**
 * The tab strip, L1 and R1 at its ends: while the page is open it owns
 * the shoulders. Its four short names fit a landscape page; at a large
 * text size or in portrait it scrolls sideways, and the end it can scroll
 * to fades, so a tab cut at the edge says there is more (Droidtop/tracker#661).
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
        dev.droidtop.library.stores.StoreHolding.WISHLIST -> holding.label to "On your $store wishlist. A finds it on $store"
    }
}

/**
 * The Cloud saves row of a store copy (docs/SPEC.md 7i): when the saves last
 * matched the store's cloud, that the last sync failed (A retries), that the
 * device is offline and since when nothing synced, or that the store keeps no
 * cloud saves droidtop reaches ("GOG cloud saves are not supported"). Value
 * then line. Pure.
 */
internal fun cloudSavesLine(
    store: String,
    hasCloudSaves: Boolean,
    last: dev.droidtop.library.stores.StoreSaves.LastSync?,
    online: Boolean,
    now: Long,
): Pair<String, String?> {
    if (!hasCloudSaves) return "Not supported" to "$store cloud saves are not supported"
    val since = last?.okAt?.let { dev.droidtop.library.format.ValueFormat.daysAgo(now, it) }
    return when {
        !online -> "Offline" to (since?.let { "Not synced since $it" } ?: "Not synced yet")
        last?.failedAt != null -> "Last sync failed" to "Select to retry"
        since != null -> "Synced $since" to "Select to sync now"
        else -> "Not synced yet" to "Select to sync now"
    }
}

/** The cloud saves row's glyph beside [cloudSavesLine]'s words: syncing, offline, a failed sync, synced. Pure. */
internal fun cloudSavesMark(
    hasCloudSaves: Boolean,
    last: dev.droidtop.library.stores.StoreSaves.LastSync?,
    online: Boolean,
    syncing: Boolean,
): dev.droidtop.library.settings.StatusMark? = when {
    !hasCloudSaves -> null
    syncing -> dev.droidtop.library.settings.StatusMark.WORKING
    !online -> dev.droidtop.library.settings.StatusMark.OFFLINE
    last?.failedAt != null -> dev.droidtop.library.settings.StatusMark.ATTENTION
    last?.okAt != null -> dev.droidtop.library.settings.StatusMark.SYNCED
    else -> null
}

/** The cloud saves row's line while a sync it started runs. */
private const val SYNCING = "Syncing\u2026"

/** Whether the device has a network that reaches the internet now; a system service read, no I/O. */
private fun isOnline(context: android.content.Context): Boolean = runCatching {
    val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
    val caps = manager?.getNetworkCapabilities(manager.activeNetwork)
    caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}.getOrDefault(true)

/** The person's game folders, for the Source row's folder name; a preferences read of a few paths. */
private fun pcRootsOf(context: android.content.Context): List<String> =
    runCatching { dev.droidtop.library.GamesRoots.configured(context).map { it.absolutePath } }.getOrDefault(emptyList())

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

/**
 * One row per copy of a game on Versions (docs/SPEC.md 7i, "Which
 * copy", Droidtop/tracker#397 slice H): where it is from and how it is held,
 * what A does to it (its own Install, Update or Play) and whether it is the one
 * the person chose. Only for a card of more than one copy, one of them a
 * store's row (a folder game's versions are its Version rows). Pure.
 */
internal fun copyRows(
    copies: List<LibraryEntry>,
    chosen: String?,
    acting: String,
    origin: (LibraryEntry) -> String,
    onRun: (LibraryEntry) -> Unit,
    onOptions: (LibraryEntry) -> Unit,
): List<PageFact> {
    if (copies.size < 2 || copies.none { it.ownership() != null }) return emptyList()
    return copies.map { copy ->
        val installed = copy.pcInfo?.installed ?: !copy.missing
        val state = when {
            copy.availableUpdate != null && installed -> "Update"
            installed -> "Play"
            else -> "Install"
        }
        val notes = listOfNotNull(
            "The button plays this one".takeIf { copy.id == acting },
            "Chosen for this game".takeIf { copy.id == chosen && copy.id != acting },
            if (installed) "Installed" else "Not installed",
        )
        PageFact(
            origin(copy),
            state,
            subtitle = notes.joinToString(" \u00b7 "),
            onActivate = { onRun(copy) },
            tab = PageTab.VERSIONS,
            onOptions = { onOptions(copy) },
        )
    }
}

/**
 * One row per other version folder of the game on Versions (docs/SPEC.md 7i; Droidtop/tracker#624): its
 * version and size, and Remove in two presses. The first arms it ([armed], the folder's path) and says what the second
 * does; the second removes it, its saves moved to the version kept first ([dev.droidtop.library.integrations.OldVersions]).
 * [status] is the last removal's line, on a heading row that stays after the folder's row is gone. Pure.
 */
internal fun oldVersionRows(
    olds: List<dev.droidtop.library.integrations.OldVersions.Old>,
    sizes: Map<String, dev.droidtop.library.FolderSizes.Size>,
    armed: String?,
    removing: Boolean,
    status: String?,
    again: String,
    onRemove: (dev.droidtop.library.integrations.OldVersions.Old) -> Unit,
): List<PageFact> {
    fun words(size: dev.droidtop.library.FolderSizes.Size) =
        dev.droidtop.library.FolderSizes.words(size, dev.droidtop.library.format.ValueFormat::size)
    val heading = when {
        removing -> PageFact(OLD_VERSIONS_ROW, "Removing\u2026", tab = PageTab.VERSIONS)
        status != null -> PageFact(OLD_VERSIONS_ROW, subtitle = status, tab = PageTab.VERSIONS)
        else -> null
    }
    return listOfNotNull(heading) + olds.map { old ->
        val path = old.folder.absolutePath
        val label = dev.droidtop.library.integrations.OldVersions.label(old.version)
        val size = sizes[path]?.let(::words)
        PageFact(
            title = "Version $label",
            value = if (armed == path) "Remove?" else "Remove",
            subtitle = if (armed == path) {
                "$again to remove it" + (size?.let { " and free $it" } ?: "") + ". Its saves are copied to this version first"
            } else {
                listOfNotNull(size, "Its saves are copied to this version before it is removed").joinToString(" \u00b7 ")
            },
            onActivate = { onRemove(old) },
            tip = path,
            tab = PageTab.VERSIONS,
        )
    }
}

/** Whether [update], as a source words it, names [copy]'s store ("Steam Build" names Steam): a word match, any case. */
internal fun namesStore(update: String, copy: LibraryEntry): Boolean {
    val label = dev.droidtop.library.PcSource.of(copy)?.label()?.takeIf { it.isNotBlank() } ?: return false
    return Regex("(^|\\W)" + Regex.escape(label) + "(\\W|$)", RegexOption.IGNORE_CASE).containsMatchIn(update)
}

/** The heading row that says what the last removal of an old version did. */
internal const val OLD_VERSIONS_ROW = "Older versions"

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
    // "Get the new installer" for a copy a store installed outside droidtop: its account library (docs/SPEC.md 7g).
    onGetInstaller: (dev.droidtop.library.StoreMarker) -> Unit = {},
    // Another copy of this game, opened where it is installed or played (its options: Install, Play).
    onOpenCopy: (LibraryEntry) -> Unit = {},
    // A folder game's update: the record page of the source that named it (docs/SPEC.md 7g, "Getting an update").
    onOpenUpdate: (() -> Unit)? = null,
    // What getting the update last said ("Downloading...", "Added Game v0.6. Copied 3 save files from Game v0.5").
    updateStatus: String? = null,
    // The page's Details (docs/SPEC.md 7i, "The game page"): where it came from in full, its size on this device,
    // its cloud saves' row, the version the person set, and whether More is open.
    origin: String? = null,
    size: String? = null,
    // Whether it plays with no network ([dev.droidtop.library.OfflineReadiness]).
    offline: PageFact? = null,
    cloudSaves: PageFact? = null,
    setVersion: String? = null,
    onSetVersion: (() -> Unit)? = null,
    onClearVersion: (() -> Unit)? = null,
    moreOpen: Boolean = false,
    onToggleMore: () -> Unit = {},
    onScrape: () -> Unit,
): List<PageFact> = buildList {
    // Each fact is on ONE tab, and none repeats the facts strip under the
    // action band (last played, play time, version, size, runs with): Overview
    // is the game, Versions what is installed against what is out, Extras
    // what comes with it, Details where it lives and what runs it.
    if (!entry.hideMetadata) {
        entry.description?.takeIf { it.isNotBlank() }?.let { add(PageFact("About", subtitle = it, tab = PageTab.OVERVIEW)) }
    }
    if (entry.isUnscraped()) {
        add(
            PageFact(
                "Not scraped yet",
                value = scrapeStatus ?: "Scrape",
                subtitle = "No cover or description yet",
                onActivate = onScrape,
                tab = PageTab.OVERVIEW,
            ),
        )
    }
    if (entry.pcInfo?.installed == false) add(PageFact("Install state", "Not installed", tab = PageTab.OVERVIEW))
    // A GOG installer's row: what the installer file says it holds (docs/SPEC.md 7g, "Installers in a games folder").
    entry.pcInfo?.installer?.let { installer ->
        add(PageFact("Installer", installer.file, subtitle = listOfNotNull(installer.version?.let { "Version $it" }, installer.build?.let { "GOG build $it" }).joinToString(". ").ifEmpty { null }, tab = PageTab.OVERVIEW))
    }
    if (entry.playCount > 0) add(PageFact("Times played", entry.playCount.toString(), tab = PageTab.OVERVIEW))
    entry.pcInfo?.compatibility?.let { add(PageFact("Compatibility", subtitle = it.summary() + ". Other people's results on other hardware, not a verdict.", tab = PageTab.OVERVIEW)) }

    // A version comes from a folder's own name (docs/SPEC.md 7m); a store
    // row has none to derive.
    val availableVersions = installedVersions(entry, siblings, setVersion)
    val shownVersion = dev.droidtop.library.SetVersions.shown(entry, setVersion)
    if (availableVersions.isNotEmpty()) {
        add(
            PageFact(
                "Version",
                availableVersions.first(),
                subtitle = if (availableVersions.size > 1) "Also here: ${availableVersions.drop(1).joinToString(", ")}" else null,
                onActivate = onSetVersion,
                tab = PageTab.VERSIONS,
            ),
        )
    } else if (onSetVersion != null) {
        add(PageFact("Version", "Set", subtitle = "The folder's name says no version", onActivate = onSetVersion, tab = PageTab.VERSIONS))
    }
    if (setVersion != null && onClearVersion != null) {
        add(PageFact(CLEAR_VERSION_ROW, "Use folder name", onActivate = onClearVersion, tab = PageTab.VERSIONS))
    }
    latest?.let { add(PageFact("Latest", it, tab = PageTab.VERSIONS)) }
    // A copy a store installed outside droidtop (its marker, docs/SPEC.md 7g): the store's newer build is had
    // from the account's own library, where the installers are, never by droidtop downloading over the folder.
    val marker = entry.pcInfo?.marker
    val markerStore = marker?.let { dev.droidtop.library.PcSource.Store(it.storeId).label() }
    // A source's newest release that is a store's own build ("Steam Build" on F95zone) of a game whose card holds
    // that store's copy (docs/SPEC.md 7m, "A store's copy and a local copy", Droidtop/tracker#745): the person owns
    // it, so the row points at that copy instead of a download from the source.
    val ownedCopy = entry.availableUpdate?.let { update -> siblings.firstOrNull { it.id != entry.id && it.isStoreRow() && namesStore(update, it) } }
    val ownedStore = ownedCopy?.let { dev.droidtop.library.PcSource.of(it)?.label() }
    entry.availableUpdate?.let { update ->
        add(
            PageFact(
                "Update",
                update,
                subtitle = when {
                    marker != null -> "${GameUpdates.line(update)}. Get the new installer from your $markerStore library"
                    ownedCopy != null -> "That is the $ownedStore build, which you own: ${if (ownedCopy.pcInfo?.installed == true) "play" else "install"} the $ownedStore copy"
                    updateStatus != null -> updateStatus
                    onOpenUpdate != null -> "${GameUpdates.line(update)}. Get it from its source"
                    else -> GameUpdates.line(update)
                },
                onActivate = marker?.let { m -> { onGetInstaller(m) } } ?: ownedCopy?.let { copy -> { onOpenCopy(copy) } } ?: onOpenUpdate,
                tab = PageTab.VERSIONS,
            ),
        )
    }
    addAll(sourceRows)
    // The DLC installed beside the game, from the store's marker files in its folder.
    marker?.dlcIds?.takeIf { it.isNotEmpty() }?.let { dlc ->
        add(PageFact("DLC", "${dlc.size} installed", subtitle = "$markerStore DLC in the game's folder", tab = PageTab.DETAILS))
    }
    entry.pcInfo?.takeIf { (entry.isStoreRow() || it.marker?.buildId != null) && it.installed }?.let { pc ->
        pc.installedVersion?.takeIf { availableVersions.isEmpty() }?.let { add(PageFact("Version", it, tab = PageTab.VERSIONS)) }
        // A store with no way to tell droidtop says so; it is never shown as up to date.
        when {
            // Already said above, under the same row.
            entry.availableUpdate != null -> Unit
            // A marker copy whose store was not asked (signed out, offline) has no Update row at all.
            pc.update == dev.droidtop.library.StoreUpdate.UNKNOWN && pc.marker != null -> Unit
            pc.update == dev.droidtop.library.StoreUpdate.UNKNOWN ->
                add(PageFact("Update", "Not known", subtitle = "${dev.droidtop.library.PcSource.of(entry)?.label() ?: "The store"} has not said whether a newer build exists yet; it is asked in the background, and an install made before its build was recorded is checked after its next update", tab = PageTab.VERSIONS))
            pc.update == dev.droidtop.library.StoreUpdate.CURRENT ->
                add(PageFact("Update", "Up to date", subtitle = "${dev.droidtop.library.PcSource.of(entry)?.label() ?: "The store"} says this is the newest build", tab = PageTab.VERSIONS))
            else -> Unit
        }
    }

    entry.manualUri?.let { add(PageFact("Manual", "Available", tab = PageTab.EXTRAS)) }
    entry.videoUri?.let { add(PageFact("Video", "Available", tab = PageTab.EXTRAS)) }
    entry.links.forEach { link ->
        add(PageFact(link.label, value = "Open", onActivate = { onOpenLink(link.url) }, tip = link.url, tab = PageTab.EXTRAS))
    }
    if (!entry.hideMetadata) {
        sourcesLine(entry)?.let { add(PageFact("Where these facts came from", subtitle = it, tab = PageTab.EXTRAS)) }
    }

    val owned = siblings.mapNotNull { it.ownership() }
    val ownedLine = owned.ownershipLabel().takeIf { owned.map { it.store }.distinct().size > 1 }
    val folderPath = entry.groupingPath()
    // Where it came from, in full ("Steam · Shared with you", "GOG · via Heroic", "Folder: Games").
    add(PageFact("Source", origin ?: dev.droidtop.library.PcSource.of(entry)?.detail() ?: "Folder", subtitle = ownedLine, tab = PageTab.DETAILS))
    // How the account holds it, only when that is not simply owned (docs/SPEC.md 7g, "Ownership is a fact").
    ownershipSentence(entry)?.let { (value, sentence) -> add(PageFact("Ownership", value, subtitle = sentence, tab = PageTab.DETAILS)) }
    size?.let { add(PageFact("Size", it, tab = PageTab.DETAILS)) }
    offline?.let(::add)
    cloudSaves?.let(::add)
    // The technical facts, folded away until asked for (docs/SPEC.md 7i).
    val more = buildList {
        entry.pcInfo?.storeId?.takeIf { entry.isStoreRow() }?.let { add(PageFact("Store id", it, tab = PageTab.DETAILS)) }
        entry.pcInfo?.marker?.let { add(PageFact("Store id", it.key, subtitle = "From the store's files in the game's folder", tab = PageTab.DETAILS)) }
        entry.pcInfo?.installedVersion?.let { add(PageFact("Installed build", it, tab = PageTab.DETAILS)) }
        // Where it lives as a person names it; the whole path is the tooltip.
        folderPath?.let { add(PageFact("Install location", friendlyLocation(it), tip = it, tab = PageTab.DETAILS)) }
        shownVersion?.let { (version, from) -> add(PageFact("Version from", from.words, subtitle = version, tab = PageTab.DETAILS)) }
    }
    if (more.isNotEmpty()) {
        add(PageFact(MORE_ROW, if (moreOpen) "Hide" else "Show", subtitle = if (moreOpen) null else more.joinToString(", ") { it.title }, onActivate = onToggleMore, tab = PageTab.DETAILS))
        if (moreOpen) more.forEach { add(it.copy(tab = PageTab.DETAILS)) }
    }
    // The name as it is on disk, beside the title drawn from it (docs/SPEC.md
    // 7n): the raw name is never altered, only parsed.
    folderPath?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() && it != GameNaming.displayName(entry.title) }
        ?.let { add(PageFact("Folder name", it, tab = PageTab.DETAILS)) }
    // A Wine-run engine says so: what it was built with, and how it runs here (#603).
    entry.engineLabel()?.let { label ->
        add(PageFact("Engine", if (!entry.kind.runsInEnginehost()) "$label, runs in Wine" else label, tab = PageTab.DETAILS))
    }
    // The play state's own line (a store game's "Downloads 590 MB", an install step) is the action band's
    // and is not a statement about the runner, so it is never drawn under this label; a store stage with
    // no runner worked out leaves the row out (Droidtop/tracker#371).
    if (runner != null || play.store == null) {
        add(
            PageFact(
                "Runner",
                runner?.label ?: if (play.pressable && !play.chooseEngine) "" else play.verb,
                subtitle = if (runner != null) runner.reason else play.detail,
                tab = PageTab.DETAILS,
            ),
        )
    }
    if (!entry.hideMetadata) {
        aboutFacts(entry).forEach { (label, value) -> add(PageFact(label, value, tab = PageTab.DETAILS)) }
        entry.players?.takeIf { it.isNotBlank() }?.let { add(PageFact("Players", it, tab = PageTab.DETAILS)) }
    }
}

/** Folder sizes are read only for the open game page, on IO, by the one measurement Storage reads too ([dev.droidtop.library.FolderSizes]). */
private fun folderSizeBytes(path: String): Long = dev.droidtop.library.FolderSizes.measure(path)?.bytes ?: 0L

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
    VERSIONS("Versions", "No version information for this game"),
    EXTRAS("Extras", "No extras for this game yet"),
    DETAILS("Details", "No details for this game yet"),
}

/** The rows a game page folds under More (docs/SPEC.md 7i), and the row that opens them. */
internal const val MORE_ROW = "More"
internal const val CLEAR_VERSION_ROW = "Clear set version"
private val MORE_ROWS = setOf("Store id", "Installed build", "Install location", "Version from")

/**
 * Details in order of use (docs/SPEC.md 7i, "The game page"): where it came
 * from, how it is held, its size, whether it plays offline, its cloud saves, its DLC, then the rest,
 * then More and what More opens.
 */
private val DETAILS_ORDER = listOf("Source", "Ownership", "Size", OFFLINE_ROW, "Cloud saves", "DLC")

/** The Details row saying whether the game plays with no network (Droidtop/tracker#422). */
internal const val OFFLINE_ROW = "Offline"

private fun detailsRank(title: String): Int = when (title) {
    in DETAILS_ORDER -> DETAILS_ORDER.indexOf(title)
    MORE_ROW -> DETAILS_ORDER.size + 1
    in MORE_ROWS -> DETAILS_ORDER.size + 2
    else -> DETAILS_ORDER.size
}

/** Every row under its tab, in row order; the multi-part list leads Overview. Pure, for the tests. */
internal fun groupRowsByTab(rows: List<PageFact>, parts: List<PageFact>): Map<PageTab, List<PageFact>> =
    PageTab.values().associateWith { tab ->
        val own = rows.filter { it.tab == tab }
        val ordered = when (tab) {
            // An available update leads Versions: it is what a person opens the tab for.
            PageTab.VERSIONS -> own.sortedBy { if (it.title == "Update") 0 else 1 }
            PageTab.DETAILS -> own.sortedBy { detailsRank(it.title) }
            else -> own
        }
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
        add(PageFact("Parts", "${game.segments.size} parts", subtitle = "Played in the order the folders name them", tab = PageTab.OVERVIEW))
        game.segments.forEach { segment ->
            add(PageFact(segment.label, segment.versions.firstOrNull()?.version?.takeIf { it.isNotBlank() }, tab = PageTab.OVERVIEW))
        }
    }
}

/**
 * The versions a game's own folders name (docs/SPEC.md 7m), newest-first
 * as the folders listed them, this entry's first; a store row has none to
 * derive. The ONE place the page reads "installed version" from, for the
 * Version row and the facts strip alike.
 */
internal fun installedVersions(
    entry: LibraryEntry,
    siblings: List<LibraryEntry>,
    setVersion: String? = null,
): List<String> {
    val own = dev.droidtop.library.SetVersions.shown(entry, setVersion)?.first
    val others = siblings.mapNotNull { sibling ->
        sibling.groupingPath()?.let { GameNaming.derive(it).version.ifBlank { sibling.filesVersion.orEmpty() } }?.takeIf { v -> v.isNotBlank() }
    }
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
 * it. Pure; sizes read through the one formatter ([dev.droidtop.library.format.ValueFormat]).
 */
internal fun factsStrip(
    entry: LibraryEntry,
    now: Long,
    folderSizeBytes: Long?,
    installedVersion: String?,
    runsWith: String?,
): List<Pair<String, String>> = listOfNotNull(
    "Last played" to (entry.lastPlayedEpochMs?.let { dev.droidtop.library.format.ValueFormat.daysAgo(now, it).replaceFirstChar { c -> c.uppercase() } } ?: "Never"),
    // Play time is time; how many times is Overview's "Times played", said once (Droidtop/tracker#662).
    entry.playtimeSeconds.takeIf { it > 0 }?.let { "Play time" to dev.droidtop.library.format.ValueFormat.duration(it) },
    versionFact(installedVersion, entry.availableUpdate)?.let { "Version" to it },
    (folderSizeBytes ?: entry.pcInfo?.sizeBytes)?.takeIf { it > 0 }?.let { "Size" to dev.droidtop.library.format.ValueFormat.size(it) },
    runsWith?.takeIf { it.isNotBlank() }?.let { "Runs with" to it },
)

/** The one quiet line under the home's hero card: "Played yesterday · 2 h 5 min". */
internal fun heroCaption(entry: LibraryEntry, now: Long): String {
    val last = entry.lastPlayedEpochMs?.let { "Played ${dev.droidtop.library.format.ValueFormat.daysAgo(now, it)}" }
    val time = dev.droidtop.library.format.ValueFormat.duration(entry.playtimeSeconds).takeIf { entry.playtimeSeconds > 0 }
    return listOfNotNull(last, time).joinToString(" · ").ifEmpty { "Not played yet" }
}

/**
 * The game's versions on the person's other devices, on Versions
 * (docs/SPEC.md 7o, "Versions" and "Game updates"): one row per device, its
 * version now or last, and the ones it had before. A paired computer's newer
 * version is copied here as a new version folder when its row is chosen
 * ([onGet]), and "Send this version" copies this one to a computer ([onSend]).
 */
internal fun elsewhereRows(
    seen: List<dev.droidtop.library.computers.ComputerLibrary.VersionElsewhere>,
    here: String? = null,
    computers: List<dev.droidtop.net.peer.Computer> = emptyList(),
    onGet: ((dev.droidtop.net.peer.Computer, String) -> Unit)? = null,
    onSend: ((dev.droidtop.net.peer.Computer) -> Unit)? = null,
): List<PageFact> = seen.groupBy { it.device }.map { (device, versions) ->
    val now = versions.firstOrNull { it.current }
    val before = versions.filter { it != now }.map { it.version }.distinct()
    val computer = now?.let { n -> computers.firstOrNull { it.id == n.deviceId } }
    val newer = now != null && (here == null || dev.droidtop.library.computers.ComputerLibrary.newerThan(now.version, here))
    val get = if (computer != null && newer && onGet != null) ({ onGet(computer, now.version) }) else null
    PageFact(
        title = "On $device",
        value = (now ?: versions.first()).version.let { if (it.firstOrNull()?.isDigit() == true) "v$it" else it },
        subtitle = listOfNotNull(
            if (now == null) "No longer installed there" else null,
            before.takeIf { it.isNotEmpty() }?.let { "Before: " + it.joinToString(", ") },
            if (get != null) "Select to copy it here as a new version; the one you have stays" else null,
        ).joinToString(". ").ifBlank { null },
        onActivate = get,
        tab = PageTab.VERSIONS,
    )
} + if (onSend == null) emptyList() else computers.map { computer ->
    PageFact(
        title = "Send this version to ${computer.name}",
        subtitle = "Copied to its game folder as a new folder; nothing there is replaced",
        onActivate = { onSend(computer) },
        tab = PageTab.VERSIONS,
    )
}

/**
 * The source that named [update], and its record, for the update row: the first linked source (in the sources' order)
 * whose last answer is that version, compared without case, spaces or a leading "v" ("v0.37.dv" is "0.37.DV"); or the
 * only linked source, which named it whatever it wrote. Null when several are linked and none named it (a paired
 * computer's version, a store's build), or none is.
 */
internal fun updateSourceOf(sources: List<UpdateSources.Source>, links: GameLinks?, update: String?): Pair<UpdateSources.Source, String>? {
    update ?: return null
    fun plain(version: String) = version.trim().lowercase().removePrefix("v").filterNot { it.isWhitespace() }
    val wanted = plain(update)
    val linked = sources.mapNotNull { source -> links?.link(source.key)?.let { source to it } }
    linked.firstOrNull { (_, link) -> link.answer?.version?.let(::plain) == wanted }?.let { (source, link) -> return source to link.externalId }
    return linked.singleOrNull()?.let { (source, link) -> source to link.externalId }
}
