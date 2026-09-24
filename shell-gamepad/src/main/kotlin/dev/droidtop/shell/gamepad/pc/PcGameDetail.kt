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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.droidtop.library.EngineHost
import dev.droidtop.library.EngineOverridePrefs
import dev.droidtop.library.EnginesDatabase
import dev.droidtop.library.GameEngine
import dev.droidtop.library.GameLaunchStrategy
import dev.droidtop.library.LaunchStrategyOverridePrefs
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.PcRunnerOptions
import dev.droidtop.library.PcRunners
import dev.droidtop.library.ResolvedRunner
import dev.droidtop.library.RunnerState
import dev.droidtop.library.displayName
import dev.droidtop.library.scraper.PcScraper
import dev.droidtop.shell.gamepad.CollectionMembershipEditor
import dev.droidtop.shell.gamepad.ManualMatchPicker
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.selectionFrame
import dev.droidtop.shell.gamepad.MediaViewer
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One PC or engine game, and everything droidtop can do with it —
 * docs/SPEC.md §7i's "Game detail".
 *
 * The two rules that shape it:
 *
 * - **The runner is stated, never hidden.** A "Runs with" row names the
 *   resolved runner and why it won ("default for Ren'Py", "your choice"),
 *   and opens the picker over the full availability model.
 * - **The primary button never lies.** It is Play when the resolved
 *   runner is ready, and it *is the setup action* when it is not
 *   ("Set up Windows games", "Install the Ren'Py plugin"). A button known
 *   in advance to produce nothing is worse than an honest one.
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
internal fun PcGameDetail(
    entry: LibraryEntry,
    library: Library,
    onLaunch: () -> Unit,
    onClose: () -> Unit,
    // Every game entry the shell has, so this screen can offer the OTHER
    // folders of the same game -- its versions and its segments (docs/
    // SPEC.md 7m). Empty means "nothing to group with", which is what a
    // caller that has no list passes.
    siblings: List<LibraryEntry> = emptyList(),
    onOpenOther: (LibraryEntry) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
    var pickingEngine by remember(entry) { mutableStateOf(false) }
    var engineChoice by remember(entry) { mutableStateOf(EngineChoice.NONE) }

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
    // this game could be, or who could be it (docs/SPEC.md 7g). Both are
    // names only, no filesystem, but they derive a name for and compare
    // against every sibling -- the whole Games list -- so they are worked
    // out on the Default dispatcher, never in composition (docs/SPEC.md
    // 7g, "no per-item work where a list is drawn"). Until they are, the
    // header names the game from this folder alone and no row offers a
    // version or a replacement.
    val worked by produceState(DetailNames.NONE, entry, siblings) {
        value = withContext(Dispatchers.Default) {
            DetailNames(
                forId = entry.id,
                grouping = dev.droidtop.library.LibraryGrouping.groupOf(entry, siblings),
                replacements = replacementCandidatesFor(entry, siblings),
            )
        }
    }
    // A state kept across a move to another folder of the game answers
    // for the folder it was worked out for, not this one.
    val names = worked.takeIf { it.forId == entry.id } ?: DetailNames.NONE
    val grouping = names.grouping
    val group = grouping?.takeIf { it.hasChoices }

    // Media is a folder listing (EsDeArtwork), which is disk work: IO
    // dispatcher, and "no media" until it answers.
    val media by produceState(emptyList<Pair<String, String>>(), entry) {
        value = emptyList()
        value = withContext(Dispatchers.IO) {
            val folder = PcRunnerOptions.gameFolderFor(entry)
            val roots = dev.droidtop.library.GamesRoots.current(context)
            val root = folder?.let { f -> roots.firstOrNull { f.absolutePath.startsWith(it.absolutePath) } }
            if (root != null && folder != null) {
                dev.droidtop.library.EsDeArtwork.allMedia(root, "pc", folder.name)
            } else {
                emptyList()
            }
        }
    }

    if (picking) {
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
        return
    }
    val engineFolder = engineChoice.folder
    if (pickingEngine && engineFolder != null) {
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
        return
    }
    if (viewingMedia) {
        MediaViewer(title = entry.title, media = media, onClose = { viewingMedia = false })
        return
    }
    if (editingCollections) {
        CollectionMembershipEditor(entry = entry, library = library, onDismiss = { editingCollections = false })
        return
    }
    if (pickingMatch) {
        ManualMatchPicker(entry = entry, onApplied = { status = it }, onDismiss = { pickingMatch = false })
    }
    if (pickingReplacement) {
        MissingReplacementPicker(
            entry = entry,
            candidates = names.replacements,
            library = library,
            onFolded = { message ->
                status = message
                // The entry that is gone is gone: staying on its screen
                // would be a detail of nothing. The one that remains is
                // the game, and its own detail is where the user is now.
                if (entry.missing) onClose() else pickingReplacement = false
            },
            onDismiss = { pickingReplacement = false },
        )
    }

    // The replacement row is only worth drawing when there is somebody to offer.
    val replacements = names.replacements
    val runner = resolved
    val actions = rememberPcActions(
        group = group,
        currentId = entry.id,
        onOpenOther = onOpenOther,
        replacements = replacements.size,
        onReplace = { pickingReplacement = true },
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
            }.getOrElse { "enginehost didn't take that: ${it.message}" }
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
        hasWindowsRoute = runners.options.any {
            it.strategy == GameLaunchStrategy.WINE_PREFIX && it.state != RunnerState.NOT_FOR_THIS_GAME
        },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                val action = GamepadKeyMap.actionFor(event.key)
                if (event.type == KeyEventType.KeyUp && (action == GamepadAction.BACK || action == GamepadAction.B)) {
                    onClose()
                    true
                } else {
                    false
                }
            },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .padding(horizontal = dev.droidtop.shell.gamepad.LocalShellWindow.current.edgePadding),
            // The hint bar's own room at the end of the list, so the last
            // card clears it instead of ending under it (MenuTokens.HintBarRoom).
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                bottom = dev.droidtop.shell.gamepad.MenuTokens.HintBarRoom,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ONE header, artwork or not: a game's own screen opens with
            // the game, never straight into a column of rows. A game with
            // no scraped art gets the same plate with the same title and
            // identity line over it, so the screen has the same shape
            // either way (research/ui-polish item 18).
            item { PcDetailHeader(entry, grouping) }

            // A game the walk no longer finds has no runner question to
            // answer and nothing to play: the folder it was is not there.
            // Every row below the button still applies -- its history, its
            // metadata, its collections and the replacement action are
            // exactly what it is being kept FOR (docs/SPEC.md 7g).
            if (entry.missing) {
                item {
                    PrimaryActionButton(
                        label = "The folder is not there",
                        detail = missingFolderLine(entry),
                        enabled = false,
                        onSelect = {},
                    )
                }
            }

            // 1. Runs with -- WHICH runner, and how to change it. Whether
            // this game can be played is the button's sentence and only
            // the button's: the rig read "Nothing on this device can run
            // this game yet" here and "Can't play yet / No runner on this
            // device offers this game" immediately below it, three
            // wordings of one fact. A game with no runner option at all
            // has nothing to choose, so the row is not drawn.
            if (!entry.missing && (!loaded || runners.options.isNotEmpty())) {
                item {
                    DetailRow(
                        title = "Runs with",
                        detail = when {
                            !loaded -> "Working out what can run this…"
                            runner == null -> "Not chosen — ${runners.options.size} to choose from"
                            else -> "${runner.label} - ${runner.reason}"
                        },
                        enabled = loaded && runners.options.isNotEmpty(),
                        onSelect = { picking = true },
                    )
                }
            }

            // 2. The primary button: Play, or the one action that makes Play possible.
            if (!entry.missing) item {
                val setupAction = runner?.option?.action
                val isReady = runner?.option?.state == RunnerState.READY
                PrimaryActionButton(
                    label = when {
                        !loaded -> "…"
                        isReady -> "Play"
                        setupAction != null -> runner.option.reason ?: "Set up"
                        else -> "Can't play yet"
                    },
                    detail = when {
                        !loaded -> ""
                        isReady -> runner.option.caveat ?: "Starts now on ${runner.label}"
                        setupAction != null -> "One step, then this becomes Play"
                        else -> runner?.option?.reason ?: "No runner on this device offers this game"
                    },
                    enabled = loaded && (isReady || setupAction != null),
                    onSelect = {
                        if (isReady) {
                            onLaunch()
                        } else if (setupAction != null) {
                            scope.launch {
                                status = "Working…"
                                val failure = PcRunnerOptions.runAction(context, entry, setupAction) { status = it }
                                status = failure
                                reloadToken++
                            }
                        }
                    },
                )
            }

            status?.let { message -> item { Text(message, color = MenuTokens.Value, style = MaterialTheme.typography.bodySmall) } }

            actions.forEach { group ->
                item(key = "group:" + group.title) {
                    Text(
                        group.title,
                        color = MenuTokens.OnSurfaceMuted,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                items(group.rows, key = { "row:" + group.title + it.title }) { row ->
                    DetailRow(title = row.title, detail = row.detail, enabled = row.onSelect != null, onSelect = { row.onSelect?.invoke() })
                }
            }

            entry.pcInfo?.compatibility?.let { compat ->
                item {
                    Column(modifier = Modifier.padding(top = 16.dp, bottom = 32.dp)) {
                        Text("Compatibility", color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.labelLarge)
                        Text(compat.summary(), color = MenuTokens.Value, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Other people's results on other hardware.",
                            color = MenuTokens.OnSurfaceDisabled,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One line under the title: where it came from, what engine it is, how
 * big it is -- or, for a game the walk no longer finds, the one fact
 * that matters, in the words the card uses (docs/SPEC.md 7g).
 */
private fun LibraryEntry.identityLine(): String = if (missing) "broken - missing" else buildString {
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
    entry: LibraryEntry,
    runner: ResolvedRunner?,
    media: Int,
    onScrape: () -> Unit,
    onChooseMatch: () -> Unit,
    onViewMedia: () -> Unit,
    onCollections: () -> Unit,
    engineRow: PcActionRow?,
    onEnginehost: (android.content.Intent) -> Unit,
    onOpenAppScreen: (className: String, extras: Map<String, String>) -> Unit,
    hasWindowsRoute: Boolean,
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
                // Which engine this folder is, and the pin that corrects
                // detection (docs/SPEC.md 7e2b).
                engineRow,
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
                if (isStoreGame) {
                    PcActionRow(
                        "Downloads",
                        "Everything downloading or waiting, and the storage it is going into",
                        { onOpenAppScreen(PC_STORE_ACTIVITY, emptyMap()) },
                    )
                } else {
                    null
                },
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
        ),
        PcActionGroup(
            "Metadata and media",
            listOfNotNull(
                PcActionRow("Scrape", "Looks this game up in the PC sources", onScrape),
                PcActionRow("Choose match", "Pick the right game by hand when the scraper guessed wrong", onChooseMatch),
                if (media > 1) PcActionRow("View media", "$media images and videos scraped for this game", onViewMedia) else null,
                PcActionRow("Collections", "Which of your collections this game is in", onCollections),
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
        if (version.updateAvailable) append(" - ").append(version.latestKnown).append(" is available")
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
): PcActionGroup? = when {
    runsOnEnginehost -> PcActionGroup(
        "Runs on enginehost",
        listOfNotNull(
            PcActionRow("Saves", "Opens enginehost's own save settings", { onEnginehost(EngineHost.savesSettingsIntent()) }),
            PcActionRow(
                "Controls",
                "Opens enginehost's own per-engine controls for this game",
                { onEnginehost(EngineHost.settingsIntent()) },
            ),
            if (isEngineGame) {
                PcActionRow("Engine settings", "Opens enginehost's own settings", { onEnginehost(EngineHost.settingsIntent()) })
            } else {
                null
            },
        ),
    )
    hasWindowsRoute -> PcActionGroup(
        "Runs on Windows",
        listOf(
            PcActionRow(
                "Prefix and graphics",
                "The Windows prefix this game runs in: graphics driver, DXVK, Box64 and FEX, components, drives and the rest",
                onOpenPrefix,
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

/** What the detail knows of [PcGameDetail]'s entry from names alone, worked out off the main thread. */
private data class DetailNames(
    val forId: String?,
    val grouping: dev.droidtop.library.LibraryGameGroup?,
    val replacements: List<dev.droidtop.library.MissingGames.Candidate>,
) {
    companion object {
        val NONE = DetailNames(forId = null, grouping = null, replacements = emptyList())
    }
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
): List<dev.droidtop.library.MissingGames.Candidate> =
    dev.droidtop.library.MissingGames.candidates(
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

/**
 * A game's own screen opens with the game: its artwork when something has
 * scraped some, and the same plate with the same two lines when nothing
 * has. One anatomy either way -- the rig's detail screen started straight
 * into rows, with nothing of the game on its own screen at all.
 *
 * Nothing is invented here: the plate is the title and the identity line
 * this entry already carries, on the shell's own surface colour. There is
 * no stand-in cover art, because a made-up cover is a lie about a game.
 */
@Composable
private fun PcDetailHeader(entry: LibraryEntry, grouping: dev.droidtop.library.LibraryGameGroup?) {
    // The header names the GAME and then says which folder of it is open,
    // in the words the "Parts and versions" rows use. The card in the grid
    // already said "BeingADIK"; this screen said "BeingADik - Chap3+", the
    // folder's own qualified title, and the two disagreed on the same
    // screen pair (rig, build 550).
    // Until the grouping is worked out (off the main thread), the name
    // this one folder derives is the game's name as the grouping will say
    // it, bar casing; a store row keeps the title its store gave.
    val ownName = remember(entry) {
        if (entry.id.startsWith("/")) dev.droidtop.library.GameNaming.derive(entry.id).name.ifEmpty { entry.title } else entry.title
    }
    val title = dev.droidtop.library.GameNaming.displayName(grouping?.game?.name ?: ownName)
    val copyLine = grouping?.let { copyLabel(it, entry) }
    Box(modifier = Modifier.fillMaxWidth().height(220.dp).padding(top = 24.dp)) {
        if (entry.artworkUri != null) {
            AsyncImage(
                model = entry.artworkUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().background(MenuTokens.Card, RoundedCornerShape(16.dp)),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(MenuTokens.Card, RoundedCornerShape(16.dp)))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(Brush.verticalGradient(listOf(Color.Transparent, MenuTokens.Scrim)))
                .padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    color = MenuTokens.OnSurface,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (copyLine != null) {
                    Text(
                        copyLine,
                        color = MenuTokens.OnLaunchMuted,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Text(
                    entry.identityLine(),
                    color = MenuTokens.Value,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The one thing this screen is FOR, as a button rather than as another
 * row in the list of rows. It says what pressing it does and what will
 * happen; when nothing can be done it is a disabled button that says why,
 * which is the only honest shape for "this game has no runner here"
 * (research/ui-polish item 18).
 */
@Composable
private fun PrimaryActionButton(
    label: String,
    detail: String,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val background = when {
        !enabled -> MenuTokens.LaunchDisabled
        focused -> MenuTokens.LaunchFocused
        else -> MenuTokens.Launch
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            // Ahead of the focus targets, not after them: see [GameCard].
            .onKeyEvent { event ->
                if (enabled && event.type == KeyEventType.KeyUp &&
                    GamepadKeyMap.actionFor(event.key) == GamepadAction.A
                ) {
                    onSelect()
                    true
                } else {
                    false
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled = enabled)
            .then(if (enabled) Modifier.clickable(onClick = onSelect) else Modifier)
            .background(background, shape)
            .border(
                width = if (focused) MenuTokens.FocusRingWidth else 1.dp,
                color = if (focused) MenuTokens.Accent else MenuTokens.CardOutline,
                shape = shape,
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            label,
            color = if (enabled) MenuTokens.OnSurface else MenuTokens.OnSurfaceDisabled,
            style = MaterialTheme.typography.headlineSmall,
        )
        if (detail.isNotBlank()) {
            Text(
                detail,
                color = if (enabled) MenuTokens.OnLaunchMuted else MenuTokens.OnSurfaceDisabled,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DetailRow(
    title: String,
    detail: String,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Ahead of the focus targets, not after them: see [GameCard].
            .onKeyEvent { event ->
                if (enabled && event.type == KeyEventType.KeyUp &&
                    GamepadKeyMap.actionFor(event.key) == GamepadAction.A
                ) {
                    onSelect()
                    true
                } else {
                    false
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled = enabled)
            .then(if (enabled) Modifier.clickable(onClick = onSelect) else Modifier)
            .selectionFrame(focused, RoundedCornerShape(10.dp), rest = MenuTokens.CardInset)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            title,
            color = if (enabled) MenuTokens.OnSurface else MenuTokens.OnSurfaceDisabled,
            style = MaterialTheme.typography.titleMedium,
        )
        if (detail.isNotBlank()) {
            Text(detail, color = if (enabled) MenuTokens.Value else MenuTokens.OnSurfaceDisabled, style = MaterialTheme.typography.bodySmall)
        }
    }
}
