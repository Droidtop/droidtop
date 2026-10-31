package dev.droidtop.shell.gamepad

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.GameNaming
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.consoles.EmulatorResolution
import dev.droidtop.library.consoles.GameEmulatorChoice
import dev.droidtop.library.consoles.SwitchContentHandoff
import dev.droidtop.library.friendlyLocation
import dev.droidtop.library.integrations.Integration
import dev.droidtop.library.integrations.IntegrationCapability
import dev.droidtop.library.integrations.IntegrationStore
import dev.droidtop.library.integrations.OpenWithTarget
import dev.droidtop.library.integrations.openWithChipLabel
import dev.droidtop.library.integrations.openWithTargetsFor
import dev.droidtop.library.itemName
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.library.settings.Notices
import dev.droidtop.library.userFacingErrorMessage
import dev.droidtop.shell.gamepad.pc.FullScreenOverlay
import dev.droidtop.shell.gamepad.pc.GamePageFrame
import dev.droidtop.shell.gamepad.pc.PageFact
import dev.droidtop.shell.gamepad.pc.PageTab
import dev.droidtop.shell.gamepad.pc.PcPlayState
import dev.droidtop.shell.gamepad.pc.PluginPageScreen
import dev.droidtop.shell.gamepad.pc.SCRAPING
import dev.droidtop.shell.gamepad.pc.aboutFacts
import dev.droidtop.shell.gamepad.pc.activityRows
import dev.droidtop.shell.gamepad.pc.factsStrip
import dev.droidtop.shell.gamepad.pc.groupRowsByTab
import dev.droidtop.shell.gamepad.pc.pageStatuses
import dev.droidtop.shell.gamepad.pc.rememberPluginPageRows
import dev.droidtop.shell.gamepad.pc.scrapeRow
import dev.droidtop.shell.gamepad.pc.sourcesLine
import dev.droidtop.shell.gamepad.pc.toPageFact
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import dev.droidtop.shell.gamepad.theme.UiSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A console game's page, and an Android app's, in the one frame every kind of game's page is drawn in
 * ([GamePageFrame]; docs/SPEC.md 7i, "The page's frame"; the owner, Droidtop/tracker#845: "One page for every kind
 * of game"). Reached from a card's Info, a search result or a game's menu; B or Back closes it.
 *
 * What this page knows, and where the frame puts it:
 * - **The action band**: Play (Open for an app) runs [onLaunch]; Edit metadata (a console game only) holds Scrape and
 *   then the full editor ([GameMetadataEditor]); Options holds the ones this game has of Saves, Sign in for
 *   achievements, Choose match, View media and Collections, or an app's App info and Uninstall; the star is
 *   Favourite. L2 and Select open Options: a console game has no other menu of its own.
 * - **The status row**: ready to play with which emulator, or that none is installed, the saves, the achievements
 *   and not scraped yet ([pageStatuses], the PC page's own).
 * - **The tabs** ([consolePageRows]): Overview has the description and how long to beat or how an emulator runs it;
 *   Versions a Switch game's update and DLC; Activity the play record and the achievements; Extras the manual, the
 *   video, the person's own "open with" hooks (docs/SPEC.md 12) and the rows plugins add ([rememberPluginPageRows]);
 *   Details the system, the emulator, the file and the scraped facts.
 *
 * Nothing here reads a disk while drawing: the emulators, the media, the hooks, the plugins and the game's facts from
 * outside the library are each asked for once, off the main thread, when the page opens.
 */
@Composable
internal fun EntryDetailScreen(
    entry: LibraryEntry,
    library: Library,
    onLaunch: () -> Unit,
    onClose: () -> Unit,
    /** The entry after an edit of its metadata or its favourite, for the list that holds it (Droidtop/tracker#766). */
    onEntryChanged: (LibraryEntry) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Console ROMs and native apps only: a PC or engine game opens its own page (PcGamePage).
    val isRomEntry = entry.kind == LibraryEntryKind.CONSOLE_ROM
    val isApp = entry.kind == LibraryEntryKind.NATIVE_ANDROID_APP

    var editingMetadata by remember(entry.id) { mutableStateOf(false) }
    var viewingMedia by remember(entry.id) { mutableStateOf(false) }
    var pickingMatch by remember(entry.id) { mutableStateOf(false) }
    var editingCollections by remember(entry.id) { mutableStateOf(false) }
    var scrapeStatus by remember(entry.id) { mutableStateOf<String?>(null) }
    var infoScreen by remember(entry.id) { mutableStateOf<CatalogScreen?>(null) }
    // The star shows the press at once; the list learns it through [onEntryChanged].
    var favourite by remember(entry.id, entry.favorite) { mutableStateOf(entry.favorite) }
    val shown = if (favourite == entry.favorite) entry else entry.copy(favorite = favourite)

    // Everything scraped for this game, for Options' View media: listed on IO once per entry, never while drawing.
    val media by produceState(emptyList<Pair<String, String>>(), entry) {
        value = withContext(Dispatchers.IO) {
            val romFile = java.io.File(entry.id)
            val gamesRoot = dev.droidtop.library.GamesRoots.rootOf(dev.droidtop.library.GamesRoots.current(context), romFile)
            val systemId = entry.systemId
            // The person's own shots of it come first (Droidtop/tracker#596).
            dev.droidtop.library.GameScreenshots.of(context, entry.id) + if (gamesRoot != null && systemId != null) {
                dev.droidtop.library.EsDeArtwork.allMedia(gamesRoot, systemId, romFile.nameWithoutExtension)
            } else {
                emptyList()
            }
        }
    }

    // The user's own "open with" hooks (docs/SPEC.md section 12), paired with the real files on this entry droidtop
    // itself cannot open. Resolved off the main thread: which of the entry's files exist, the integrations folder,
    // and whether the PackageManager has each declared target app.
    var openWithTargets by remember(entry) { mutableStateOf<List<OpenWithTarget>>(emptyList()) }
    var openWith by remember(entry) { mutableStateOf<List<Integration>>(emptyList()) }
    LaunchedEffect(entry) {
        val (targets, integrations) = withContext(Dispatchers.IO) {
            val targets = openWithTargetsFor(entry)
            targets to if (targets.isEmpty()) {
                emptyList()
            } else {
                IntegrationStore.available(context, IntegrationCapability.OPEN_WITH)
            }
        }
        openWithTargets = targets
        openWith = integrations
    }

    // What plugins add to this game's page and the context actions they offer on it (docs/plugin-api.md 3 C4, C18),
    // as rows under the tabs they name: the PC page's own rows.
    val pluginRows = rememberPluginPageRows(entry)
    // Achievements, play time, saves and an emulator's compatibility, asked for once when the page opens (docs/SPEC.md
    // 7h, "Game info"); a console game only, an app has none of them.
    val gameInfo = rememberGameInfoRows(entry, enabled = isRomEntry)
    val savesInfo = gameInfo.firstOrNull { it.kind == GameInfoKind.SAVES }
    val signInInfo = gameInfo.firstOrNull { it.isAchievementsSignIn }
    val achievementsInfo = gameInfo.firstOrNull { it.kind == GameInfoKind.ACHIEVEMENTS && !it.isAchievementsSignIn }
    // Which emulator Play starts, by the one resolution a launch uses (docs/SPEC.md, "Launch resolution"): read on IO.
    val emulators = rememberSystemEmulators(entry)
    val emulator = emulators?.let { EmulatorResolution.matchGameChoice(it.candidates, entry.altEmulator) ?: it.withoutGameChoice?.player }
    val systemName = emulators?.system?.displayName
    val play = remember(isApp, emulators, emulator) {
        consolePlayState(isApp = isApp, emulatorsKnown = emulators != null, emulator = emulator?.name, systemName = systemName)
    }

    val infoFacts = remember(gameInfo) {
        gameInfo.filter { it.kind != GameInfoKind.SAVES && !it.isAchievementsSignIn }.map { info ->
            info.toPageFact(context, tab = if (info.kind == GameInfoKind.ACHIEVEMENTS) PageTab.ACTIVITY else PageTab.OVERVIEW) { screen -> infoScreen = screen }
        }
    }
    // One row per (hook, openable file), on Extras. Never a substitution and never a silent pick: droidtop has no
    // manual reader and no full video player of its own, so these open a door rather than taking over a behaviour,
    // and if the person declared two hooks both appear and the person chooses; droidtop does not rank them.
    val openWithFacts = remember(openWith, openWithTargets) {
        openWith.flatMap { integration ->
            openWithTargets.map { target ->
                PageFact(
                    openWithChipLabel(integration, target, openWithTargets.size),
                    value = "Open",
                    onActivate = {
                        // An integration drives another app's own Activity, so it can fail for reasons droidtop
                        // cannot see coming (the template names a component that app no longer exports): said, not
                        // swallowed.
                        runCatching {
                            IntegrationStore.run(context = context, integration = integration, systemId = entry.systemId, file = target.file)
                        }.exceptionOrNull()?.let { failure ->
                            Log.w("droidtop.gamepad", "Integration failed: ${integration.label}", failure)
                            Notices.flash("${integration.label} failed: ${userFacingErrorMessage(failure)}", tone = NoticeTone.Caution, context = context)
                        }
                    },
                    tip = target.file.path,
                    tab = PageTab.EXTRAS,
                )
            }
        }
    }
    val rowsByTab = remember(entry, systemName, emulators, infoFacts, openWithFacts, pluginRows.facts) {
        groupRowsByTab(
            consolePageRows(
                entry,
                systemName = systemName,
                emulator = emulators?.let { GameEmulatorChoice.summary(it, entry.altEmulator) },
                now = System.currentTimeMillis(),
                // A Switch game with an update or DLC beside it: opens its emulator and names the files to pick.
                onAddSwitchContent = {
                    scope.launch { Notices.flash(SwitchContentHandoff.open(context, entry), context = context) }
                },
            ) + infoFacts + openWithFacts + pluginRows.facts,
            emptyList(),
        )
    }
    val statuses = remember(entry, play, emulator, savesInfo, achievementsInfo, signInInfo) {
        pageStatuses(
            entry = entry,
            play = play,
            runsWith = emulator?.name,
            cloudSaves = null,
            saves = savesInfo?.value,
            achievements = achievementsInfo?.value,
            achievementsSignIn = signInInfo != null,
        )
    }
    val strip = remember(entry, emulator) { factsStrip(entry, folderSizeBytes = null, installedVersion = null, runsWith = emulator?.name) }

    // The console scrape, for this one game (docs/SPEC.md 7h): its line is the Scrape row's while it runs, and what
    // it said is a flash.
    fun scrape() {
        if (scrapeStatus == SCRAPING) return
        scrapeStatus = SCRAPING
        scope.launch {
            val romFile = java.io.File(entry.id)
            val folder = romFile.parentFile
            val system = withContext(Dispatchers.IO) {
                dev.droidtop.library.consoles.ConsoleSystemsRepository.allSystems(context).firstOrNull { it.id == entry.systemId }
            }
            val said = if (folder == null || system == null) {
                "Can't resolve this game's system folder."
            } else {
                dev.droidtop.library.scraper.scrapeSystemArtwork(context, folder, system, onlyRom = romFile)
            }
            scrapeStatus = said
            Notices.flash(said, source = GameNaming.displayName(entry.title), context = context)
        }
    }

    if (editingMetadata) {
        GameMetadataEditor(
            entry = entry,
            library = library,
            onDismiss = { editingMetadata = false },
            onSaved = onEntryChanged,
        )
        return
    }

    GamePageFrame(
        entry = shown,
        play = play,
        statuses = statuses,
        strip = strip,
        rowsByTab = rowsByTab,
        // Scrape first (the owner, Droidtop/tracker#845: "put "scrape" IN the edit metadata panel"), then every field.
        // An app has no metadata of its own to edit, so no button.
        editMetadataRows = if (isRomEntry) {
            { close ->
                listOf(
                    scrapeRow(entry, scrapeStatus, "the console scrapers") { scrape() },
                    ActionRow("Title, description and more", "Every field of this game's metadata, and its emulator") {
                        close()
                        editingMetadata = true
                    },
                )
            }
        } else {
            null
        },
        optionsRows = { close ->
            val savesScreen = savesInfo?.screen
            val signIn = signInInfo?.run
            listOfNotNull(
                savesScreen?.let { screen ->
                    ActionRow("Saves", savesInfo?.value) {
                        close()
                        infoScreen = screen
                    }
                },
                signIn?.let { step ->
                    ActionRow("Sign in for achievements", "Shows your RetroAchievements progress on this game") {
                        close()
                        step(context)
                    }
                },
                if (isRomEntry) {
                    ActionRow("Choose match", "Pick the right game by hand when the scraper guessed wrong") {
                        close()
                        pickingMatch = true
                    }
                } else {
                    null
                },
                if (media.size > 1) {
                    ActionRow("View media", "${media.size} images and videos scraped for this game") {
                        close()
                        viewingMedia = true
                    }
                } else {
                    null
                },
                if (isRomEntry) {
                    ActionRow("Collections", "Which of your collections this game is in") {
                        close()
                        editingCollections = true
                    }
                } else {
                    null
                },
                // An app's own actions: Android's screens do the work, and Uninstall asks its own confirmation.
                if (isApp) {
                    ActionRow("App info", "Android's page for this app") {
                        close()
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", entry.id, null),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                } else {
                    null
                },
                if (isApp) {
                    ActionRow("Uninstall") {
                        close()
                        startUninstall(context, entry.id)
                    }
                } else {
                    null
                },
            )
        },
        onPrimary = onLaunch,
        onToggleFavorite = {
            scope.launch {
                // Null is a kind with no favourite of its own: no sound, nothing changes.
                val now = library.toggleFavorite(shown) ?: return@launch
                EsDeNavigationSounds.play(UiSound.FAVORITE)
                favourite = now
                onEntryChanged(entry.copy(favorite = now))
            }
        },
        onGameMenu = null,
        onBack = onClose,
        focusLabel = "Game page",
    )
    PluginPageScreen(pluginRows)
    if (pickingMatch) {
        ManualMatchPicker(
            entry = entry,
            onApplied = { line -> Notices.flash(line, context = context) },
            onDismiss = { pickingMatch = false },
        )
    }
    if (viewingMedia) {
        FullScreenOverlay(onDismiss = { viewingMedia = false }) {
            MediaViewer(title = entry.title, media = media, onClose = { viewingMedia = false })
        }
    }
    if (editingCollections) {
        FullScreenOverlay(onDismiss = { editingCollections = false }) {
            CollectionMembershipEditor(entry = entry, library = library, onDismiss = { editingCollections = false })
        }
    }
    // The one sheet with the ground painted (CatalogSheet), so the page behind never shows through (rig,
    // 0.2.0-dev.2070).
    infoScreen?.let { screen -> CatalogSheet(root = screen, onExit = { infoScreen = null }) }
}

/**
 * What the console page's primary action says: Open for an app; Play for a console game, ready with the emulator
 * the launch resolves ([emulator]), or not ready when its system's emulators are known ([emulatorsKnown]) and none is
 * installed. Still pressable then: the launch says what is missing and how to get it. Pure.
 */
internal fun consolePlayState(isApp: Boolean, emulatorsKnown: Boolean, emulator: String?, systemName: String?): PcPlayState = when {
    isApp -> PcPlayState(verb = "Open", detail = "", pressable = true, ready = true)
    emulator != null -> PcPlayState(verb = "Play", detail = "Runs with $emulator", pressable = true, ready = true)
    emulatorsKnown -> PcPlayState(
        verb = "Play",
        detail = "No emulator installed for ${systemName ?: "this system"}",
        pressable = true,
        ready = false,
    )
    else -> PcPlayState(verb = "Play", detail = "", pressable = true, ready = true)
}

/**
 * The console page's own rows, each under its tab (docs/SPEC.md 7i, "The page's frame"): Overview the description,
 * Versions a Switch game's update and DLC ([onAddSwitchContent] when there are files to add), Activity droidtop's
 * play record ([activityRows]), Extras the manual, the video, the game's links and where the facts came from, Details
 * the kind, the system, the emulator ([emulator], its summary), the file or the app's package, and the scraped facts.
 * Only facts that exist. Pure.
 */
internal fun consolePageRows(
    entry: LibraryEntry,
    systemName: String?,
    emulator: String?,
    now: Long,
    onAddSwitchContent: () -> Unit,
): List<PageFact> = buildList {
    if (!entry.hideMetadata) {
        entry.description?.takeIf { it.isNotBlank() }?.let { add(PageFact("About", subtitle = it, tab = PageTab.OVERVIEW)) }
    }
    // What this game's Switch files add up to ("Update v131072 · 2 DLC"), or, for a row that is itself an update or
    // DLC whose base game is missing, that fact.
    entry.switchFacts?.line()?.takeIf { it.isNotEmpty() }?.let { line ->
        val adds = SwitchContentHandoff.applies(entry)
        add(
            PageFact(
                SWITCH_CONTENT_ROW,
                line,
                subtitle = if (adds) "Opens its emulator and names the files to pick there" else null,
                onActivate = if (adds) onAddSwitchContent else null,
                tab = PageTab.VERSIONS,
            ),
        )
    }
    addAll(activityRows(entry, now))
    entry.manualUri?.let { add(PageFact("Manual", "Available", tab = PageTab.EXTRAS)) }
    entry.videoUri?.let { add(PageFact("Video", "Available", tab = PageTab.EXTRAS)) }
    if (!entry.hideMetadata) {
        sourcesLine(entry)?.let { add(PageFact("Where these facts came from", subtitle = it, tab = PageTab.EXTRAS)) }
    }
    add(PageFact("Kind", entry.kind.itemName(), tab = PageTab.DETAILS))
    if (entry.kind == LibraryEntryKind.NATIVE_ANDROID_APP) {
        add(PageFact("Package", entry.id, tab = PageTab.DETAILS))
    } else {
        (systemName ?: entry.systemId)?.let { add(PageFact("System", it, tab = PageTab.DETAILS)) }
        emulator?.let { add(PageFact("Emulator", it, tab = PageTab.DETAILS)) }
        // Where it lives as a person names it; the whole path is the tooltip.
        add(PageFact("File", friendlyLocation(entry.id), tip = entry.id, tab = PageTab.DETAILS))
    }
    if (!entry.hideMetadata) {
        aboutFacts(entry).forEach { (label, value) -> add(PageFact(label, value, tab = PageTab.DETAILS)) }
        entry.players?.takeIf { it.isNotBlank() }?.let { add(PageFact("Players", it, tab = PageTab.DETAILS)) }
    }
}

/** The Versions row of a Switch game's update and DLC. */
internal const val SWITCH_CONTENT_ROW = "Update and DLC"
