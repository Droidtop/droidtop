package dev.droidtop.shell.gamepad.pc

import androidx.compose.ui.res.stringResource
import dev.droidtop.shell.gamepad.R
import dev.droidtop.runtime.FailureText
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.library.friendlyLocation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.droidtop.library.EngineHost
import dev.droidtop.library.EngineOverridePrefs
import dev.droidtop.library.EnginesDatabase

import dev.droidtop.library.GameEngine
import dev.droidtop.library.GameLinks
import dev.droidtop.library.UpdateSources
import dev.droidtop.library.ownership
import dev.droidtop.library.ownershipLabel
import dev.droidtop.library.storeLabel
import dev.droidtop.library.GameNaming
import dev.droidtop.library.GameUpdates
import dev.droidtop.library.GameLaunchStrategy
import dev.droidtop.library.LaunchStrategyOverridePrefs
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.LibraryGrouping
import dev.droidtop.library.MissingGames
import dev.droidtop.library.PartProgress
import dev.droidtop.library.groupingPath
import dev.droidtop.library.PcRunnerOptions
import dev.droidtop.library.PcRunners
import dev.droidtop.library.ResolvedRunner
import dev.droidtop.library.RunnerState
import dev.droidtop.library.WineGameSettings
import dev.droidtop.library.WineGameSettingsPrefs
import dev.droidtop.library.displayName
import dev.droidtop.library.SimilarGames
import dev.droidtop.library.scraper.GogChangelog
import dev.droidtop.library.scraper.GogChangelogClient
import dev.droidtop.library.scraper.PcScraper
import dev.droidtop.library.settings.LibraryPaths
import dev.droidtop.library.settings.PathChange
import dev.droidtop.library.userFacingErrorMessage
import dev.droidtop.library.scraper.ProtonDbClient
import dev.droidtop.library.scraper.ProtonDbSummary
import dev.droidtop.library.scraper.ScrapeLookup
import dev.droidtop.library.scraper.SteamNewsClient
import dev.droidtop.library.scraper.SteamNewsItem
import dev.droidtop.library.scraper.kindAndDate
import dev.droidtop.library.scraper.line
import dev.droidtop.shell.gamepad.CollectionMembershipEditor
import dev.droidtop.shell.gamepad.ManualMatchPicker
import dev.droidtop.shell.gamepad.TextEditDialog
import dev.droidtop.shell.gamepad.ActionHeading
import dev.droidtop.shell.gamepad.ActionNote
import dev.droidtop.shell.gamepad.ActionRow
import dev.droidtop.shell.gamepad.ActionSheet
import dev.droidtop.shell.gamepad.ActionSheetItem
import dev.droidtop.shell.gamepad.MediaViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.droidtop.shell.gamepad.ShellDialog

/**
 * The PC-only actions on one PC or engine game -- docs/SPEC.md §7i's
 * "Game options", an ES-DE-style in-context menu over the themed
 * gamelist (redecided 2026-09-26, then again 2026-09-28: PC library view over the theme's frame only), not a screen of its own.
 *
 * What used to live here as a full-screen "detail" -- a hero-art header,
 * the scraped description/developer/rating/genre "About this game" --
 * is gone, but not because a theme shows it any more: the 2026-09-28
 * redecision took PC games OFF the theme own gamelist widget entirely
 * (a frame-only render, PcLibraryView), so that content now lives in
 * PcLibraryView own focused-game panel instead, bound from the exact
 * same [LibraryEntry] fields. This menu is only what NEITHER the theme
 * NOR that panel show: the resolved runner and its picker, Wine/
 * container settings, ProtonDB, the Lutris import, the source links and
 * update state, merge and versions/segments, favourite/collections/
 * scrape.
 *
 * The top page is the primary action, the game page and the favourite, then
 * the categories: Info and art, Organise, Install and files, Updates, How it
 * runs, Controls and saves ([PcMenuPage]). Each opens one page and nothing
 * deeper (docs/SPEC.md 7i, "The game menu"; the owner, 2026-10-23: "No more
 * than one nested menu deep, and fairly short categories"). Scrape was
 * two pages down under "Game info and links" before.
 *
 * A itself no longer opens this menu (docs/SPEC.md 7i): on the gamelist,
 * A launches when the resolved runner is ready and runs the one setup
 * action when it is not ([dev.droidtop.library.PcRunnerOptions.resolveAndPlay]),
 * exactly like a console ROM's A. This menu opens on Select or L2 ("Game options"),
 * the same in-context-menu convention [dev.droidtop.shell.gamepad
 * .GamelistOptionsMenu] already uses for the whole gamelist's own
 * actions (sort/scrape/"PC setup").
 *
 * Store management and prefix configuration are not droidtop's own
 * screens: :runtime-windows compiles the whole vendored gamenative tree,
 * so the install lifecycle and the nine-tab container configuration are
 * already in the APK and these rows open them (through an :app Activity,
 * since this module cannot depend on :app). A row whose action does not
 * apply to this game is still listed, disabled, with the reason -- a
 * folder game has no store to install from, and a game with no Windows
 * build has no prefix.
 */
@Composable
internal fun PcGameMenu(
    entry: LibraryEntry,
    library: Library,
    onLaunch: () -> Unit,
    onClose: () -> Unit,
    // Every game entry the shell has, so this menu can offer the OTHER
    // folders of the same game -- its versions and its segments (docs/
    // SPEC.md 7m). Empty means "nothing to group with", which is what a
    // caller that has no list passes.
    siblings: List<LibraryEntry> = emptyList(),
    onOpenOther: (LibraryEntry) -> Unit = {},
    // A part was marked finished or not: the tab folds again, so Play moves on (docs/SPEC.md 7n).
    onProgressChanged: () -> Unit = {},
    // The game's own page (the list's A is Play now, so the page's route from
    // the pad is this row); null where the caller has no page to open.
    onOpenPage: (() -> Unit)? = null,
    // The Downloads place, where a store download droidtop runs is paused,
    // resumed or cancelled (docs/SPEC.md 7g, "Stores").
    onOpenDownloads: () -> Unit = {},
    // Opens on the Engine picker (the page's "Choose a runner" for a game no runner offers).
    openEnginePicker: Boolean = false,
) {
    // The game that is running leads with its own sheet, this menu one row into it (Droidtop/tracker#740).
    dev.droidtop.shell.gamepad.RunningGameFirst(entry, onClose) {
        PcGameMenuSheet(entry, library, onLaunch, onClose, siblings, onOpenOther, onProgressChanged, onOpenPage, onOpenDownloads, openEnginePicker)
    }
}

@Composable
private fun PcGameMenuSheet(
    entry: LibraryEntry,
    library: Library,
    onLaunch: () -> Unit,
    onClose: () -> Unit,
    siblings: List<LibraryEntry>,
    onOpenOther: (LibraryEntry) -> Unit,
    onProgressChanged: () -> Unit,
    onOpenPage: (() -> Unit)?,
    onOpenDownloads: () -> Unit,
    openEnginePicker: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var favorite by remember(entry) { mutableStateOf(entry.favorite) }

    var runners by remember(entry) { mutableStateOf(PcRunners(null, emptyList())) }
    var resolved by remember(entry) { mutableStateOf<ResolvedRunner?>(null) }
    var loaded by remember(entry) { mutableStateOf(false) }
    var reloadToken by remember(entry) { mutableStateOf(0) }
    var picking by remember(entry) { mutableStateOf(false) }
    // "Run once with…" (docs/SPEC.md 7i, Droidtop/tracker#636): the same choices as Runs with, for one launch.
    var pickingOnce by remember(entry) { mutableStateOf(false) }
    var status by remember(entry) { mutableStateOf<String?>(null) }
    var viewingMedia by remember(entry) { mutableStateOf(false) }
    var pickingMatch by remember(entry) { mutableStateOf(false) }
    // "Rescrape with…" (Droidtop/tracker#67): one game, from a source the person picks.
    var pickingSource by remember(entry) { mutableStateOf(false) }
    var editingCollections by remember(entry) { mutableStateOf(false) }
    var pickingReplacement by remember(entry) { mutableStateOf(false) }
    var pickingSameGame by remember(entry) { mutableStateOf(false) }
    // "Not the same game as <store>": the first press arms it, the second unlinks (Droidtop/tracker#745).
    var unlinkArmed by remember(entry) { mutableStateOf(false) }
    var renaming by remember(entry) { mutableStateOf(false) }
    var progressToken by remember(entry) { mutableStateOf(0) }
    var editingSource by remember(entry) { mutableStateOf<UpdateSources.Source?>(null) }
    // A linked source's row opens its record (at Downloads while an update waits); linking is only for a source not
    // linked yet, or the record's own Change link (Droidtop/tracker#619).
    var viewingRecord by remember(entry) { mutableStateOf<Triple<UpdateSources.Source, String, RecordTab>?>(null) }
    var pickingEngine by remember(entry) { mutableStateOf(openEnginePicker) }
    var engineChoice by remember(entry) { mutableStateOf(EngineChoice.NONE) }
    var importingLutris by remember(entry) { mutableStateOf(false) }
    // This game's Wine and graphics settings (docs/SPEC.md 5a), in a sheet
    // over the menu; null when it is closed.
    var readingGuides by remember(entry) { mutableStateOf(false) }
    var wineScreen by remember(entry) { mutableStateOf<dev.droidtop.library.settings.CatalogScreen?>(null) }
    // Which program the game runs (docs/SPEC.md 7i, "Which program runs"), in a sheet the same way.
    var programScreen by remember(entry) { mutableStateOf<dev.droidtop.library.settings.CatalogScreen?>(null) }
    // What plugins offer on this game (C4, Droidtop/tracker#67), in a sheet the same way.
    var pluginScreen by remember(entry) { mutableStateOf<dev.droidtop.library.settings.CatalogScreen?>(null) }
    val hasPluginActions = dev.droidtop.shell.gamepad.rememberHasPluginActions(entry)
    // The free-space offer before a store install or update opens its own
    // window over the menu (Droidtop/tracker#227); null when it is closed.
    var storeOffer by remember(entry) { mutableStateOf<StoreInstallOffer?>(null) }
    var contentOpen by remember(entry) { mutableStateOf(false) }
    var wineSettings by remember(entry) { mutableStateOf<WineGameSettings?>(null) }
    var protonDb by remember(entry) { mutableStateOf<ProtonDbState>(ProtonDbState.NotAsked) }
    var steamNews by remember(entry) { mutableStateOf<SteamNewsState>(SteamNewsState.NotAsked) }
    var gogNews by remember(entry) { mutableStateOf<GogNewsState>(GogNewsState.NotAsked) }
    // Which page of the menu is showing and which row of it the cursor is
    // on (docs/SPEC.md 7i): the short top page, or one of the two longer
    // lists it opens. A sub-page's B goes back to the top, never out.
    var page by remember(entry) { mutableStateOf(PcMenuPage.Root) }
    val focus = remember(entry) { mutableIntStateOf(0) }
    var focusIndex by focus
    // Uninstall is a two-step press (the design language's confirm): moving
    // to another row disarms it.
    var removeArmed by remember(entry) { mutableStateOf(false) }
    LaunchedEffect(focusIndex, page) { removeArmed = false }
    val downloads by dev.droidtop.library.stores.StoreInstallJob.live.collectAsState()
    val autoUpdateChoices by dev.droidtop.net.DownloadPolicy.games.collectAsState()
    val downloadRules by dev.droidtop.net.DownloadPolicy.settings.collectAsState()

    LaunchedEffect(entry, reloadToken) {
        loaded = false
        val computed = withContext(Dispatchers.IO) {
            val computedRunners = PcRunnerOptions.forEntry(context, entry)
            computedRunners to PcRunnerOptions.resolvedFor(context, entry, computedRunners)
        }
        runners = computed.first
        resolved = computed.second
        loaded = true
    }

    // The game's own Wine settings (docs/SPEC.md 7i), when an import set
    // some: a preferences read, so on IO.
    LaunchedEffect(entry, reloadToken) {
        wineSettings = withContext(Dispatchers.IO) { WineGameSettingsPrefs.get(context, entry.id) }
    }

    // The engine pin (docs/SPEC.md 7e2b): which folder it is keyed by,
    // whether one is set, and the engines it can name -- prefs and the
    // engines database, so read on IO.
    LaunchedEffect(entry, reloadToken) {
        engineChoice = withContext(Dispatchers.IO) {
            val folder = PcRunnerOptions.gameFolderFor(entry)
            if (folder == null) {
                EngineChoice.NONE
            } else {
                EngineChoice(
                    folder = folder.absolutePath,
                    pinned = EngineOverridePrefs.get(context, folder.absolutePath) != null,
                    engines = EnginesDatabase.defs(context)
                        .mapNotNull { def -> def.engine?.let { def.id to it } }
                        .distinctBy { it.second },
                )
            }
        }
    }

    // The game this entry is one folder of: the game's own name for the
    // header, whether or not there is anything to choose between. And who
    // this game could be, or who could be it (docs/SPEC.md 7g), and which
    // other games it might be the same as (7m, "The same game"). All
    // three are names only, no filesystem, but they derive a name for and
    // compare against every sibling -- the whole Games list -- so they are
    // worked out on the Default dispatcher, never in composition
    // (docs/SPEC.md 7g, "no per-item work where a list is drawn"), and for
    // THIS game only: nothing compares every game with every other. Until
    // they are, the header names the game from this folder alone and no
    // row offers a version, a replacement or a merge.
    val worked by produceState(DetailNames.NONE, entry, siblings, progressToken) {
        value = withContext(Dispatchers.Default) {
            val groups = LibraryGrouping.group(siblings, PartProgress.finished(context), dev.droidtop.library.GamesRoots.current(context).map { it.absolutePath })
            val grouping = groups.firstOrNull { it.entriesByPath.containsKey(entry.id) }
            DetailNames(
                forId = entry.id,
                grouping = grouping,
                replacements = replacementCandidatesFor(entry, siblings),
                similar = if (entry.missing || grouping == null) emptyList() else SimilarGames.candidates(grouping, groups),
            )
        }
    }
    // A state kept across a move to another folder of the game answers
    // for the folder it was worked out for, not this one.
    val names = worked.takeIf { it.forId == entry.id } ?: DetailNames.NONE
    val grouping = names.grouping
    // The game's name as the header shows it, which is what a lookup by
    // name (Lutris, ProtonDB) asks for.
    val gameName = dev.droidtop.library.GameNaming.displayName(grouping?.game?.name ?: ownNameOf(entry))
    val group = grouping?.takeIf { it.hasChoices }

    // The game's update sources (docs/SPEC.md 7g): a source link is the
    // GAME's, so it is read and written for every folder of it, and read
    // from the library rather than from [entry], which is the list's copy
    // from before anything on this screen changed it.
    val isFolder = entry.groupingPath() != null
    val gameIds = grouping?.entriesByPath?.keys ?: setOf(entry.id)
    var linksToken by remember(entry) { mutableStateOf(0) }
    val links by produceState<GameLinks?>(null, gameIds, linksToken) {
        value = if (isFolder) library.gameLinks(gameIds) else null
    }
    val updateSources by produceState(emptyList<UpdateSources.Source>(), isFolder) {
        value = if (isFolder) library.updateSources() else emptyList()
    }
    val versions = grouping?.game?.allVersions?.map { it.version }
        ?: entry.groupingPath()?.let { listOf(GameNaming.derive(it).version) }.orEmpty()
    val available = GameUpdates.available(links?.latestKnown ?: entry.latestKnown, versions)

    // Media is a folder listing (EsDeArtwork), which is disk work: IO
    // dispatcher, and "no media" until it answers. Read where the scrape
    // writes it (the engine's own system folder for an engine game, the
    // name the scrape files under), and under the game folder's own name.
    val media by produceState(emptyList<Pair<String, String>>(), entry) {
        value = emptyList()
        value = withContext(Dispatchers.IO) {
            PcScraper.scrapedMedia(context, entry, alsoUnder = PcRunnerOptions.gameFolderFor(entry)?.name)
        }
    }

    // RunnerPicker/EnginePicker/MediaViewer/CollectionMembershipEditor are
    // plain fillMaxSize() screens, written for the days when this menu was
    // itself a full-screen nav-stack detail: FullScreenOverlay hosts them
    // in a full-bleed Dialog instead, now that this menu is an in-context
    // overlay over the gamelist (docs/SPEC.md 7i, redecided 2026-09-26),
    // without touching those shared composables themselves --
    // CollectionMembershipEditor is also a console ROM's own full-screen
    // detail content and stays exactly that there.
    if (picking) {
        FullScreenOverlay(onDismiss = { picking = false }) {
            RunnerPicker(
                options = runners.options,
                engine = runners.engine,
                current = resolved?.option?.strategy,
                overridden = LaunchStrategyOverridePrefs.get(context, entry.id) != null,
                onPick = { strategy ->
                    LaunchStrategyOverridePrefs.set(context, entry.id, strategy)
                    picking = false
                    reloadToken++
                },
                onDismiss = { picking = false },
            )
        }
        return
    }
    if (pickingOnce) {
        FullScreenOverlay(onDismiss = { pickingOnce = false }) {
            RunnerPicker(
                options = runners.options,
                engine = runners.engine,
                current = null,
                overridden = false,
                title = "Run once with",
                onPick = { strategy ->
                    pickingOnce = false
                    val option = runners.options.firstOrNull { it.strategy == strategy }
                    val setup = option?.action
                    when {
                        option == null -> Unit
                        // The saved Runs with choice is left as it is; the launch takes this one and forgets it.
                        option.state == RunnerState.READY -> {
                            LaunchStrategyOverridePrefs.runOnce(entry.id, option.strategy)
                            onClose()
                            onLaunch()
                        }
                        setup != null -> scope.launch {
                            status = PcRunnerOptions.runAction(context, entry, setup) { line -> status = line }
                                ?: "Set up. Choose Run once with again to start it that way"
                            reloadToken++
                        }
                        else -> status = option.reason ?: "That runner can't start this game yet"
                    }
                },
                onDismiss = { pickingOnce = false },
            )
        }
        return
    }
    if (pickingSource) {
        FullScreenOverlay(onDismiss = { pickingSource = false }) {
            ScrapeSourcePicker(
                choices = remember { PcScraper.sourceChoices(context) },
                current = remember { dev.droidtop.library.scraper.PcScraperSourcePrefs.key(context) },
                onPick = { key ->
                    pickingSource = false
                    status = "Scraping ${entry.title}…"
                    scope.launch {
                        status = PcScraper.scrape(context, listOf(entry), filter = dev.droidtop.library.scraper.ScrapeFilter.ALL, using = key)
                    }
                },
                onDismiss = { pickingSource = false },
            )
        }
        return
    }
    val engineFolder = engineChoice.folder
    if (pickingEngine && engineFolder != null) {
        FullScreenOverlay(onDismiss = { pickingEngine = false }) {
            EnginePicker(
                engines = engineChoice.engines,
                current = runners.engine,
                pinned = engineChoice.pinned,
                onPick = { id ->
                    EngineOverridePrefs.set(context, engineFolder, id)
                    pickingEngine = false
                    status = if (id == null) {
                        "Detecting this folder again. The library's label follows on its next scan."
                    } else {
                        "Pinned. The library's label follows on its next scan."
                    }
                    reloadToken++
                },
                onDismiss = { pickingEngine = false },
            )
        }
        return
    }
    wineScreen?.let { screen ->
        dev.droidtop.shell.gamepad.CatalogSheet(
            root = screen,
            onExit = {
                wineScreen = null
                // Picking another Wine build moves the game to its own prefix.
                reloadToken++
            },
        )
        return
    }
    pluginScreen?.let { screen ->
        dev.droidtop.shell.gamepad.CatalogSheet(root = screen, onExit = { pluginScreen = null })
        return
    }
    programScreen?.let { screen ->
        dev.droidtop.shell.gamepad.CatalogSheet(
            root = screen,
            onExit = {
                programScreen = null
                reloadToken++
            },
        )
        return
    }
    if (importingLutris) {
        LutrisImportScreen(
            entry = entry,
            name = gameName,
            onDone = { message ->
                importingLutris = false
                status = message
                reloadToken++
            },
            onDismiss = { importingLutris = false },
        )
        return
    }
    if (readingGuides) {
        FullScreenOverlay(onDismiss = { readingGuides = false }) {
            GuidesScreen(
                gameId = entry.id,
                gameName = gameName,
                folder = dev.droidtop.library.guides.GuideContent.folderOf(entry),
                onClose = { readingGuides = false },
            )
        }
        return
    }
    if (viewingMedia) {
        FullScreenOverlay(onDismiss = { viewingMedia = false }) {
            MediaViewer(title = entry.title, media = media, onClose = { viewingMedia = false })
        }
        return
    }
    if (editingCollections) {
        FullScreenOverlay(onDismiss = { editingCollections = false }) {
            CollectionMembershipEditor(entry = entry, library = library, onDismiss = { editingCollections = false })
        }
        return
    }
    if (pickingMatch) {
        ManualMatchPicker(entry = entry, onApplied = { status = it }, onDismiss = { pickingMatch = false })
    }
    if (pickingReplacement) {
        val candidates = names.replacements
        SameGamePicker(
            focusLabel = if (entry.missing) "Find its replacement" else "This replaces a missing game",
            question = if (entry.missing) "Which game replaced ${entry.title}?" else "Which missing game is ${entry.title}?",
            choices = candidates.map { SameGameChoice(it.entry.title, it.line()) },
            confirmLine = { choice ->
                if (entry.missing) {
                    "Press again: ${entry.title} becomes ${choice.title}, and its history, favourite and collections move there"
                } else {
                    "Press again: ${choice.title} becomes this game, and its history, favourite and collections move here"
                }
            },
            workingLine = "Moving this game's history across...",
            onPick = { index ->
                // Whichever side this screen is on, the missing entry is
                // the one that goes and the present one is the one that
                // stays.
                val candidate = candidates[index].entry
                val missing = if (entry.missing) entry else candidate
                val replacement = if (entry.missing) candidate else entry
                if (library.replaceMissing(missing, replacement)) {
                    "${missing.title} is now ${replacement.title}."
                } else {
                    "${missing.title} could not be folded into ${replacement.title}."
                }
            },
            onDone = { message ->
                status = message
                // The entry that is gone is gone: staying on its screen
                // would be a detail of nothing. The one that remains is
                // the game, and its own detail is where the user is now.
                if (entry.missing) onClose() else pickingReplacement = false
            },
            onDismiss = { pickingReplacement = false },
        )
    }
    val sameGame = grouping
    if (pickingSameGame && sameGame != null) {
        val candidates = names.similar
        val here = GameNaming.displayName(sameGame.game.name)
        SameGamePicker(
            focusLabel = "The same game as",
            question = "Which game is the same game as $here?",
            choices = candidates.map { SameGameChoice(GameNaming.displayName(it.group.game.name), it.line()) },
            confirmLine = { choice ->
                val store = candidates.firstOrNull { GameNaming.displayName(it.group.game.name) == choice.title }?.store
                if (store != null) {
                    "Press again: this folder becomes the local copy of ${choice.title} on ${storeLabel(store.substringBefore(':'))}. Both copies stay, each with its own version; the history and favourite move to one card"
                } else {
                    "Press again: ${choice.title} becomes part of $here. Both folders stay; the history, favourite and collections move to one card"
                }
            },
            workingLine = "Making them one game...",
            onPick = { index ->
                val candidate = candidates[index]
                val other = candidate.group
                val store = candidate.store
                if (store != null) {
                    if (library.linkToStore(sameGame, other)) {
                        "$here is now a copy of ${GameNaming.displayName(other.game.name)} on ${storeLabel(store.substringBefore(':'))}."
                    } else {
                        "$here could not be linked to ${GameNaming.displayName(other.game.name)}."
                    }
                } else if (library.mergeGames(sameGame, other)) {
                    "${GameNaming.displayName(other.game.name)} is now part of $here."
                } else {
                    "${GameNaming.displayName(other.game.name)} could not be made part of $here."
                }
            },
            onDone = { message ->
                status = message
                pickingSameGame = false
            },
            onDismiss = { pickingSameGame = false },
        )
    }
    if (renaming) {
        TextEditDialog(
            title = "Title",
            subtitle = "Blank uses the folder name (${folderNameOf(entry)})",
            initial = entry.gameName.orEmpty(),
            onCommit = { text ->
                renaming = false
                scope.launch {
                    library.renameGame(gameIds, text)
                    status = if (text.isBlank()) "Back to the title read from the folder name." else "Renamed to ${text.trim()}."
                }
            },
            onDismiss = { renaming = false },
        )
    }
    val linking = editingSource
    if (linking != null) {
        SourceLinkSheet(
            library = library,
            gameIds = gameIds,
            gameTitle = gameName,
            versions = versions,
            source = linking,
            current = links?.link(linking.key),
            scope = scope,
            say = { line ->
                status = line
                linksToken++
            },
            onClose = { editingSource = null },
        )
    }
    val viewing = viewingRecord
    if (viewing != null) {
        SourceRecordPage(
            library = library,
            source = viewing.first,
            externalId = viewing.second,
            gameName = gameName,
            newest = entry,
            scope = scope,
            say = { line -> status = line },
            onRelink = {
                viewingRecord = null
                editingSource = viewing.first
            },
            onOpen = { url ->
                dev.droidtop.library.Links.open(context, url)
            },
            onClose = { viewingRecord = null },
            startTab = viewing.third,
            installedVersion = grouping?.game?.defaultVersion?.version ?: versions.firstOrNull(),
        )
    }

    // The replacement row is only worth drawing when there is somebody to offer.
    val replacements = names.replacements
    val runner = resolved
    val hasWindowsRoute = runners.options.any {
        it.strategy == GameLaunchStrategy.WINE_PREFIX && it.state != RunnerState.NOT_FOR_THIS_GAME
    }
    val hasLinuxRoute = runners.options.any {
        it.strategy == GameLaunchStrategy.LINUX_CONTAINER && it.state != RunnerState.NOT_FOR_THIS_GAME
    }
    // Which part of a multi-part game this entry is, and whether it is finished
    // (docs/SPEC.md 7n). Names only: the segments came from the folder names.
    val partOf = grouping?.game?.takeIf { it.segments.size > 1 }?.segments
        ?.firstOrNull { segment -> segment.versions.any { version -> version.copies.any { it.path == entry.id } } }
    val finishedHere = grouping?.finished?.contains(entry.id) == true
    val titleRows = listOfNotNull(
        ActionRow(
            "Title",
            if (entry.gameName.isNullOrBlank()) {
                "From the folder name"
            } else {
                "Your own title"
            },
            onSelect = { renaming = true },
        ),
        partOf?.let { part ->
            ActionRow(
                if (finishedHere) "Not finished with ${part.label}" else "Finished with ${part.label}",
                if (finishedHere) {
                    "Play returns to this part"
                } else {
                    "Play moves to the next part"
                },
                onSelect = {
                    scope.launch {
                        withContext(Dispatchers.IO) { PartProgress.setFinished(context, entry.id, !finishedHere) }
                        progressToken++
                        onProgressChanged()
                    }
                },
            )
        },
        partOf?.let { part ->
            ActionRow(
                "Make ${part.label} its own game",
                "Lists it on its own",
                onSelect = {
                    scope.launch {
                        library.renameGame(listOf(entry.id), "$gameName ${part.label}")
                        status = "${part.label} is a game of its own now."
                    }
                },
            )
        },
    )
    // A store game's install is managed from this menu (docs/SPEC.md 7g,
    // "Stores"): verify where the store keeps a file list, uninstall, and the
    // way to its download. Every store is droidtop's own, Steam included.
    val ownStore = entry.ownStore()
    val hasContent by produceState(false, entry.id) {
        value = ownStore != null && withContext(Dispatchers.IO) {
            runCatching { ownStore.contentOptions(context, (entry.pcInfo?.storeId ?: entry.id).substringAfter(':')) }.getOrNull()
        } != null
    }
    // The store's other ways to play (Steam's "Stream with Steam Link"): files read, so on IO.
    val playOptions by produceState(emptyList<dev.droidtop.library.stores.StorePlayOption>(), entry.id) {
        value = if (ownStore == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                runCatching { ownStore.playOptions(context, (entry.pcInfo?.storeId ?: entry.id).substringAfter(':')) }.getOrNull().orEmpty()
            }
        }
    }
    val ownStoreRows = ownStore?.let { store ->
        val key = entry.pcInfo?.storeId ?: entry.id
        val gameId = key.substringAfter(':')
        val installed = entry.pcInfo?.installed == true
        val play = mutableListOf<ActionRow>()
        val install = mutableListOf<ActionRow>()
        val saves = mutableListOf<ActionRow>()
        run {
            playOptions.forEach { option ->
                play.add(
                    ActionRow(option.title, option.detail, onSelect = {
                        scope.launch {
                            status = "Working…"
                            status = store.playWith(context, gameId, option.id, GameNaming.displayName(entry.title))
                        }
                    }),
                )
            }
            if (hasContent) {
                install.add(ActionRow("DLC and versions", "Which DLC to have and which version to follow", onSelect = { contentOpen = true }))
            }
            downloads[key]?.let { job ->
                install.add(
                    ActionRow(
                        "Downloads",
                        dev.droidtop.library.integrations.PluginJobsScreen.oneLine(job),
                        onSelect = {
                            onClose()
                            onOpenDownloads()
                        },
                        // The job's bar and how it moves, with its line under it (Droidtop/tracker#738).
                        progress = if (job.done) null else job.fraction() ?: -1f,
                        look = dev.droidtop.library.integrations.PluginJobsScreen.lookOf(job),
                    ),
                )
            }
            if (installed && store.hasCloudSaves) {
                saves.add(
                    ActionRow("Sync cloud saves", "Brings this game's saves and ${store.label}'s cloud to the same state", onSelect = {
                        scope.launch {
                            status = "Syncing saves…"
                            status = dev.droidtop.library.stores.StoreSaves
                                .sync(context, key, GameNaming.displayName(entry.title), dev.droidtop.library.stores.SaveSyncPhase.MANUAL)
                                ?.line ?: "No cloud saves for this game"
                        }
                    }),
                )
            }
            if (installed) {
                val choice = autoUpdateChoices[key] ?: dev.droidtop.net.AutoUpdate.FOLLOW
                val next = dev.droidtop.net.AutoUpdate.values()[(choice.ordinal + 1) % dev.droidtop.net.AutoUpdate.values().size]
                install.add(
                    ActionRow(
                        "Keep up to date",
                        when (choice) {
                            dev.droidtop.net.AutoUpdate.FOLLOW -> "Follows the default, which is ${if (downloadRules.autoUpdateDefault) "on" else "off"}"
                            dev.droidtop.net.AutoUpdate.ON -> "On: installs a newer build when ${store.label} has one"
                            dev.droidtop.net.AutoUpdate.OFF -> "Off: never updates by itself"
                        },
                        onSelect = { scope.launch(Dispatchers.IO) { dev.droidtop.net.DownloadPolicy.setGame(context, key, next) } },
                    ),
                )
            }
            if (installed && store.canVerify) {
                install.add(
                    ActionRow("Verify files", "Checks every file against ${store.label}'s list", onSelect = {
                        scope.launch {
                            status = "Checking files…"
                            status = store.verify(context, gameId).fold({ it }, { "Could not check: ${userFacingErrorMessage(it)}" })
                        }
                    }),
                )
            }
            if (installed) {
                val where = entry.pcInfo?.installPath?.let { friendlyLocation(it) }
                install.add(
                    ActionRow(
                        "Uninstall",
                        if (removeArmed) "Press again: deletes ${where ?: "its files"}" else "Deletes ${where ?: "its files"}. ${store.label} still lists it",
                        onSelect = {
                            if (!removeArmed) {
                                removeArmed = true
                            } else {
                                removeArmed = false
                                scope.launch {
                                    status = "Removing…"
                                    val installedAt = entry.pcInfo?.installPath?.takeIf { it.isNotBlank() }
                                    status = store.uninstall(context, gameId).fold(
                                        onSuccess = {
                                            // The files are gone, so exactly that folder leaves the library; nothing is walked.
                                            if (installedAt != null) {
                                                withContext(Dispatchers.IO) { LibraryPaths.report(context, PathChange.removed(java.io.File(installedAt))) }
                                            }
                                            "Removed"
                                        },
                                        onFailure = { "Could not remove it: ${userFacingErrorMessage(it)}" },
                                    )
                                }
                            }
                        },
                    ),
                )
            }
        }
        StoreRows(play, install, saves)
    }
    val actions = rememberPcActions(
        titleRows = titleRows,
        storeRows = ownStoreRows,
        group = group,
        currentId = entry.id,
        onOpenOther = onOpenOther,
        replacements = replacements.size,
        onReplace = { pickingReplacement = true },
        sameGameRow = if (grouping != null && SimilarGames.linkedToStore(grouping)) {
            val stores = grouping.entriesByPath.values.mapNotNull { it.ownership() }.ownershipLabel().removePrefix("Owned on ")
            ActionRow(
                "Not the same game as the $stores copy",
                if (unlinkArmed) "Press again: the local copy and the $stores copy become two games" else "Splits the local copy from the $stores copy",
                onSelect = {
                    if (!unlinkArmed) {
                        unlinkArmed = true
                    } else {
                        unlinkArmed = false
                        scope.launch {
                            status = if (library.unlinkFromStore(grouping)) "The local copy and the $stores copy are two games again." else "Could not split them."
                        }
                    }
                },
            )
        } else {
            names.similar.size.takeIf { it > 0 }?.let { count ->
                ActionRow(
                    "The same game as...",
                    "$count with a similar name",
                    onSelect = { pickingSameGame = true },
                )
            }
        },
        updateRows = if (!isFolder) {
            emptyList()
        } else {
            updateSources.flatMap { source ->
                val link = links?.link(source.key)
                listOfNotNull(
                    ActionRow(
                        source.label,
                        sourceLine(link, available, versions),
                        onSelect = {
                            if (link != null) {
                                viewingRecord = Triple(source, link.externalId, if (available != null) RecordTab.DOWNLOADS else RecordTab.OVERVIEW)
                            } else {
                                editingSource = source
                            }
                        },
                    ),
                    link?.let {
                        ActionRow(
                            if (updateSources.size > 1) "Check ${source.label} now" else "Check for an update now",
                            it.answer?.takeIf { a -> a.checkedAtEpochMs > 0 }
                                ?.let { a -> "Last checked " + dev.droidtop.library.format.ValueFormat.ago(System.currentTimeMillis(), a.checkedAtEpochMs).replaceFirstChar { c -> c.lowercase() } }
                                ?: "Not checked yet",
                            onSelect = {
                                scope.launch {
                                    status = "Checking ${source.label}..."
                                    status = checkSourceAndSay(library, gameIds, it.key, versions, entry.latestKnown)
                                    linksToken++
                                }
                            },
                        )
                    },
                )
            }
        },
        entry = entry,
        runner = runner,
        media = media.size,
        onScrape = {
            status = "Scraping ${entry.title}…"
            scope.launch { status = PcScraper.scrape(context, listOf(entry), filter = dev.droidtop.library.scraper.ScrapeFilter.ALL) }
        },
        onChooseMatch = { pickingMatch = true },
        onRescrapeWith = { pickingSource = true },
        onViewMedia = { viewingMedia = true },
        onCollections = { editingCollections = true },
        favorite = favorite,
        onToggleFavorite = {
            scope.launch { library.toggleFavorite(entry)?.let { favorite = it } }
        },
        onOpenPage = onOpenPage,
        engineRow = runners.engine.let { engine ->
            if (engineChoice.folder == null || !loaded) {
                null
            } else {
                ActionRow(
                    "Engine",
                    when {
                        engine == null -> "Not identified as an engine game; pick one if it is"
                        engineChoice.pinned -> "${engine.displayName()} - your choice"
                        else -> "${engine.displayName()} - identified; pick another if that is wrong"
                    },
                    onSelect = { pickingEngine = true },
                )
            }
        },
        onEnginehost = { intent ->
            status = runCatching {
                context.startActivity(intent)
                null
            }.getOrElse { "Enginehost didn't take that: ${FailureText.of("PcGameMenu", it)}" }
        },
        hasWindowsRoute = hasWindowsRoute,
        // The Manage section's screens (docs/SPEC.md 7i, "Manage"): registered by :app, opened by id for this
        // game's folder, in the same sheet. Linux tools only for a game with a Linux build.
        manageTools = engineChoice.folder?.let { folder ->
            listOfNotNull(
                ManageTool(
                    dev.droidtop.library.ManageScreens.RUN_IN_PREFIX,
                    "Run a program in a Wine prefix",
                    "A program from the game's folder or anywhere, in the prefix you pick. It may change the game's files",
                ),
                ManageTool(
                    dev.droidtop.library.ManageScreens.PREFIX_TOOLS,
                    "Prefix tools",
                    "Wine configuration, the registry editor, a command prompt and Windows components, for the prefix this game starts in",
                ),
                if (hasLinuxRoute) {
                    ManageTool(
                        dev.droidtop.library.LinuxToolsScreen.ID,
                        "Linux tools",
                        "Run a program from its folder, stop its processes, keep or reset its own saves and settings",
                    )
                } else {
                    null
                },
            ).map { tool ->
                ActionRow(tool.title, tool.detail) {
                    val screen = dev.droidtop.library.settings.SettingsScreenRegistry.get(
                        tool.screenId,
                        dev.droidtop.library.LinuxToolsScreen.argument(entry.id, gameName, folder),
                    )
                    if (screen != null) wineScreen = screen else status = "${tool.title} isn't available in this build"
                }
            }
        }.orEmpty(),
        // The registered settings screen :app builds (WineOptionsCatalog),
        // by id and deep-linked to this game, in a sheet over the menu.
        onOpenWineSettings = {
            val screen = dev.droidtop.library.settings.SettingsScreenRegistry.get(
                dev.droidtop.library.WineSettingsScreen.ID,
                dev.droidtop.library.WineSettingsScreen.argument(entry.id, gameName, engineChoice.folder),
            )
            if (screen != null) wineScreen = screen else status = "Wine settings aren't available in this build"
        },
        wineSettings = wineSettings,
        onImportLutris = { importingLutris = true },
        // The game's folder is known once the engine row's facts are read (on IO).
        onChooseProgram = engineChoice.folder?.let { folder ->
            { programScreen = dev.droidtop.library.WindowsPrograms.screen(entry.id, gameName, folder) }
        },
        onOpenProperties = engineChoice.folder?.let { folder ->
            { programScreen = dev.droidtop.library.GameProperties.screen(entry.id, gameName, folder) }
        },
    )

    var pageInfo: List<ActionSheetItem> = emptyList()
    val isReady = runner?.option?.state == RunnerState.READY
    val setupAction = runner?.option?.action
    val playState = if (loaded) {
        playStateOf(runner, entry, entry.downloadKey()?.let { downloads[it] }, runners.noRunnerLine)
    } else {
        PcPlayStateLoading
    }

    // Flattened once per recomposition into what this Dialog actually
    // draws and what Up/Down/A navigate: a section header (never
    // selectable), an info line (never selectable, e.g. a status message
    // or the compatibility summary), or a row (selectable, the same
    // ActionRow the sections produce). The rows sit under the three
    // headers of docs/SPEC.md 13, "Gaming mode" -- Play, About, Fix and
    // advanced -- so one list, one focus index, the same shape
    // GamelistOptionsMenu's own Select-button menu already uses, still
    // moves over the whole menu, while the headers say what each part
    // is for.
    // Whether this game's "Get it on" rows were hidden (docs/SPEC.md 7m);
    // one small preferences read, on entry, like the other remembered facts.
    var storeLinksHidden by remember(entry) { mutableStateOf(dev.droidtop.library.StoreLinkPrefs.hidden(context, gameIds)) }
    // Runs with -- WHICH runner, and how to change it: the first row of How it runs.
    val runsWithRow = if (!entry.missing && (!loaded || runners.options.isNotEmpty())) {
        ActionRow(
            title = "Runs with",
            detail = when {
                !loaded -> "Working out what can run this…"
                runner == null -> "Not chosen — ${runners.options.size} to choose from"
                else -> "${runner.label} - ${runner.reason}"
            },
            onSelect = if (loaded && runners.options.isNotEmpty()) ({ picking = true }) else null,
        )
    } else {
        null
    }
    // Run once with… -- another runner for one launch, the saved one kept: offered when there is a choice to make.
    val runOnceRow = if (!entry.missing && loaded && runners.options.count { it.selectable } > 1) {
        ActionRow("Run once with…", "Start it one time with another runner; Runs with stays as it is", onSelect = { pickingOnce = true })
    } else {
        null
    }
    // Run with Wine instead (Droidtop/tracker#752): the engine's own runner is the default even while it needs its core,
    // so Wine is never taken silently; this is the one press that makes it this game's runner, kept like a Runs with pick.
    val wineInsteadRow = if (
        loaded && runner?.option?.strategy == GameLaunchStrategy.ENGINEHOST &&
        LaunchStrategyOverridePrefs.get(context, entry.id) == null &&
        runners.options.any { it.strategy == GameLaunchStrategy.WINE_PREFIX && it.selectable }
    ) {
        ActionRow(
            stringResource(R.string.run_with_wine_instead),
            stringResource(R.string.run_with_wine_instead_detail),
            onSelect = {
                LaunchStrategyOverridePrefs.set(context, entry.id, GameLaunchStrategy.WINE_PREFIX)
                reloadToken++
            },
        )
    } else {
        null
    }
    val playEntries = buildList<ActionSheetItem> {
        // Play, or the one action that makes Play possible -- the
        // gamelist's own A makes this exact decision on its own
        // (docs/SPEC.md 7i, PcRunnerOptions.resolveAndPlay), and the
        // library's hero and the game page draw the same [PcPlayState],
        // so this row is a second way to reach the very same thing.
        if (entry.missing) {
            add(ActionRow("The folder is not there", missingFolderLine(entry), onSelect = null))
        } else {
            add(
                ActionRow(
                    title = playState.verb,
                    detail = playState.detail,
                    onSelect = if (loaded && playState.pressable) {
                        {
                            val store = if (playState.chooseEngine) null else playState.store
                            when (store) {
                                // Install and Update stop on the free-space
                                // offer first: the size and the room the
                                // chosen volume has are named before the
                                // store's screen opens (Droidtop/tracker#227).
                                StoreStage.INSTALL, StoreStage.UPDATE ->
                                    storeOffer = StoreInstallOffer(entry, checkNotNull(store))
                                StoreStage.WISHLIST -> status = openOnStore(context, entry)
                                // Downloading, Paused: the download is already
                                // in flight; a paused one resumes, a running one
                                // is in Downloads.
                                null -> Unit
                                else -> status = continueStoreDownload(context, entry, store) {
                                    onClose()
                                    onOpenDownloads()
                                }
                            }
                            if (store == null) {
                                if (entry.pcInfo?.installer != null) {
                                    scope.launch {
                                        status = "Working…"
                                        status = dev.droidtop.library.FolderInstalls.install(context, entry) { status = it }
                                    }
                                } else if (playState.chooseEngine) {
                                    if (engineChoice.folder != null) pickingEngine = true else status = "No game folder to pick an engine for"
                                } else if (isReady) {
                                    onClose()
                                    onLaunch()
                                } else if (setupAction != null) {
                                    scope.launch {
                                        status = "Working…"
                                        val failure = PcRunnerOptions.runAction(context, entry, setupAction) { status = it }
                                        status = failure
                                        reloadToken++
                                    }
                                }
                            }
                        }
                    } else {
                        null
                    },
                ),
            )
        }
        if (playState.store == StoreStage.UPDATE && isReady) {
            add(ActionRow("Play without updating", "Starts the installed build as it is", onSelect = { onClose(); onLaunch() }))
        }
        actions.play.forEach { add(it) }
    }

    run {
        // About: this copy -- where it is owned and where to get it, its
        // update state, its record in the person's library, and how it
        // runs for other people.
        // Where the game is owned, and where it could be got (docs/SPEC.md
        // 7m): "Owned on Steam and GOG" for a game a store owns; for one no
        // store owns, its scraped store and support links as "Get it on ..."
        // and "Support the developer" rows, hideable per game. Read from the
        // entries and their links only, so no lookup happens as this opens.
        val ownedOn = (grouping?.entriesByPath?.values ?: listOf(entry)).mapNotNull { it.ownership() }.ownershipLabel()
        // A page of a store droidtop runs ("Get it on Steam") opens in that store's own view, signed in, where the
        // person can buy or claim it and the store is synced after (docs/SPEC.md 7g, "A store's own pages"); any
        // other link goes to the device's browser.
        val openLink = { url: String ->
            dev.droidtop.library.Links.open(context, url)
            status = null
        }
        val pointers = if (ownedOn.isEmpty() && !storeLinksHidden) {
            dev.droidtop.library.StorePages.getItOn(entry.links) + dev.droidtop.library.StorePages.support(entry.links)
        } else {
            emptyList()
        }
        // A game a store owns keeps its scraped store links as plain
        // rows; one owned nowhere shows them as the pointers above instead.
        val plainLinks = if (ownedOn.isEmpty() && !storeLinksHidden) dev.droidtop.library.StorePages.other(entry.links) else entry.links
        // Compatibility: evidence, never a verdict and never a gate
        // (docs/SPEC.md 7i). gamenative's own reports when the entry
        // carries them, and ProtonDB for a game with a Windows route,
        // looked up only when asked.
        val compat = entry.pcInfo?.compatibility
        val offersProtonDb = !entry.missing && (hasWindowsRoute || entry.pcInfo?.storeId != null)
        val aboutEntries = buildList<ActionSheetItem> {
            if (ownedOn.isNotEmpty()) add(ActionNote(ownedOn))
            plainLinks.forEach { link -> add(ActionRow(link.label, link.url) { openLink(link.url) }) }
            if (pointers.isNotEmpty()) {
                pointers.forEach { link -> add(ActionRow(link.label, link.url) { openLink(link.url) }) }
                add(
                    ActionRow("Hide these for this game", "") {
                        dev.droidtop.library.StoreLinkPrefs.hide(context, gameIds)
                        storeLinksHidden = true
                    },
                )
                add(
                    ActionRow("Hide these for every game", "Brought back under Stores") {
                        dev.droidtop.library.StoreLinkPrefs.setHiddenEverywhere(context, true)
                        storeLinksHidden = true
                    },
                )
            }
            actions.info.forEach { add(it) }
            // Its guides (Droidtop/tracker#817): the folder's walkthrough, guide and readme in droidtop's reader, then the
            // web searches by its name (#363), one list.
            add(ActionRow("Guides", "Walkthroughs and readmes, with your place kept") { readingGuides = true })
            compat?.let {
                add(ActionNote(it.summary() + "\nOther people's results on other hardware."))
            }
            if (offersProtonDb) {
                val state = protonDb
                add(
                    ActionRow(
                        title = if (state is ProtonDbState.Found) state.summary.line() else "ProtonDB",
                        detail = state.detail(),
                        onSelect = if (state !is ProtonDbState.Looking) {
                            {
                                when (state) {
                                    is ProtonDbState.Found -> {
                                        dev.droidtop.library.Links.open(context, ProtonDbClient.pageUrl(state.appId))
                                        status = null
                                    }
                                    else -> {
                                        protonDb = ProtonDbState.Looking
                                        scope.launch { protonDb = lookUpProtonDb(entry, gameName) }
                                    }
                                }
                            }
                        } else {
                            null
                        },
                    ),
                )
            }
        }
        // What's new (docs/SPEC.md 7i, Droidtop/tracker#229): the developer's latest post on Steam, asked for like ProtonDB.
        val newsAppId = steamAppIdOf(entry)
        val newsRow = newsAppId?.let {
            val state = steamNews
            ActionRow(
                title = if (state is SteamNewsState.Found) state.item.title else "What's new",
                detail = state.detail(),
                onSelect = if (state !is SteamNewsState.Looking) {
                    {
                        if (state is SteamNewsState.Found) {
                            dev.droidtop.library.Links.open(context, SteamNewsClient.pageUrl(newsAppId))
                            status = null
                        } else {
                            steamNews = SteamNewsState.Looking
                            scope.launch { steamNews = lookUpSteamNews(newsAppId) }
                        }
                    }
                } else {
                    null
                },
            )
        }
        // The same row for a GOG game: the newest entry of the changelog GOG keeps for it.
        val gogId = if (newsAppId == null) gogProductIdOf(entry) else null
        val gogRow = gogId?.let { productId ->
            val state = gogNews
            ActionRow(
                title = "What's new",
                detail = state.detail(),
                onSelect = if (state !is GogNewsState.Looking) {
                    {
                        val page = (state as? GogNewsState.Found)?.changelog?.pageUrl
                        if (page != null) {
                            dev.droidtop.library.Links.open(context, page)
                            status = null
                        } else if (state !is GogNewsState.Found) {
                            gogNews = GogNewsState.Looking
                            scope.launch { gogNews = lookUpGogChangelog(productId) }
                        }
                    }
                } else {
                    null
                },
            )
        }
        pageInfo = aboutEntries + listOfNotNull(newsRow, gogRow)
    }

    // The categories (docs/SPEC.md 7i, "The game menu", owner 2026-10-23: "No more than one nested menu deep, and
    // fairly short categories"): each opens one page and nothing deeper, and a category with nothing in it is not
    // offered, because a row opening an empty page is a category claiming to exist.
    val pages: Map<PcMenuPage, List<ActionSheetItem>> = mapOf(
        PcMenuPage.Info to pageInfo,
        PcMenuPage.Organise to actions.organise,
        PcMenuPage.Install to actions.install,
        PcMenuPage.Updates to actions.updates,
        PcMenuPage.Runs to (listOfNotNull(runsWithRow, wineInsteadRow, runOnceRow) + actions.runs),
        PcMenuPage.Saves to actions.saves,
        PcMenuPage.Manage to actions.manage,
    )
    val entries = buildList<ActionSheetItem> {
        when (page) {
            PcMenuPage.Root -> {
                addAll(playEntries)
                status?.let { add(ActionNote(it)) }
                PcMenuPage.categories.forEach { category ->
                    val rows = pages[category].orEmpty().filterIsInstance<ActionRow>()
                    if (rows.any { it.onSelect != null }) {
                        add(
                            ActionRow(category.title, categoryLine(rows.map { it.title })) {
                                page = category
                                focusIndex = 0
                            },
                        )
                    }
                }
                if (hasPluginActions) {
                    add(
                        ActionRow(dev.droidtop.shell.gamepad.PLUGIN_ACTIONS_LABEL, "What your plugins can do with this game") {
                            pluginScreen = dev.droidtop.shell.gamepad.pluginActionsScreen(
                                entry,
                                dev.droidtop.pluginhost.PluginModes.Surfaces.GAMING_GAME_MENU,
                            ) { reply -> pluginScreen = reply }
                        },
                    )
                }
                add(ActionRow("Close", "", onSelect = onClose))
            }
            else -> {
                add(ActionHeading(page.title))
                status?.let { add(ActionNote(it)) }
                addAll(pages[page].orEmpty())
                add(ActionRow("Back", "") { page = PcMenuPage.Root; focusIndex = 0 })
            }
        }
    }

    val rowCount = entries.count { it is ActionRow }
    // The row list changes under the cursor (a page opens, a row goes
    // away); it never points past the end it is drawn against.
    LaunchedEffect(rowCount, page) {
        focusIndex = focusIndex.coerceIn(0, (rowCount - 1).coerceAtLeast(0))
    }
    // B and the system Back step out of a sub-page before they close the
    // menu; both reach the same function (the Dialog's own dismiss is Back).
    val goBack = {
        if (page != PcMenuPage.Root) {
            page = PcMenuPage.Root
            focusIndex = 0
        } else {
            onClose()
        }
    }
    // One window at a time: a sheet this menu opens (DLC and versions, the
    // install offer, a picker, a text field) takes the screen while it is
    // open and the menu comes back, where it was, when it closes. Drawn under
    // it, the menu showed round the smaller sheet with its own Close row
    // (console, build 1519, Droidtop/tracker#371).
    val childOpen = (contentOpen && ownStore != null) || storeOffer != null || pickingMatch ||
        pickingReplacement || (pickingSameGame && grouping != null) || renaming || editingSource != null
    if (!childOpen) {
        ActionSheet(
            title = gameName,
            subtitle = entry.identityLine(available),
            rows = entries,
            // B and the system Back step out of a page before they close the menu; L2, the button that opened it,
            // or Select on the game page close it from any page (proposal 6.E, Droidtop/tracker#644 slice 4).
            onBack = goBack,
            onDismiss = onClose,
            focus = focus,
            width = 560.dp,
            focusLabel = "Game options",
        )
    }
    if (contentOpen && ownStore != null) {
        StoreContentSheet(
            entry = entry,
            store = ownStore,
            onApplied = { installNeeded ->
                contentOpen = false
                if (installNeeded) {
                    scope.launch { status = startOwnStoreInstall(context, entry, ownStore, "") }
                } else {
                    status = "Saved"
                }
            },
            onDismiss = { contentOpen = false },
        )
    }
    // The free-space offer before a store install or update: its own
    // window over the menu, the one place the volume is chosen
    // (Droidtop/tracker#227).
    StoreInstallOfferSheet(
        offer = storeOffer,
        onProceed = { o, volumePath ->
            storeOffer = null
            val own = o.entry.ownStore()
            if (own == null) {
                status = NO_STORE_LINE
            } else {
                scope.launch { status = startOwnStoreInstall(context, o.entry, own, volumePath) }
            }
        },
        onDismiss = { storeOffer = null },
    )
}

/** A minimal full-bleed [Dialog] host for a screen written as a plain fillMaxSize() composable (see the call sites above). */
@Composable
internal fun FullScreenOverlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ShellDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        GatePadInThisDialog()
        content()
    }
}

/** Which page of [PcGameMenu] is showing: the top, or one of the categories it opens (one level, never deeper). */
internal enum class PcMenuPage(val title: String) {
    Root("Game options"),
    Info("Info and art"),
    Organise("Organise"),
    Install("Install and files"),
    Updates("Updates"),
    Runs("How it runs"),
    Saves("Controls and saves"),
    // The per-game tools, for every kind of game (owner, 2026-10-23, Droidtop/tracker#636).
    Manage("Manage"),
    ;

    companion object {
        /** The categories, in the order the top page lists them (owner, 2026-10-23). */
        val categories = listOf(Info, Organise, Install, Updates, Runs, Saves, Manage)
    }
}

/** A category row's line: the first rows it holds, so the label never hides what is inside ("Scrape, Choose match, Title"). Pure. */
internal fun categoryLine(titles: List<String>): String =
    titles.take(3).joinToString(", ") + if (titles.size > 3) ", and more" else ""

/**
 * One line under the title: where it came from, what engine it is, how
 * big it is -- or, for a game the walk no longer finds, the one fact
 * that matters, in the words the card uses (docs/SPEC.md 7g).
 */
private fun LibraryEntry.identityLine(update: String?): String = if (missing) "broken · missing" else buildString {
    append(sourceLabel())
    engineLabel()?.let { append(" · ").append(it) }
    val size = pcInfo?.sizeBytes ?: 0L
    if (size > 0) {
        append(" · ")
        append(dev.droidtop.library.format.ValueFormat.size(size))
        append(if (pcInfo?.installed == true) " installed" else " to download")
    } else if (pcInfo?.installed == false) {
        append(" · not installed")
    }
    // The same words as the card's line (docs/SPEC.md 7g).
    update?.let { append(" · ").append(GameUpdates.line(it)) }
}


/**
 * The menu's rows by where they are filed: the top page ([play]) and one
 * list per category ([PcMenuPage.categories]).
 */
private data class PcMenuSections(
    val play: List<ActionRow>,
    val info: List<ActionRow>,
    val organise: List<ActionRow>,
    val install: List<ActionRow>,
    val updates: List<ActionRow>,
    val runs: List<ActionRow>,
    val saves: List<ActionRow>,
    val manage: List<ActionRow>,
)

/** One screen of the Manage section: the registered screen it opens and its row's words. */
private class ManageTool(val screenId: String, val title: String, val detail: String)

/** A store game's own rows, filed by what they are for: other ways to play, the install, and its cloud saves. */
private data class StoreRows(val play: List<ActionRow>, val install: List<ActionRow>, val saves: List<ActionRow>)

/** The rows of the runner this game uses: how it runs, and where its controls and saves are. */
private data class RunnerRows(val runs: List<ActionRow>, val saves: List<ActionRow>) {
    companion object {
        val NONE = RunnerRows(emptyList(), emptyList())
    }
}

/** What the Engine row needs: the pin's folder key, whether it is set, and the engines it can name. */
private data class EngineChoice(val folder: String?, val pinned: Boolean, val engines: List<Pair<String, GameEngine>>) {
    companion object {
        val NONE = EngineChoice(null, false, emptyList())
    }
}

/**
 * The menu's rows, filed into its top page and categories ([PcMenuSections],
 * docs/SPEC.md 7i, "The game menu"). The rows that need state this
 * function has not got are handed in pre-built; everything else is
 * built here from [entry] alone.
 */
@Composable
private fun rememberPcActions(
    titleRows: List<ActionRow>,
    // A store game: its store's rows. Null for a game no store owns.
    storeRows: StoreRows?,
    group: dev.droidtop.library.LibraryGameGroup?,
    currentId: String,
    onOpenOther: (LibraryEntry) -> Unit,
    replacements: Int,
    onReplace: () -> Unit,
    sameGameRow: ActionRow?,
    updateRows: List<ActionRow>,
    entry: LibraryEntry,
    runner: ResolvedRunner?,
    media: Int,
    onScrape: () -> Unit,
    onRescrapeWith: () -> Unit,
    onChooseMatch: () -> Unit,
    onViewMedia: () -> Unit,
    onCollections: () -> Unit,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenPage: (() -> Unit)?,
    engineRow: ActionRow?,
    onEnginehost: (android.content.Intent) -> Unit,
    hasWindowsRoute: Boolean,
    manageTools: List<ActionRow>,
    onOpenWineSettings: () -> Unit,
    wineSettings: WineGameSettings?,
    onImportLutris: () -> Unit,
    onChooseProgram: (() -> Unit)?,
    onOpenProperties: (() -> Unit)?,
): PcMenuSections {
    val isEngineGame = entry.kind != LibraryEntryKind.WINE_PROFILE
    val runsOnEnginehost = runner?.option?.strategy == GameLaunchStrategy.ENGINEHOST

    val runnerRows = runnerRows(
        runsOnEnginehost = runsOnEnginehost,
        hasWindowsRoute = hasWindowsRoute,
        isEngineGame = isEngineGame,
        onEnginehost = onEnginehost,
        onOpenPrefix = onOpenWineSettings,
        wineSettings = wineSettings,
        onImportLutris = onImportLutris,
        onChooseProgram = onChooseProgram,
        onOpenProperties = onOpenProperties,
    )
    return PcMenuSections(
        // The top: the store's other ways to play (Steam Link), the page, and the favourite. The primary action and
        // Play without updating are added where the play state is known.
        play = storeRows?.play.orEmpty() + listOfNotNull(
            onOpenPage?.let { ActionRow("Game page", "Its artwork, facts and the one Play button", onSelect = it) },
            ActionRow(
                if (favorite) "Remove from favourites" else "Add to favourites",
                if (favorite) "It is in your Favourites collection" else "Puts it in your Favourites collection",
                onSelect = onToggleFavorite,
            ),
        ),
        // Info and art: what is known about the game and where it came from. The links, compatibility and news rows are
        // added where their state lives.
        info = listOfNotNull(
            ActionRow("Scrape", "Looks this game up in the PC sources", onSelect = onScrape),
            ActionRow("Rescrape with…", "Looks it up again in a source you pick, this game only", onSelect = onRescrapeWith),
            ActionRow("Choose match", "Pick the right game by hand when the scraper guessed wrong", onSelect = onChooseMatch),
            if (media > 1) ActionRow("View media", "$media images and videos scraped for this game", onSelect = onViewMedia) else null,
        ) + titleRows.take(1),
        // Organise: where the game sits in the library, its parts, and the other folders that are the same game.
        organise = listOf(ActionRow("Collections", "Which of your collections this game is in", onSelect = onCollections)) +
            titleRows.drop(1) +
            listOfNotNull(
                // The fold, from whichever side the user is standing on (docs/SPEC.md 7g). On the game that is not
                // there it is always offered, because that is the screen a person comes to in order to fix it, and it
                // says so when there is nothing to offer yet. On a game that IS here it appears only when something
                // is actually missing that it could be: an action with nothing behind it is not an action.
                when {
                    entry.missing -> ActionRow(
                        "Find its replacement",
                        if (replacements == 0) "None found" else "$replacements found",
                        onSelect = if (replacements == 0) null else onReplace,
                    )
                    replacements > 0 -> ActionRow("This replaces a missing game", "$replacements missing", onSelect = onReplace)
                    else -> null
                },
                // Two games here that are one game (docs/SPEC.md 7m): offered only when another game's name is alike enough.
                sameGameRow,
            ) +
            // The game's other folders, when it has any: its parts, and the versions of each.
            group?.let { versionsRows(it, currentId, onOpenOther) }.orEmpty(),
        // Install and files: a store game's install, or the plain fact that droidtop does not manage a folder game's.
        install = storeRows?.install ?: listOf(ActionRow("Manage install", "A folder on this device", onSelect = null)),
        updates = updateRows,
        // How it runs: which runner, which engine, and ONE runner's own settings, for the runner this game uses.
        // Until build 540 every game got both: a Ren'Py game running on enginehost carried a Wine section it can do
        // nothing with. The Runs with row is added where the runner state lives.
        runs = listOfNotNull(engineRow) + runnerRows.runs,
        saves = runnerRows.saves + storeRows?.saves.orEmpty(),
        manage = manageTools,
    )
}

/**
 * Every folder this one game is: a row per segment and a row per version,
 * each saying what it carries, with the one that is open marked.
 *
 * Minimum by design (docs/SPEC.md 7m): the model's whole job is that a
 * game is one entry, so what this menu needs is a way to reach the other
 * folders of it, which is a list of rows -- the same rows every other
 * action on this screen is.
 */
private fun versionsRows(
    group: dev.droidtop.library.LibraryGameGroup,
    currentId: String,
    onOpenOther: (LibraryEntry) -> Unit,
): List<ActionRow>? {
    val rows = mutableListOf<ActionRow>()
    val game = group.game
    for (segment in game.segments) {
        for (version in segment.versions) {
            rows += row(group, version, currentId, onOpenOther, label = segment.label)
        }
    }
    for (version in game.versions) {
        // One game owned on several stores (docs/SPEC.md 7m): a row per
        // store, because each store's copy has its own install and its own
        // Play, and the person chooses which one to open.
        val stores = version.copies.filter { group.entryFor(it)?.ownership() != null }
        if (stores.size > 1) {
            stores.forEach { rows += storeCopyRow(group, it, currentId, onOpenOther) }
        } else {
            rows += row(group, version, currentId, onOpenOther, label = null)
        }
    }
    if (rows.size < 2) return null
    return rows
}

/** One store's copy of a game owned on several: named by the store, saying whether it is installed. */
private fun storeCopyRow(
    group: dev.droidtop.library.LibraryGameGroup,
    copy: dev.droidtop.library.GameCopy,
    currentId: String,
    onOpenOther: (LibraryEntry) -> Unit,
): ActionRow {
    val target = group.entryFor(copy)
    val store = target?.ownership()?.label ?: copy.source ?: copy.path
    val detail = buildString {
        append(if (target?.id == currentId) "Open now" else "Open this one")
        append(" - ").append(if (copy.installed) "installed" else "not installed")
    }
    return ActionRow(store, detail, onSelect = if (target == null || target.id == currentId) null else ({ onOpenOther(target) }))
}

private fun row(
    group: dev.droidtop.library.LibraryGameGroup,
    version: dev.droidtop.library.GameVersion,
    currentId: String,
    onOpenOther: (LibraryEntry) -> Unit,
    label: String?,
): ActionRow {
    val copy = version.playable
    val target = copy?.let { group.entryFor(it) }
    // A row is named by what it IS: its part, its version, or -- when the
    // folder name carries neither -- the folder's own name (docs/SPEC.md
    // 7m). It used to read "This version", which names the row you are
    // being asked to switch TO after the one you are already on.
    val folderName = copy?.path?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
    val title = listOfNotNull(label, version.version.takeIf { it.isNotEmpty() }?.let { "v$it" })
        .joinToString(" - ")
        .ifEmpty { folderName ?: target?.title ?: "" }
    val detail = buildString {
        append(if (target?.id == currentId) "Open now" else "Open this one")
        copy?.language?.let { append(" - ").append(it) }
        if (copy?.mods?.isNotEmpty() == true) append(" - ").append(copy.mods.joinToString(" "))
        version.latestKnown?.let { append(" - ").append(GameUpdates.line(it)) }
    }
    return ActionRow(title, detail, onSelect = if (target == null || target.id == currentId) null else ({ onOpenOther(target) }))
}

/**
 * What the game's Wine settings hold on this device. An x86_64 device runs
 * x86_64 Wine directly, so it has no FEXCore or Box64 choice to name (the
 * rows themselves follow the same rule, WineOptions; the ABI test is the one
 * X86_64GuestLibs.isX86_64Host makes, the device's first supported ABI).
 */
internal fun wineRowDetail(x86_64Host: Boolean): String =
    if (x86_64Host) {
        "Wine build, graphics, prefix settings"
    } else {
        "Wine build, FEXCore or Box64, graphics, prefix settings"
    }

/**
 * The rows that depend on HOW this game runs: enginehost's own settings
 * for a game enginehost runs, the Wine prefix for a game that takes the
 * Windows route, and nothing at all for a game whose runner is neither
 * (a native Linux build, or a game with no runner on this device -- the
 * Play row above already says so, and rows of dead settings repeating it
 * are not information).
 *
 * Filed in How it runs and in Controls and saves; every row names
 * its runner in its own subtitle ("the Windows prefix this game runs
 * in", "Enginehost's own save settings").
 */
private fun runnerRows(
    runsOnEnginehost: Boolean,
    hasWindowsRoute: Boolean,
    isEngineGame: Boolean,
    onEnginehost: (android.content.Intent) -> Unit,
    onOpenPrefix: () -> Unit,
    wineSettings: WineGameSettings?,
    onImportLutris: () -> Unit,
    onChooseProgram: (() -> Unit)?,
    onOpenProperties: (() -> Unit)?,
): RunnerRows = when {
    runsOnEnginehost -> RunnerRows(
        runs = listOfNotNull(
            if (isEngineGame) {
                ActionRow("Engine settings", "Opens Enginehost's settings", onSelect = { onEnginehost(EngineHost.settingsIntent()) })
            } else {
                null
            },
        ),
        saves = listOf(
            ActionRow(
                "Controls",
                "Opens Enginehost's controls for this game",
                onSelect = { onEnginehost(EngineHost.settingsIntent()) },
            ),
            ActionRow("Saves", "Opens Enginehost's save settings", onSelect = { onEnginehost(EngineHost.savesSettingsIntent()) }),
        ),
    )
    hasWindowsRoute -> RunnerRows(runs = listOfNotNull(
        ActionRow("Wine and graphics", wineRowDetail(android.os.Build.SUPPORTED_ABIS.firstOrNull() == "x86_64"), onSelect = onOpenPrefix),
        // Which program the game runs: the person's choice (or an
        // import's), else the one droidtop picks (docs/SPEC.md 7i).
        ActionRow(
            "Program",
            wineSettings?.executable?.let { exe ->
                listOfNotNull(
                    exe,
                    wineSettings.arguments.takeIf { it.isNotEmpty() }?.joinToString(" ", prefix = "with "),
                    wineSettings.source?.let { "from $it" },
                ).joinToString(" - ")
            } ?: "The one droidtop picks; choose another",
            onSelect = onChooseProgram,
        ),
        // Launch options and environment variables for this game (docs/SPEC.md 7i, "Game properties").
        ActionRow(
            "Game properties",
            "Launch options, environment variables, and what will run",
            onSelect = onOpenProperties,
        ),
        ActionRow(
            "Import a Lutris install script",
            "Review every change first",
            onSelect = onImportLutris,
        ),
    ), saves = listOf(
        ActionRow("Controls", "In its prefix's controller tab", onSelect = null),
        ActionRow("Saves", "Inside its Windows prefix", onSelect = null),
    ))
    // A native Linux build's tools are in Manage, with the Wine ones (docs/SPEC.md 7i, "Manage").
    else -> RunnerRows.NONE
}

/** What the detail knows of [PcGameMenu]'s entry from names alone, worked out off the main thread. */
private data class DetailNames(
    val forId: String?,
    val grouping: dev.droidtop.library.LibraryGameGroup?,
    val replacements: List<MissingGames.Candidate>,
    val similar: List<SimilarGames.Candidate>,
) {
    companion object {
        val NONE = DetailNames(forId = null, grouping = null, replacements = emptyList(), similar = emptyList())
    }
}

/**
 * What a replacement row says under the name: whether this is the same
 * game by name or a suggestion, and where it is. Never a bare percentage
 * -- "0.73" is not a reason a person can act on, and the path is.
 */
private fun MissingGames.Candidate.line(): String {
    val where = entry.groupingPath()
    val why = if (certain) "The same name" else "A similar name"
    return listOfNotNull(why, where).joinToString(" - ")
}

/** The same for a game that might be this one: why, and where its folders are, or the store it is owned on. */
private fun SimilarGames.Candidate.line(): String {
    val store = store
    if (store != null) return (if (score >= 1.0) "The same name" else "A similar name") + " - owned on ${storeLabel(store.substringBefore(':'))}"
    val folders = group.entriesByPath.values.mapNotNull { it.groupingPath() }.sorted()
    val where = folders.first() + if (folders.size > 1) " and ${folders.size - 1} more" else ""
    return "A similar name - $where"
}

/**
 * The games [entry] could be folded with: the missing ones, when this
 * game is here, and the detected ones when it is not. One ordering for
 * both directions ([dev.droidtop.library.MissingGames]), because it is
 * one question asked from two sides.
 */
private fun replacementCandidatesFor(
    entry: LibraryEntry,
    siblings: List<LibraryEntry>,
): List<MissingGames.Candidate> =
    MissingGames.candidates(
        target = entry,
        among = siblings.filter { it.missing != entry.missing },
    )



/** The ProtonDB row's states: nothing is fetched until the person asks. */
private sealed interface ProtonDbState {
    data object NotAsked : ProtonDbState
    data object Looking : ProtonDbState
    data class Found(val summary: ProtonDbSummary, val appId: Long) : ProtonDbState
    data class Unavailable(val line: String) : ProtonDbState

    fun detail(): String = when (this) {
        NotAsked -> "Look up other people's reports for this game"
        Looking -> "Looking it up…"
        is Found -> "Reports from Linux PCs running Proton, not from this device. Select to open ProtonDB"
        is Unavailable -> line
    }
}

/** The What's new row's states: nothing is fetched until the person asks. */
private sealed interface SteamNewsState {
    data object NotAsked : SteamNewsState
    data object Looking : SteamNewsState
    data class Found(val item: SteamNewsItem) : SteamNewsState
    data class Unavailable(val line: String) : SteamNewsState

    fun detail(): String = when (this) {
        NotAsked -> "The developer's latest notes on Steam"
        Looking -> "Looking it up…"
        is Found -> listOf(item.kindAndDate(), item.excerpt.takeIf { it.isNotEmpty() }, "Select for every post on Steam")
            .filterNotNull().joinToString(". ")
        is Unavailable -> line
    }
}

/** The What's new row's states for a GOG game: nothing is fetched until the person asks. */
private sealed interface GogNewsState {
    data object NotAsked : GogNewsState
    data object Looking : GogNewsState
    data class Found(val changelog: GogChangelog) : GogNewsState
    data class Unavailable(val line: String) : GogNewsState

    fun detail(): String = when (this) {
        NotAsked -> "The newest entry of the game's changelog on GOG"
        Looking -> "Looking it up…"
        is Found -> listOfNotNull(changelog.latest, "Select for the whole changelog on GOG".takeIf { changelog.pageUrl != null }).joinToString(". ")
        is Unavailable -> line
    }
}

/** The GOG product id of a game GOG sells: its own row, or the store row an engine copy kept; null otherwise. */
private fun gogProductIdOf(entry: LibraryEntry): Long? =
    listOfNotNull(entry.id, entry.pcInfo?.storeId)
        .firstNotNullOfOrNull { id -> id.takeIf { it.startsWith("gog:") }?.substringAfter(':')?.toLongOrNull() }

/** Network: the IO dispatcher. Every outcome is a sentence. */
private suspend fun lookUpGogChangelog(productId: Long): GogNewsState = withContext(Dispatchers.IO) {
    runCatching {
        when (val lookup = GogChangelogClient.latest(productId)) {
            is ScrapeLookup.Found -> GogNewsState.Found(lookup.value)
            ScrapeLookup.NoMatch -> GogNewsState.Unavailable("GOG keeps no changelog for this game")
            is ScrapeLookup.Refused -> GogNewsState.Unavailable("GOG's changelog could not be reached (error ${lookup.httpStatus})")
        }
    }.getOrElse { GogNewsState.Unavailable("GOG's changelog could not be reached: ${FailureText.of("PcGameMenu", it)}") }
}

/** The Steam app id a game is known by on Steam itself (its own row, or the store row an engine copy kept); null otherwise. */
private fun steamAppIdOf(entry: LibraryEntry): Long? =
    listOfNotNull(entry.id, entry.pcInfo?.storeId)
        .firstNotNullOfOrNull { id -> id.takeIf { it.startsWith("steam:") }?.substringAfter(':')?.toLongOrNull() }

/** Network: the IO dispatcher. Every outcome is a sentence. */
private suspend fun lookUpSteamNews(appId: Long): SteamNewsState = withContext(Dispatchers.IO) {
    runCatching {
        when (val lookup = SteamNewsClient.latest(appId)) {
            is ScrapeLookup.Found -> SteamNewsState.Found(lookup.value)
            ScrapeLookup.NoMatch -> SteamNewsState.Unavailable("The developer has posted no news on Steam for this game")
            is ScrapeLookup.Refused -> SteamNewsState.Unavailable("Steam's news could not be reached (error ${lookup.httpStatus})")
        }
    }.getOrElse { SteamNewsState.Unavailable("Steam's news could not be reached: ${FailureText.of("PcGameMenu", it)}") }
}

/** Network: the IO dispatcher. Every outcome is a sentence, never a silent blank. */
private suspend fun lookUpProtonDb(entry: LibraryEntry, name: String): ProtonDbState = withContext(Dispatchers.IO) {
    runCatching {
        val appId = ProtonDbClient.steamAppIdFor(entry, name)
            ?: return@runCatching ProtonDbState.Unavailable("No Steam ID is known for $name, and ProtonDB lists only Steam games")
        when (val lookup = ProtonDbClient.summary(appId)) {
            is ScrapeLookup.Found -> ProtonDbState.Found(lookup.value, appId)
            ScrapeLookup.NoMatch -> ProtonDbState.Unavailable("ProtonDB has no reports for this game yet")
            is ScrapeLookup.Refused -> ProtonDbState.Unavailable("ProtonDB could not be reached (error ${lookup.httpStatus})")
        }
    }.getOrElse { ProtonDbState.Unavailable("ProtonDB could not be reached: ${FailureText.of("PcGameMenu", it)}") }
}

/** The name one folder derives for its game, before any grouping is worked out. */
private fun ownNameOf(entry: LibraryEntry): String =
    entry.groupingPath()?.let { dev.droidtop.library.GameNaming.derive(it).name.ifEmpty { entry.title } } ?: entry.title

/** The folder's own name, as it is on disk: what a title is read from (docs/SPEC.md 7n). Names only. */
private fun folderNameOf(entry: LibraryEntry): String =
    entry.groupingPath()?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: entry.title
