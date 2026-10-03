package dev.droidtop.shell.gamepad.pc

import dev.droidtop.shell.gamepad.menuMove
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.window.Dialog
import dev.droidtop.library.EngineHost
import dev.droidtop.library.EngineOverridePrefs
import dev.droidtop.library.EnginesDatabase
import dev.droidtop.library.F95Thread
import dev.droidtop.library.GameEngine
import dev.droidtop.library.GameLinks
import dev.droidtop.library.ownership
import dev.droidtop.library.ownershipLabel
import dev.droidtop.library.GameNaming
import dev.droidtop.library.GameUpdates
import dev.droidtop.library.StoreDownloads
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
import dev.droidtop.library.scraper.PcScraper
import dev.droidtop.library.scraper.ProtonDbClient
import dev.droidtop.library.scraper.ProtonDbSummary
import dev.droidtop.library.scraper.ScrapeLookup
import dev.droidtop.library.scraper.line
import dev.droidtop.shell.gamepad.CollectionMembershipEditor
import dev.droidtop.shell.gamepad.ManualMatchPicker
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.MenuPanel
import dev.droidtop.shell.gamepad.TextEditDialog
import dev.droidtop.shell.gamepad.MediaViewer
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HideSystemBarsInThisDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * container settings, ProtonDB, the Lutris import, the F95 link and
 * update state, merge and versions/segments, favourite/collections/
 * scrape.
 *
 * Those rows sit under three section headers -- Play, About, Fix and
 * advanced (docs/SPEC.md 13, "Gaming mode", 2026-09-29) -- the same rows
 * and actions the one flat list carried, filed under the question a
 * player opening this menu is asking: play it, learn about this copy,
 * or fix it.
 *
 * A itself no longer opens this menu (docs/SPEC.md 7i): on the gamelist,
 * A launches when the resolved runner is ready and runs the one setup
 * action when it is not ([dev.droidtop.library.PcRunnerOptions.resolveAndPlay]),
 * exactly like a console ROM's A. This menu opens on Y ("Game options"),
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var favorite by remember(entry) { mutableStateOf(entry.favorite) }

    var runners by remember(entry) { mutableStateOf(PcRunners(null, emptyList())) }
    var resolved by remember(entry) { mutableStateOf<ResolvedRunner?>(null) }
    var loaded by remember(entry) { mutableStateOf(false) }
    var reloadToken by remember(entry) { mutableStateOf(0) }
    var picking by remember(entry) { mutableStateOf(false) }
    var status by remember(entry) { mutableStateOf<String?>(null) }
    var viewingMedia by remember(entry) { mutableStateOf(false) }
    var pickingMatch by remember(entry) { mutableStateOf(false) }
    var editingCollections by remember(entry) { mutableStateOf(false) }
    var pickingReplacement by remember(entry) { mutableStateOf(false) }
    var pickingSameGame by remember(entry) { mutableStateOf(false) }
    var renaming by remember(entry) { mutableStateOf(false) }
    var progressToken by remember(entry) { mutableStateOf(0) }
    var editingThread by remember(entry) { mutableStateOf(false) }
    var pickingEngine by remember(entry) { mutableStateOf(false) }
    var engineChoice by remember(entry) { mutableStateOf(EngineChoice.NONE) }
    var importingLutris by remember(entry) { mutableStateOf(false) }
    var gettingGames by remember(entry) { mutableStateOf(false) }
    // This game's Wine and graphics settings (docs/SPEC.md 5a), in a sheet
    // over the menu; null when it is closed.
    var wineScreen by remember(entry) { mutableStateOf<dev.droidtop.library.settings.CatalogScreen?>(null) }
    // The free-space offer before a store install or update opens its own
    // window over the menu (Droidtop/tracker#227); null when it is closed.
    var storeOffer by remember(entry) { mutableStateOf<StoreInstallOffer?>(null) }
    var wineSettings by remember(entry) { mutableStateOf<WineGameSettings?>(null) }
    var protonDb by remember(entry) { mutableStateOf<ProtonDbState>(ProtonDbState.NotAsked) }
    // Which page of the menu is showing and which row of it the cursor is
    // on (docs/SPEC.md 7i): the short top page, or one of the two longer
    // lists it opens. A sub-page's B goes back to the top, never out.
    var page by remember(entry) { mutableStateOf(PcMenuPage.Root) }
    var focusIndex by remember(entry) { mutableStateOf(0) }

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

    // The game's update source (docs/SPEC.md 7g): a thread link is the
    // GAME's, so it is read and written for every folder of it, and read
    // from the library rather than from [entry], which is the list's copy
    // from before anything on this screen changed it.
    val isFolder = entry.groupingPath() != null
    val gameIds = grouping?.entriesByPath?.keys ?: setOf(entry.id)
    var linksToken by remember(entry) { mutableStateOf(0) }
    val links by produceState<GameLinks?>(null, gameIds, linksToken) {
        value = if (isFolder) library.gameLinks(gameIds) else null
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
    if (gettingGames) {
        // The one Get games screen (docs/SPEC.md 12a "Get games everywhere"), asking which system.
        dev.droidtop.shell.gamepad.GetGamesSheet(
            dev.droidtop.library.integrations.GetGamesContext.PC,
            onDismiss = { gettingGames = false },
        )
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
                "Press again: ${choice.title} becomes part of $here. Both folders stay; the history, favourite and collections move to one card"
            },
            workingLine = "Making them one game...",
            onPick = { index ->
                val other = candidates[index].group
                if (library.mergeGames(sameGame, other)) {
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
            subtitle = "The name this game is drawn and scraped under. droidtop reads it from the folder name " +
                "(${folderNameOf(entry)}); your own title is kept across every rescan. " +
                "Clear it and save to go back to the folder's.",
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
    if (editingThread) {
        TextEditDialog(
            title = "F95zone thread",
            subtitle = F95_THREAD_HELP,
            initial = links?.f95Thread?.let { F95Thread.url(it) }.orEmpty(),
            onCommit = { text ->
                editingThread = false
                scope.launch {
                    linkF95ThreadFromText(library, gameIds, text) { status = it }
                    linksToken++
                }
            },
            onDismiss = { editingThread = false },
        )
    }

    // The replacement row is only worth drawing when there is somebody to offer.
    val replacements = names.replacements
    val runner = resolved
    val hasWindowsRoute = runners.options.any {
        it.strategy == GameLaunchStrategy.WINE_PREFIX && it.state != RunnerState.NOT_FOR_THIS_GAME
    }
    // Which part of a multi-part game this entry is, and whether it is finished
    // (docs/SPEC.md 7n). Names only: the segments came from the folder names.
    val partOf = grouping?.game?.takeIf { it.segments.size > 1 }?.segments
        ?.firstOrNull { segment -> segment.versions.any { version -> version.copies.any { it.path == entry.id } } }
    val finishedHere = grouping?.finished?.contains(entry.id) == true
    val titleRows = listOfNotNull(
        PcActionRow(
            "Title",
            if (entry.gameName.isNullOrBlank()) {
                "Read from the folder name (${folderNameOf(entry)}). Select to use your own"
            } else {
                "Your own title, kept across rescans. Select to change it or clear it"
            },
            { renaming = true },
        ),
        partOf?.let { part ->
            PcActionRow(
                if (finishedHere) "Not finished with ${part.label}" else "Finished with ${part.label}",
                if (finishedHere) {
                    "Play goes back to this part"
                } else {
                    "Play then continues with the next part of $gameName"
                },
                {
                    scope.launch {
                        withContext(Dispatchers.IO) { PartProgress.setFinished(context, entry.id, !finishedHere) }
                        progressToken++
                        onProgressChanged()
                    }
                },
            )
        },
        partOf?.let { part ->
            PcActionRow(
                "Make ${part.label} its own game",
                "Takes it out of $gameName and lists it on its own. Clearing its title puts it back",
                {
                    scope.launch {
                        library.renameGame(listOf(entry.id), "$gameName ${part.label}")
                        status = "${part.label} is a game of its own now."
                    }
                },
            )
        },
    )
    val actions = rememberPcActions(
        titleRows = titleRows,
        group = group,
        currentId = entry.id,
        onOpenOther = onOpenOther,
        replacements = replacements.size,
        onReplace = { pickingReplacement = true },
        sameGameRow = names.similar.size.takeIf { it > 0 }?.let { count ->
            PcActionRow(
                "The same game as...",
                "$count ${if (count == 1) "game has a similar name" else "games have similar names"}; " +
                    "picking one makes the two one game, keeping both folders",
                { pickingSameGame = true },
            )
        },
        updateRows = if (!isFolder) {
            emptyList()
        } else {
            listOfNotNull(
                PcActionRow("F95zone thread", f95Line(links, available, versions), { editingThread = true }),
                links?.f95Thread?.let { thread ->
                    PcActionRow(
                        "Check for an update now",
                        links?.check?.let { "Last checked " + android.text.format.DateUtils.getRelativeTimeSpanString(it.checkedAtEpochMs) }
                            ?: "Not checked yet",
                        {
                            scope.launch {
                                status = "Checking thread $thread..."
                                status = checkF95ThreadAndSay(library, gameIds, thread, versions, entry.latestKnown)
                                linksToken++
                            }
                        },
                    )
                },
            )
        },
        entry = entry,
        runner = runner,
        media = media.size,
        onScrape = {
            status = "Scraping ${entry.title}…"
            scope.launch { status = PcScraper.scrape(context, listOf(entry)) }
        },
        onChooseMatch = { pickingMatch = true },
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
                PcActionRow(
                    "Engine",
                    when {
                        engine == null -> "Not detected as an engine game; pick one if it is"
                        engineChoice.pinned -> "${engine.displayName()} - your choice"
                        else -> "${engine.displayName()} - detected; pick another if that is wrong"
                    },
                    { pickingEngine = true },
                )
            }
        },
        onEnginehost = { intent ->
            status = runCatching {
                context.startActivity(intent)
                null
            }.getOrElse { "Enginehost didn't take that: ${it.message}" }
        },
        // The store page: UI :runtime-windows already compiles from the
        // vendored gamenative tree, hosted by an :app Activity (build-plan
        // step 5). Started by explicit class name because this module
        // cannot depend on :app -- the same route every other cross-module
        // screen here takes.
        onOpenAppScreen = { className, extras ->
            status = runCatching {
                context.startActivity(
                    android.content.Intent()
                        .setClassName(context.packageName, className)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        .apply { extras.forEach { (key, value) -> putExtra(key, value) } },
                )
                null
            }.getOrElse { "droidtop couldn't open that screen: ${it.message}" }
        },
        hasWindowsRoute = hasWindowsRoute,
        // The registered settings screen :app builds (WineOptionsCatalog),
        // by id and deep-linked to this game, in a sheet over the menu.
        onOpenWineSettings = {
            val screen = dev.droidtop.library.settings.SettingsScreenRegistry.get(
                dev.droidtop.library.WineSettingsScreen.ID,
                dev.droidtop.library.WineSettingsScreen.argument(entry.id, gameName),
            )
            if (screen != null) wineScreen = screen else status = "Wine settings aren't available in this build"
        },
        wineSettings = wineSettings,
        onImportLutris = { importingLutris = true },
        onClearWineSettings = {
            scope.launch {
                withContext(Dispatchers.IO) { WineGameSettingsPrefs.set(context, entry.id, null) }
                status = "This game runs the program droidtop detects again."
                reloadToken++
            }
        },
    )

    var pageAbout: List<PcMenuEntry> = emptyList()
    val isReady = runner?.option?.state == RunnerState.READY
    val setupAction = runner?.option?.action
    val downloads by StoreDownloads.active.collectAsState()
    val playState = if (loaded) {
        playStateOf(runner, entry, entry.downloadKey()?.let { downloads[it] }, runners.noRunnerLine)
    } else {
        PcPlayStateLoading
    }

    // Flattened once per recomposition into what this Dialog actually
    // draws and what Up/Down/A navigate: a section header (never
    // selectable), an info line (never selectable, e.g. a status message
    // or the compatibility summary), or a row (selectable, the same
    // PcActionRow the sections produce). The rows sit under the three
    // headers of docs/SPEC.md 13, "Gaming mode" -- Play, About, Fix and
    // advanced -- so one list, one focus index, the same shape
    // GamelistOptionsMenu's own Select-button menu already uses, still
    // moves over the whole menu, while the headers say what each part
    // is for.
    // Whether this game's "Get it on" rows were hidden (docs/SPEC.md 7m);
    // one small preferences read, on entry, like the other remembered facts.
    var storeLinksHidden by remember(entry) { mutableStateOf(dev.droidtop.library.StoreLinkPrefs.hidden(context, gameIds)) }
    val playEntries = buildList {
        // Play: what runs the game, and getting it running.
        add(PcMenuEntry.Header("Play"))
        // Runs with -- WHICH runner, and how to change it.
        if (!entry.missing && (!loaded || runners.options.isNotEmpty())) {
            add(
                PcMenuEntry.Row(
                    PcActionRow(
                        title = "Runs with",
                        detail = when {
                            !loaded -> "Working out what can run this…"
                            runner == null -> "Not chosen — ${runners.options.size} to choose from"
                            else -> "${runner.label} - ${runner.reason}"
                        },
                        onSelect = if (loaded && runners.options.isNotEmpty()) ({ picking = true }) else null,
                    ),
                ),
            )
        }
        // Play, or the one action that makes Play possible -- the
        // gamelist's own A makes this exact decision on its own
        // (docs/SPEC.md 7i, PcRunnerOptions.resolveAndPlay), and the
        // library's hero and the game page draw the same [PcPlayState],
        // so this row is a second way to reach the very same thing.
        if (entry.missing) {
            add(PcMenuEntry.Row(PcActionRow("The folder is not there", missingFolderLine(entry), null)))
        } else {
            add(
                PcMenuEntry.Row(
                    PcActionRow(
                        title = playState.verb,
                        detail = playState.detail,
                        onSelect = if (loaded && playState.pressable) {
                            {
                                val store = playState.store
                                when (store) {
                                    // Install and Update stop on the free-space
                                    // offer first: the size and the room the
                                    // chosen volume has are named before the
                                    // store's screen opens (Droidtop/tracker#227).
                                    StoreStage.INSTALL, StoreStage.UPDATE ->
                                        storeOffer = StoreInstallOffer(entry, checkNotNull(store))
                                    // Downloading, Paused: the download is already
                                    // in flight; the store's queue is the place for it.
                                    null -> Unit
                                    else -> status = openStoreScreen(context, entry)
                                }
                                if (store == null) {
                                    if (isReady) {
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
                ),
            )
        }
        if (playState.store == StoreStage.UPDATE && isReady) {
            add(PcMenuEntry.Row(PcActionRow("Play without updating", "Starts the installed build as it is", { onClose(); onLaunch() })))
        }
        actions.play.forEach { add(PcMenuEntry.Row(it)) }
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
        val openLink = { url: String ->
            status = runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                null
            }.getOrElse { "Nothing on this device opens $url" }
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
        val aboutEntries = buildList {
            if (ownedOn.isNotEmpty()) add(PcMenuEntry.Info(ownedOn))
            plainLinks.forEach { link -> add(PcMenuEntry.Row(PcActionRow(link.label, link.url) { openLink(link.url) })) }
            if (pointers.isNotEmpty()) {
                pointers.forEach { link -> add(PcMenuEntry.Row(PcActionRow(link.label, link.url) { openLink(link.url) })) }
                add(
                    PcMenuEntry.Row(
                        PcActionRow("Hide these for this game", "") {
                            dev.droidtop.library.StoreLinkPrefs.hide(context, gameIds)
                            storeLinksHidden = true
                        },
                    ),
                )
            }
            actions.about.forEach { add(PcMenuEntry.Row(it)) }
            compat?.let {
                add(PcMenuEntry.Info(it.summary() + "\nOther people's results on other hardware."))
            }
            if (offersProtonDb) {
                val state = protonDb
                add(
                    PcMenuEntry.Row(
                        PcActionRow(
                            title = if (state is ProtonDbState.Found) state.summary.line() else "ProtonDB",
                            detail = state.detail(),
                            onSelect = if (state !is ProtonDbState.Looking) {
                                {
                                    when (state) {
                                        is ProtonDbState.Found -> status = runCatching {
                                            context.startActivity(
                                                android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                    android.net.Uri.parse(ProtonDbClient.pageUrl(state.appId)),
                                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                            null
                                        }.getOrElse { "There is no browser on this device to open ProtonDB in." }
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
                    ),
                )
            }
        }
        pageAbout = aboutEntries
    }

    // Fix and advanced: the repairs and the internals of how this copy
    // runs -- offered only when there is anything of the kind, because a
    // row opening an empty page is a category claiming to exist.
    val advancedEntries = actions.advanced.map { PcMenuEntry.Row(it) }
    val entries = buildList {
        when (page) {
            PcMenuPage.Root -> {
                addAll(playEntries)
                status?.let { add(PcMenuEntry.Info(it)) }
                if (pageAbout.isNotEmpty()) {
                    add(
                        PcMenuEntry.Row(
                            PcActionRow(
                                "Game info and links",
                                "Where it is owned, store links, compatibility, scraping and collections",
                            ) {
                                page = PcMenuPage.About
                                focusIndex = 0
                            },
                        ),
                    )
                }
                if (advancedEntries.isNotEmpty()) {
                    add(
                        PcMenuEntry.Row(
                            PcActionRow("Fix and advanced", "Replacements, merging, the engine, other versions and the runner's settings") {
                                page = PcMenuPage.Advanced
                                focusIndex = 0
                            },
                        ),
                    )
                }
                add(PcMenuEntry.Row(PcActionRow(dev.droidtop.library.integrations.GetGamesEntry.LABEL, "More games from your download sources") { gettingGames = true }))
                add(PcMenuEntry.Row(PcActionRow("Close", "", onClose)))
            }
            PcMenuPage.About -> {
                add(PcMenuEntry.Header("Game info and links"))
                status?.let { add(PcMenuEntry.Info(it)) }
                addAll(pageAbout)
                add(PcMenuEntry.Row(PcActionRow("Back", "") { page = PcMenuPage.Root; focusIndex = 0 }))
            }
            PcMenuPage.Advanced -> {
                add(PcMenuEntry.Header("Fix and advanced"))
                status?.let { add(PcMenuEntry.Info(it)) }
                addAll(advancedEntries)
                add(PcMenuEntry.Row(PcActionRow("Back", "") { page = PcMenuPage.Root; focusIndex = 0 }))
            }
        }
    }

    val rowEntries = entries.filterIsInstance<PcMenuEntry.Row>()
    // The row list changes under the cursor (a page opens, a row goes
    // away); it never points past the end it is drawn against.
    LaunchedEffect(rowEntries.size, page) {
        focusIndex = focusIndex.coerceIn(0, (rowEntries.size - 1).coerceAtLeast(0))
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
    Dialog(onDismissRequest = goBack) {
        HideSystemBarsInThisDialog()
        MenuPanel(
            modifier = Modifier.width(dev.droidtop.shell.gamepad.LocalShellWindow.current.panelWidth(560.dp)),
            focusLabel = "Game options",
            onPad = { press ->
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> focusIndex = menuMove(focusIndex, rowEntries.size, press)
                    GamepadAction.A -> rowEntries.getOrNull(focusIndex)?.row?.onSelect?.invoke()
                    // B steps out of a page before it closes the menu, like
                    // the system back key (the dialog's own dismiss); the
                    // release of the same press can no longer step out a
                    // second time, it belongs to this press (SPEC 6e).
                    GamepadAction.B -> goBack()
                    GamepadAction.SELECT -> onClose()
                    else -> Unit
                }
                true
            },
        ) {
            Text(
                gameName,
                style = MaterialTheme.typography.titleLarge,
                color = MenuTokens.OnSurface,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            Text(entry.identityLine(available), color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
            var rowCounter = 0
            entries.forEach { menuEntry ->
                when (menuEntry) {
                    is PcMenuEntry.Header -> dev.droidtop.shell.gamepad.MenuSectionLabel(menuEntry.title)
                    is PcMenuEntry.Info -> Text(menuEntry.text, color = MenuTokens.Value, style = MaterialTheme.typography.bodySmall)
                    is PcMenuEntry.Row -> {
                        val index = rowCounter++
                        dev.droidtop.shell.gamepad.MenuRow(
                            title = menuEntry.row.title,
                            subtitle = menuEntry.row.detail.ifBlank { null },
                            selected = index == focusIndex,
                            onClick = {
                                focusIndex = index
                                menuEntry.row.onSelect?.invoke()
                            },
                        )
                    }
                }
            }
            dev.droidtop.shell.gamepad.MenuHint(if (page == PcMenuPage.Root) "Up/Down moves, A activates, B closes" else "Up/Down moves, A activates, B goes back")
        }
    }
    // The free-space offer before a store install or update: its own
    // window over the menu, the one place the volume is chosen
    // (Droidtop/tracker#227).
    StoreInstallOfferSheet(
        offer = storeOffer,
        onProceed = { o ->
            storeOffer = null
            status = openStoreScreen(context, o.entry)
        },
        onDismiss = { storeOffer = null },
    )
}

/** A minimal full-bleed [Dialog] host for a screen written as a plain fillMaxSize() composable (see the call sites above). */
@Composable
private fun FullScreenOverlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        content()
    }
}

/** Which list of [PcGameMenu] is showing: the short top page, or one of the two it opens. */
private enum class PcMenuPage { Root, About, Advanced }

/** One entry in [PcGameMenu]'s flattened list: a section header, an info line, or a selectable row. */
private sealed interface PcMenuEntry {
    data class Header(val title: String) : PcMenuEntry
    data class Info(val text: String) : PcMenuEntry
    data class Row(val row: PcActionRow) : PcMenuEntry
}
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
        append(String.format("%.1f GB", size / 1_000_000_000.0))
        append(if (pcInfo?.installed == true) " installed" else " to download")
    } else if (pcInfo?.installed == false) {
        append(" · not installed")
    }
    // The same words as the card's line (docs/SPEC.md 7g).
    update?.let { append(" · ").append(GameUpdates.line(it)) }
}

/** What the thread dialog says, on the menu and on the game page. */
internal const val F95_THREAD_HELP = "Paste the game's thread link, or its number. droidtop checks a public index " +
    "for the thread's newest version; no F95zone account is needed. Clear it and save to unlink."

/**
 * A person's text for the F95zone thread (a link, its number, or blank to
 * unlink) applied to the game whose folders are [gameIds], with each step
 * said through [say]. The ONE linking path: the menu's thread row and the
 * game page's thread row both end here.
 */
internal suspend fun linkF95ThreadFromText(
    library: Library,
    gameIds: Collection<String>,
    text: String,
    say: (String?) -> Unit,
) {
    val thread = F95Thread.parse(text)
    if (text.isNotBlank() && thread == null) {
        say("That is not an F95zone thread link: it should look like f95zone.to/threads/<name>.<number>/")
        return
    }
    say(if (thread == null) "Unlinking..." else "Checking thread $thread...")
    val failure = library.linkF95Thread(gameIds, thread)
    say(
        when {
            failure != null -> "Linked thread $thread, but checking it failed: $failure"
            thread == null -> "Unlinked. This game is no longer checked for updates."
            else -> null
        },
    )
}

/** "Check now" for [thread], then the one short line for what it found ([checkOutcomeLine]). */
internal suspend fun checkF95ThreadAndSay(
    library: Library,
    gameIds: Collection<String>,
    thread: Long,
    versions: List<String>,
    fallbackLatest: String?,
): String {
    val failure = library.checkF95ThreadNow(thread)
    return checkOutcomeLine(failure, library.gameLinks(gameIds), versions, fallbackLatest)
}

/**
 * What a check found, in one short line: why it failed, "Up to date", or
 * "v1.2 is available" ([GameUpdates.line]). Pure, for the tests.
 */
internal fun checkOutcomeLine(failure: String?, links: GameLinks?, versions: List<String>, fallbackLatest: String?): String {
    if (failure != null) return failure
    val check = links?.check
    if (check?.gone == true) return "Thread is gone: private, moved or deleted"
    GameUpdates.available(links?.latestKnown ?: fallbackLatest, versions)?.let { return GameUpdates.line(it) }
    val newest = check?.version ?: return "The thread gives no version"
    return if (versions.none { it.isNotEmpty() }) "Newest is $newest" else "Up to date"
}

/**
 * What the F95zone thread row says: whether a thread is linked, and what
 * its update source last answered, in the one wording for an update
 * ([GameUpdates.line]).
 */
private fun f95Line(links: GameLinks?, available: String?, versions: List<String>): String {
    val thread = links?.f95Thread
        ?: return "Not linked. Paste the game's F95zone thread link to be told when a new version is out"
    val check = links.check
    val newest = check?.version
    return when {
        check == null -> "Thread $thread - not checked yet"
        check.gone -> "Thread $thread is gone: private, moved or deleted"
        available != null -> "${GameUpdates.line(available)} - thread $thread"
        newest == null -> "Thread $thread gives no version"
        versions.none { it.isNotEmpty() } -> "The thread's newest is $newest; this game's folders name no version to compare"
        else -> "Up to date: $newest is the thread's newest - thread $thread"
    }
}

/**
 * The menu's three sections (docs/SPEC.md 13, "Gaming mode"): what plays
 * the game, what this copy is, and what fixes or reconfigures it. The
 * same rows the old mechanism-named groups ("Game management", "Runs on
 * Windows", "Metadata and media") carried, refiled under the question a
 * player opening the menu is there to answer -- a list titled by its
 * machinery reads as one flat list, not as things to do.
 */
private data class PcMenuSections(
    val play: List<PcActionRow>,
    val about: List<PcActionRow>,
    val advanced: List<PcActionRow>,
)

/** [onSelect] null means the row is shown disabled, with [detail] saying why. */
private data class PcActionRow(val title: String, val detail: String, val onSelect: (() -> Unit)?)

/** What the Engine row needs: the pin's folder key, whether it is set, and the engines it can name. */
private data class EngineChoice(val folder: String?, val pinned: Boolean, val engines: List<Pair<String, GameEngine>>) {
    companion object {
        val NONE = EngineChoice(null, false, emptyList())
    }
}

/**
 * The menu's rows, filed into its three sections ([PcMenuSections],
 * docs/SPEC.md 13, "Gaming mode"). The rows that need state this
 * function has not got are handed in pre-built; everything else is
 * built here from [entry] alone.
 */
@Composable
private fun rememberPcActions(
    titleRows: List<PcActionRow>,
    group: dev.droidtop.library.LibraryGameGroup?,
    currentId: String,
    onOpenOther: (LibraryEntry) -> Unit,
    replacements: Int,
    onReplace: () -> Unit,
    sameGameRow: PcActionRow?,
    updateRows: List<PcActionRow>,
    entry: LibraryEntry,
    runner: ResolvedRunner?,
    media: Int,
    onScrape: () -> Unit,
    onChooseMatch: () -> Unit,
    onViewMedia: () -> Unit,
    onCollections: () -> Unit,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenPage: (() -> Unit)?,
    engineRow: PcActionRow?,
    onEnginehost: (android.content.Intent) -> Unit,
    onOpenAppScreen: (className: String, extras: Map<String, String>) -> Unit,
    hasWindowsRoute: Boolean,
    onOpenWineSettings: () -> Unit,
    wineSettings: WineGameSettings?,
    onImportLutris: () -> Unit,
    onClearWineSettings: () -> Unit,
): PcMenuSections {
    val isEngineGame = entry.kind != LibraryEntryKind.WINE_PROFILE
    val runsOnEnginehost = runner?.option?.strategy == GameLaunchStrategy.ENGINEHOST
    val isStoreGame = entry.pcInfo?.storeId != null || entry.id.substringBefore(':') in STORE_PREFIXES

    return PcMenuSections(
        // Play: getting this game onto the device and running it. The
        // store's own screen is the one step before Play can mean
        // anything for a store game; a folder game keeps the row,
        // disabled, saying droidtop does not manage it.
        play = listOfNotNull(
            onOpenPage?.let { PcActionRow("Game page", "Its artwork, facts and the one Play button", it) },
            // One row, not three: install, verify, update, DLC and
            // delete are one screen on the store's side, and that
            // screen is the store's own (gamenative's AppScreen for
            // this game's source, with its GameManagerDialog /
            // EpicGameManagerDialog / AmazonInstallDialog).
            PcActionRow(
                // Install and Update are the primary row above; this is the
                // place for verify, extras and remove.
                "Manage install",
                if (isStoreGame) {
                    "Install, verify, update or remove it, and pick which extras come with it"
                } else {
                    "This game is a folder on this device; droidtop doesn't manage it"
                },
                if (isStoreGame) {
                    { onOpenAppScreen(PC_STORE_ACTIVITY, mapOf(EXTRA_PC_ENTRY_ID to entry.id)) }
                } else {
                    null
                },
            ),
            // The global download queue is not this game's; it is
            // under "PC setup" (UI pass 2026-09-24, M7; renamed from
            // "Stores and folders" when store accounts moved to
            // "Accounts and sources").
            PcActionRow(
                if (favorite) "Remove from favourites" else "Add to favourites",
                if (favorite) "It is in your Favourites collection" else "Puts it in your Favourites collection",
                onToggleFavorite,
            ),
        ),
        // About: this copy -- its update state and its record in the
        // person's library.
        about = titleRows + updateRows + listOfNotNull(
            PcActionRow("Scrape", "Looks this game up in the PC sources", onScrape),
            PcActionRow("Choose match", "Pick the right game by hand when the scraper guessed wrong", onChooseMatch),
            if (media > 1) PcActionRow("View media", "$media images and videos scraped for this game", onViewMedia) else null,
            PcActionRow("Collections", "Which of your collections this game is in", onCollections),
        ),
        // Fix and advanced: the repairs (a found replacement, a same-game
        // merge, a corrected engine detection, another folder of the same
        // game) and the internals of how this copy runs.
        advanced = listOfNotNull(
            // The game's other folders, when it has any: its parts, and the
            // versions of each. The row that is open says so instead of
            // offering to open itself again.
            group?.let { versionsRows(it, currentId, onOpenOther) },
            listOfNotNull(
                // The fold, from whichever side the user is standing on
                // (docs/SPEC.md 7g). On the game that is not there it is
                // always offered, because that is the screen a person
                // comes to in order to fix it, and it says so when there
                // is nothing to offer yet. On a game that IS here it
                // appears only when something is actually missing that it
                // could be: an action with nothing behind it is not an
                // action.
                when {
                    entry.missing -> PcActionRow(
                        "Find its replacement",
                        if (replacements == 0) {
                            "Nothing found looks like this game yet"
                        } else {
                            "$replacements found ${if (replacements == 1) "game looks" else "games look"} " +
                                "like it; picking one moves this game's history, favourite and collections to it"
                        },
                        if (replacements == 0) null else onReplace,
                    )
                    replacements > 0 -> PcActionRow(
                        "This replaces a missing game",
                        "$replacements missing ${if (replacements == 1) "game looks" else "games look"} like this one; " +
                            "picking one moves its history, favourite and collections here",
                        onReplace,
                    )
                    else -> null
                },
                // Two games here that are one game (docs/SPEC.md 7m):
                // offered only when another game's name is alike enough.
                sameGameRow,
                // Which engine this folder is, and the pin that corrects
                // detection (docs/SPEC.md 7e2b).
                engineRow,
            ),
            // ONE runner's rows, for the runner this game actually uses.
            // Until build 540 every game got both: a Ren'Py game running on
            // enginehost carried a Wine "Prefix and graphics" section it can
            // do nothing with, under a section label that repeated the name
            // of its only row. Rows for a runner the game does not use are
            // worse than nothing, because they read as a setting that
            // applies.
            runnerRows(
                runsOnEnginehost = runsOnEnginehost,
                hasWindowsRoute = hasWindowsRoute,
                isEngineGame = isEngineGame,
                onEnginehost = onEnginehost,
                onOpenPrefix = onOpenWineSettings,
                wineSettings = wineSettings,
                onImportLutris = onImportLutris,
                onClearWineSettings = onClearWineSettings,
            ),
        ).flatten(),
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
): List<PcActionRow>? {
    val rows = mutableListOf<PcActionRow>()
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
): PcActionRow {
    val target = group.entryFor(copy)
    val store = target?.ownership()?.label ?: copy.source ?: copy.path
    val detail = buildString {
        append(if (target?.id == currentId) "Open now" else "Open this one")
        append(" - ").append(if (copy.installed) "installed" else "not installed")
    }
    return PcActionRow(store, detail, if (target == null || target.id == currentId) null else ({ onOpenOther(target) }))
}

private fun row(
    group: dev.droidtop.library.LibraryGameGroup,
    version: dev.droidtop.library.GameVersion,
    currentId: String,
    onOpenOther: (LibraryEntry) -> Unit,
    label: String?,
): PcActionRow {
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
    return PcActionRow(title, detail, if (target == null || target.id == currentId) null else ({ onOpenOther(target) }))
}

/**
 * What the game's Wine settings hold on this device. An x86_64 device runs
 * x86_64 Wine directly, so it has no FEXCore or Box64 choice to name (the
 * rows themselves follow the same rule, WineOptions; the ABI test is the one
 * X86_64GuestLibs.isX86_64Host makes, the device's first supported ABI).
 */
internal fun wineRowDetail(x86_64Host: Boolean): String =
    if (x86_64Host) {
        "Wine build, graphics driver and DXVK for this game, and all its prefix settings"
    } else {
        "Wine build, FEXCore or Box64, graphics driver and DXVK for this game, and all its prefix settings"
    }

/**
 * The rows that depend on HOW this game runs: enginehost's own settings
 * for a game enginehost runs, the Wine prefix for a game that takes the
 * Windows route, and nothing at all for a game whose runner is neither
 * (a native Linux build, or a game with no runner on this device -- the
 * Play row above already says so, and rows of dead settings repeating it
 * are not information).
 *
 * No sub-header of their own under "Fix and advanced": every row names
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
    onClearWineSettings: () -> Unit,
): List<PcActionRow>? = when {
    runsOnEnginehost -> listOfNotNull(
        PcActionRow("Saves", "Opens Enginehost's save settings", { onEnginehost(EngineHost.savesSettingsIntent()) }),
        PcActionRow(
            "Controls",
            "Opens Enginehost's controls for this game",
            { onEnginehost(EngineHost.settingsIntent()) },
        ),
        if (isEngineGame) {
            PcActionRow("Engine settings", "Opens Enginehost's settings", { onEnginehost(EngineHost.settingsIntent()) })
        } else {
            null
        },
    )
    hasWindowsRoute -> listOfNotNull(
        PcActionRow("Wine and graphics", wineRowDetail(android.os.Build.SUPPORTED_ABIS.firstOrNull() == "x86_64"), onOpenPrefix),
        // The game's own program, when an import chose one; selecting
        // it goes back to the program droidtop detects (docs/SPEC.md 7i).
        wineSettings?.executable?.let { exe ->
            PcActionRow(
                "Program: $exe",
                listOfNotNull(
                    wineSettings.arguments.takeIf { it.isNotEmpty() }?.joinToString(" ", prefix = "With "),
                    wineSettings.source?.let { "from $it" },
                    "select to go back to the program droidtop detects",
                ).joinToString(" - "),
                onClearWineSettings,
            )
        },
        PcActionRow(
            "Import a Lutris install script",
            "Reads a Wine script from lutris.net into this game's settings and shows every change first; nothing in it is run",
            onImportLutris,
        ),
        PcActionRow("Saves", "This game's saves live inside the Windows prefix it runs in (Wine and graphics says which)", null),
        PcActionRow("Controls", "This game's controls are its prefix's controller tab, under Wine and graphics > All prefix settings", null),
    )
    else -> null
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

/** The same for a game that might be this one: why, and where its folders are. */
private fun SimilarGames.Candidate.line(): String {
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

private val STORE_PREFIXES = setOf("steam", "gog", "epic", "amazon")

// :app's hosts for the gamenative screens droidtop adopts, by name
// because this module cannot depend on :app. Kept together so the two
// sides are one edit apart if a class ever moves.
internal const val PC_STORE_ACTIVITY = "dev.droidtop.app.PcStoreActivity"
internal const val EXTRA_PC_ENTRY_ID = "dev.droidtop.app.extra.PC_ENTRY_ID"


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

/** Network: the IO dispatcher. Every outcome is a sentence, never a silent blank. */
private suspend fun lookUpProtonDb(entry: LibraryEntry, name: String): ProtonDbState = withContext(Dispatchers.IO) {
    runCatching {
        val appId = ProtonDbClient.steamAppIdFor(entry, name)
            ?: return@runCatching ProtonDbState.Unavailable("No Steam app id is known for $name, and ProtonDB lists only Steam games")
        when (val lookup = ProtonDbClient.summary(appId)) {
            is ScrapeLookup.Found -> ProtonDbState.Found(lookup.value, appId)
            ScrapeLookup.NoMatch -> ProtonDbState.Unavailable("ProtonDB has no reports for this game yet")
            is ScrapeLookup.Refused -> ProtonDbState.Unavailable("ProtonDB refused the request (HTTP ${lookup.httpStatus})")
        }
    }.getOrElse { ProtonDbState.Unavailable("ProtonDB could not be reached: ${it.message ?: it}") }
}

/** The name one folder derives for its game, before any grouping is worked out. */
private fun ownNameOf(entry: LibraryEntry): String =
    entry.groupingPath()?.let { dev.droidtop.library.GameNaming.derive(it).name.ifEmpty { entry.title } } ?: entry.title

/** The folder's own name, as it is on disk: what a title is read from (docs/SPEC.md 7n). Names only. */
private fun folderNameOf(entry: LibraryEntry): String =
    entry.groupingPath()?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: entry.title
