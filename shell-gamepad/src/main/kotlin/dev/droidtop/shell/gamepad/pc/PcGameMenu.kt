package dev.droidtop.shell.gamepad.pc

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.library.EngineHost
import dev.droidtop.library.EngineOverridePrefs
import dev.droidtop.library.EnginesDatabase
import dev.droidtop.library.F95Thread
import dev.droidtop.library.GameEngine
import dev.droidtop.library.GameLinks
import dev.droidtop.library.GameNaming
import dev.droidtop.library.GameUpdates
import dev.droidtop.library.GameLaunchStrategy
import dev.droidtop.library.LaunchStrategyOverridePrefs
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.LibraryGrouping
import dev.droidtop.library.MissingGames
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
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The PC-only actions on one PC or engine game -- docs/SPEC.md §7i's
 * "Game options", an ES-DE-style in-context menu over the themed
 * gamelist (redecided 2026-09-26), not a screen of its own.
 *
 * What used to live here as a full-screen "detail" -- a hero-art header,
 * the scraped description/developer/rating/genre "About this game" --
 * is gone: the active theme's own gamelist already shows all of that for
 * the focused game (md_image/md_description/md_developer/md_rating and
 * the rest, bound from the exact same [LibraryEntry] fields), exactly as
 * it does for a console ROM. This menu is only what the theme cannot
 * show: the resolved runner and its picker, Wine/container settings,
 * ProtonDB, the Lutris import, the F95 link and update state, merge and
 * versions/segments, favourite/collections/scrape.
 *
 * A itself no longer opens this menu (docs/SPEC.md 7i): on the gamelist,
 * A launches when the resolved runner is ready and runs the one setup
 * action when it is not ([dev.droidtop.library.PcRunnerOptions.resolveAndPlay]),
 * exactly like a console ROM's A. This menu opens on Y ("Game options"),
 * the same in-context-menu convention [dev.droidtop.shell.gamepad
 * .GamelistOptionsMenu] already uses for the whole gamelist's own
 * actions (sort/scrape/"Stores and folders").
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
    var editingThread by remember(entry) { mutableStateOf(false) }
    var pickingEngine by remember(entry) { mutableStateOf(false) }
    var engineChoice by remember(entry) { mutableStateOf(EngineChoice.NONE) }
    var importingLutris by remember(entry) { mutableStateOf(false) }
    var wineSettings by remember(entry) { mutableStateOf<WineGameSettings?>(null) }
    var protonDb by remember(entry) { mutableStateOf<ProtonDbState>(ProtonDbState.NotAsked) }

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
    val worked by produceState(DetailNames.NONE, entry, siblings) {
        value = withContext(Dispatchers.Default) {
            val groups = LibraryGrouping.group(siblings)
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
    val isFolder = entry.id.startsWith("/")
    val gameIds = grouping?.entriesByPath?.keys ?: setOf(entry.id)
    var linksToken by remember(entry) { mutableStateOf(0) }
    val links by produceState<GameLinks?>(null, gameIds, linksToken) {
        value = if (isFolder) library.gameLinks(gameIds) else null
    }
    val versions = grouping?.game?.allVersions?.map { it.version }
        ?: if (isFolder) listOf(GameNaming.derive(entry.id).version) else emptyList()
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
    if (editingThread) {
        TextEditDialog(
            title = "F95zone thread",
            subtitle = "Paste the game's thread link, or its number. droidtop asks F95Checker's public index " +
                "for the thread's newest version; no F95zone account is needed. Clear it and save to unlink.",
            initial = links?.f95Thread?.let { F95Thread.url(it) }.orEmpty(),
            onCommit = { text ->
                editingThread = false
                val thread = F95Thread.parse(text)
                if (text.isNotBlank() && thread == null) {
                    status = "That is not an F95zone thread link: it should look like f95zone.to/threads/<name>.<number>/"
                } else {
                    scope.launch {
                        status = if (thread == null) "Unlinking..." else "Asking about thread $thread..."
                        val failure = library.linkF95Thread(gameIds, thread)
                        status = when {
                            failure != null -> "Linked thread $thread, but asking about it failed: $failure"
                            thread == null -> "Unlinked. This game is no longer checked for updates."
                            else -> null
                        }
                        linksToken++
                    }
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
    val actions = rememberPcActions(
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
                        links?.check?.let { "Last asked " + android.text.format.DateUtils.getRelativeTimeSpanString(it.checkedAtEpochMs) }
                            ?: "Not asked yet",
                        {
                            scope.launch {
                                status = "Asking about thread $thread..."
                                status = library.checkF95ThreadNow(thread)
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
        // Both of these open UI :runtime-windows already compiles from the
        // vendored gamenative tree, hosted by an :app Activity (build-plan
        // steps 5 and 7). Started by explicit class name because this
        // module cannot depend on :app -- the same route every other
        // cross-module screen here takes.
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

    val runner = resolved
    val isReady = runner?.option?.state == RunnerState.READY
    val setupAction = runner?.option?.action

    // Flattened once per recomposition into what this Dialog actually
    // draws and what Up/Down/A navigate: a header entry (never
    // selectable), a group title (never selectable), an info line (never
    // selectable, e.g. a status message or the compatibility summary),
    // or a row (selectable, the same PcActionRow every group already
    // produces). One list, one focus index, the same shape
    // GamelistOptionsMenu's own Select-button menu already uses.
    val entries = buildList {
        // 1. Runs with -- WHICH runner, and how to change it.
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
        // 2. Play, or the one action that makes Play possible -- the
        // gamelist's own A now makes this exact decision on its own
        // (docs/SPEC.md 7i, redecided 2026-09-26,
        // PcRunnerOptions.resolveAndPlay), so this row is a second way to
        // reach the very same thing from the menu, not a different one.
        if (entry.missing) {
            add(PcMenuEntry.Row(PcActionRow("The folder is not there", missingFolderLine(entry), null)))
        } else {
            add(
                PcMenuEntry.Row(
                    PcActionRow(
                        title = when {
                            !loaded -> "…"
                            isReady -> "Play"
                            setupAction != null -> runner?.option?.reason ?: "Set up"
                            else -> "Can't play yet"
                        },
                        detail = when {
                            !loaded -> ""
                            isReady -> runner?.option?.caveat ?: "Starts now on ${runner?.label}"
                            setupAction != null -> "One step, then this becomes Play"
                            else -> runner?.option?.reason ?: "No runner on this device offers this game"
                        },
                        onSelect = if (loaded && (isReady || setupAction != null)) {
                            {
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
                        } else {
                            null
                        },
                    ),
                ),
            )
        }
        status?.let { add(PcMenuEntry.Info(it)) }

        actions.forEach { group ->
            add(PcMenuEntry.Header(group.title))
            group.rows.forEach { add(PcMenuEntry.Row(it)) }
        }

        // Links the scrape brought back (an official site, a store page):
        // PC-only actionable rows, unlike description/developer/rating/
        // genre, which the theme's own md_* elements already show while
        // browsing (docs/SPEC.md 7i, redecided 2026-09-26) and are not
        // repeated here.
        if (entry.links.isNotEmpty()) {
            add(PcMenuEntry.Header("Links"))
            entry.links.forEach { link ->
                add(
                    PcMenuEntry.Row(
                        PcActionRow(link.label, link.url) {
                            status = runCatching {
                                context.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link.url))
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                                null
                            }.getOrElse { "Nothing on this device opens ${link.url}" }
                        },
                    ),
                )
            }
        }

        // Compatibility: evidence, never a verdict and never a gate
        // (docs/SPEC.md 7i). gamenative's own reports when the entry
        // carries them, and ProtonDB for a game with a Windows route,
        // looked up only when asked.
        val compat = entry.pcInfo?.compatibility
        val offersProtonDb = !entry.missing && (hasWindowsRoute || entry.pcInfo?.storeId != null)
        if (compat != null || offersProtonDb) {
            add(PcMenuEntry.Header("Compatibility"))
            compat?.let {
                add(PcMenuEntry.Info(it.summary() + "\nOther people's results on other hardware."))
            }
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
        add(PcMenuEntry.Row(PcActionRow("Close", "", onClose)))
    }

    val rowEntries = entries.filterIsInstance<PcMenuEntry.Row>()
    var focusIndex by remember(entry) { mutableStateOf(0) }
    Dialog(onDismissRequest = onClose) {
        MenuPanel(
            modifier = Modifier.width(dev.droidtop.shell.gamepad.LocalShellWindow.current.panelWidth(560.dp)),
            focusLabel = "Game options",
            onKey = { event ->
                if (event.type != KeyEventType.KeyUp) {
                    false
                } else {
                    when (GamepadKeyMap.actionFor(event.key)) {
                        GamepadAction.UP -> {
                            if (rowEntries.isNotEmpty()) focusIndex = (focusIndex - 1 + rowEntries.size) % rowEntries.size
                            dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds.play("scroll")
                            true
                        }
                        GamepadAction.DOWN -> {
                            if (rowEntries.isNotEmpty()) focusIndex = (focusIndex + 1) % rowEntries.size
                            dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds.play("scroll")
                            true
                        }
                        GamepadAction.A -> {
                            rowEntries.getOrNull(focusIndex)?.row?.onSelect?.invoke()
                            true
                        }
                        GamepadAction.B, GamepadAction.BACK, GamepadAction.SELECT -> {
                            onClose()
                            true
                        }
                        else -> false
                    }
                }
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
            dev.droidtop.shell.gamepad.MenuHint("Up/Down moves, A activates, B closes")
        }
    }
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

/** One entry in [PcGameMenu]'s flattened list: a group header, an info line, or a selectable row. */
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
private fun LibraryEntry.identityLine(update: String?): String = if (missing) "broken - missing" else buildString {
    append(sourceLabel())
    engineLabel()?.let { append(" - ").append(it) }
    val size = pcInfo?.sizeBytes ?: 0L
    if (size > 0) {
        append(" - ")
        append(String.format("%.1f GB", size / 1_000_000_000.0))
        append(if (pcInfo?.installed == true) " installed" else " to download")
    } else if (pcInfo?.installed == false) {
        append(" - not installed")
    }
    // The same words as the card's line (docs/SPEC.md 7g).
    update?.let { append(" - ").append(GameUpdates.line(it)) }
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
        check == null -> "Thread $thread - not asked yet"
        check.gone -> "Thread $thread is gone: private, moved or deleted"
        available != null -> "${GameUpdates.line(available)} - thread $thread"
        newest == null -> "Thread $thread gives no version"
        versions.none { it.isNotEmpty() } -> "The thread's newest is $newest; this game's folders name no version to compare"
        else -> "Up to date: $newest is the thread's newest - thread $thread"
    }
}

/** A named group of actions, in the order §7i lists them. */
private data class PcActionGroup(val title: String, val rows: List<PcActionRow>)

/** [onSelect] null means the row is shown disabled, with [detail] saying why. */
private data class PcActionRow(val title: String, val detail: String, val onSelect: (() -> Unit)?)

/** What the Engine row needs: the pin's folder key, whether it is set, and the engines it can name. */
private data class EngineChoice(val folder: String?, val pinned: Boolean, val engines: List<Pair<String, GameEngine>>) {
    companion object {
        val NONE = EngineChoice(null, false, emptyList())
    }
}

@Composable
private fun rememberPcActions(
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
    engineRow: PcActionRow?,
    onEnginehost: (android.content.Intent) -> Unit,
    onOpenAppScreen: (className: String, extras: Map<String, String>) -> Unit,
    hasWindowsRoute: Boolean,
    wineSettings: WineGameSettings?,
    onImportLutris: () -> Unit,
    onClearWineSettings: () -> Unit,
): List<PcActionGroup> {
    val isEngineGame = entry.kind != LibraryEntryKind.WINE_PROFILE
    val runsOnEnginehost = runner?.option?.strategy == GameLaunchStrategy.ENGINEHOST
    val isStoreGame = entry.pcInfo?.storeId != null || entry.id.substringBefore(':') in STORE_PREFIXES

    return listOfNotNull(
        // The game's other folders, when it has any: its parts, and the
        // versions of each. The row that is open says so instead of
        // offering to open itself again.
        group?.let { versionsGroup(it, currentId, onOpenOther) },
        PcActionGroup(
            "Game management",
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
                            "Nothing detected looks like this game yet"
                        } else {
                            "$replacements detected ${if (replacements == 1) "game looks" else "games look"} " +
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
            ) + updateRows + listOfNotNull(
                // One row, not three: install, verify, update, DLC and
                // delete are one screen on the store's side, and that
                // screen is the store's own (gamenative's AppScreen for
                // this game's source, with its GameManagerDialog /
                // EpicGameManagerDialog / AmazonInstallDialog).
                PcActionRow(
                    if (entry.pcInfo?.installed == false) "Install" else "Manage install",
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
                // under Stores and folders with the stores it serves (UI
                // pass 2026-09-24, M7).
            ),
        ),
        // ONE runner section, for the runner this game actually uses.
        // Until build 540 every game got both: a Ren'Py game running on
        // enginehost carried a Wine "Prefix and graphics" section it can
        // do nothing with, under a section label that repeated the name
        // of its only row. A section titled like its row says one thing
        // twice; a section for a runner the game does not use is worse
        // than nothing, because it reads as a setting that applies.
        runnerGroup(
            runsOnEnginehost = runsOnEnginehost,
            hasWindowsRoute = hasWindowsRoute,
            isEngineGame = isEngineGame,
            onEnginehost = onEnginehost,
            onOpenPrefix = {
                onOpenAppScreen(
                    PC_CONTAINER_CONFIG_ACTIVITY,
                    mapOf(EXTRA_PC_ENTRY_ID to entry.id, EXTRA_PC_TITLE to entry.title),
                )
            },
            wineSettings = wineSettings,
            onImportLutris = onImportLutris,
            onClearWineSettings = onClearWineSettings,
        ),
        PcActionGroup(
            "Metadata and media",
            listOfNotNull(
                PcActionRow("Scrape", "Looks this game up in the PC sources", onScrape),
                PcActionRow("Choose match", "Pick the right game by hand when the scraper guessed wrong", onChooseMatch),
                if (media > 1) PcActionRow("View media", "$media images and videos scraped for this game", onViewMedia) else null,
                PcActionRow("Collections", "Which of your collections this game is in", onCollections),
                PcActionRow(
                    if (favorite) "Remove from favourites" else "Add to favourites",
                    if (favorite) "It is in your Favourites collection" else "Puts it in your Favourites collection",
                    onToggleFavorite,
                ),
            ),
        ),
    )
}

/**
 * Every folder this one game is: a row per segment and a row per version,
 * each saying what it carries, with the one that is open marked.
 *
 * Minimum by design (docs/SPEC.md 7m): the model's whole job is that a
 * game is one entry, so what the detail needs is a way to reach the other
 * folders of it, which is a list of rows -- the same rows every other
 * action on this screen is.
 */
private fun versionsGroup(
    group: dev.droidtop.library.LibraryGameGroup,
    currentId: String,
    onOpenOther: (LibraryEntry) -> Unit,
): PcActionGroup? {
    val rows = mutableListOf<PcActionRow>()
    val game = group.game
    for (segment in game.segments) {
        for (version in segment.versions) {
            rows += row(group, version, currentId, onOpenOther, label = segment.label)
        }
    }
    for (version in game.versions) {
        rows += row(group, version, currentId, onOpenOther, label = null)
    }
    if (rows.size < 2) return null
    return PcActionGroup(if (game.segments.isEmpty()) "Versions" else "Parts and versions", rows)
}

/**
 * Which folder of [group] the open [entry] is, in the same words a
 * "Parts and versions" row uses for it, or null for a game that is one
 * folder with nothing in its name to say.
 */
private fun copyLabel(group: dev.droidtop.library.LibraryGameGroup, entry: LibraryEntry): String? {
    val game = group.game
    for (segment in game.segments) {
        for (version in segment.versions) {
            version.copies.firstOrNull { it.path == entry.id }?.let { return partLabel(segment.label, version, it) }
        }
    }
    for (version in game.versions) {
        version.copies.firstOrNull { it.path == entry.id }?.let { return partLabel(null, version, it) }
    }
    return null
}

/** The one wording for "this part, this version, these mods, this language". */
private fun partLabel(segment: String?, version: dev.droidtop.library.GameVersion, copy: dev.droidtop.library.GameCopy): String? =
    listOfNotNull(
        segment,
        version.version.takeIf { it.isNotEmpty() }?.let { "v$it" },
        copy.mods.takeIf { it.isNotEmpty() }?.joinToString(" "),
        copy.language,
    ).joinToString(" - ").ifEmpty { null }

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
 * The one section that depends on HOW this game runs: enginehost's own
 * settings for a game enginehost runs, the Wine prefix for a game that
 * takes the Windows route, and nothing at all for a game whose runner is
 * neither (a native Linux build, or a game with no runner on this device
 * -- the primary button above already says so, and a section of dead rows
 * repeating it is not information).
 *
 * The section is named for the runner, never for its own first row.
 */
private fun runnerGroup(
    runsOnEnginehost: Boolean,
    hasWindowsRoute: Boolean,
    isEngineGame: Boolean,
    onEnginehost: (android.content.Intent) -> Unit,
    onOpenPrefix: () -> Unit,
    wineSettings: WineGameSettings?,
    onImportLutris: () -> Unit,
    onClearWineSettings: () -> Unit,
): PcActionGroup? = when {
    runsOnEnginehost -> PcActionGroup(
        "Runs on Enginehost",
        listOfNotNull(
            PcActionRow("Saves", "Opens Enginehost's own save settings", { onEnginehost(EngineHost.savesSettingsIntent()) }),
            PcActionRow(
                "Controls",
                "Opens Enginehost's own per-engine controls for this game",
                { onEnginehost(EngineHost.settingsIntent()) },
            ),
            if (isEngineGame) {
                PcActionRow("Engine settings", "Opens Enginehost's own settings", { onEnginehost(EngineHost.settingsIntent()) })
            } else {
                null
            },
        ),
    )
    hasWindowsRoute -> PcActionGroup(
        "Runs on Windows",
        listOfNotNull(
            PcActionRow(
                "Prefix and graphics",
                "The Windows prefix this game runs in: graphics driver, DXVK, Box64 and FEX, components, drives and the rest",
                onOpenPrefix,
            ),
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
            PcActionRow("Saves", "This game's saves live inside its prefix, under Prefix and graphics", null),
            PcActionRow("Controls", "This game's controls are its prefix's controller tab, under Prefix and graphics", null),
        ),
    )
    else -> null
}

/**
 * What the disabled button says under itself: where this game was. The
 * whole path, not its name -- the name is already the title above it,
 * and the path is the thing the person has to go and look at.
 */
private fun missingFolderLine(entry: LibraryEntry): String =
    if (entry.id.startsWith("/")) {
        "${entry.id} is not there any more. Its history, favourite and collections are kept."
    } else {
        "Nothing droidtop scanned still has this game. Its history, favourite and collections are kept."
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
    val where = entry.id.takeIf { it.startsWith("/") }
    val why = if (certain) "The same name" else "A similar name"
    return listOfNotNull(why, where).joinToString(" - ")
}

/** The same for a game that might be this one: why, and where its folders are. */
private fun SimilarGames.Candidate.line(): String {
    val folders = group.entriesByPath.keys.sorted()
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
private const val PC_STORE_ACTIVITY = "dev.droidtop.app.PcStoreActivity"
private const val PC_CONTAINER_CONFIG_ACTIVITY = "dev.droidtop.app.PcContainerConfigActivity"
private const val EXTRA_PC_ENTRY_ID = "dev.droidtop.app.extra.PC_ENTRY_ID"
private const val EXTRA_PC_TITLE = "dev.droidtop.app.extra.PC_TITLE"


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
    if (entry.id.startsWith("/")) dev.droidtop.library.GameNaming.derive(entry.id).name.ifEmpty { entry.title } else entry.title

