package dev.droidtop.shell.gamepad.pc

import androidx.compose.foundation.background
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
import dev.droidtop.library.GameLaunchStrategy
import dev.droidtop.library.LaunchStrategyOverridePrefs
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.PcRunnerOptions
import dev.droidtop.library.PcRunners
import dev.droidtop.library.ResolvedRunner
import dev.droidtop.library.RunnerState
import dev.droidtop.library.scraper.PcScraper
import dev.droidtop.shell.gamepad.CollectionMembershipEditor
import dev.droidtop.shell.gamepad.ManualMatchPicker
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

    val media = remember(entry) {
        val folder = PcRunnerOptions.gameFolderFor(entry)
        val roots = dev.droidtop.library.GamesRoots.current(context)
        val root = folder?.let { f -> roots.firstOrNull { f.absolutePath.startsWith(it.absolutePath) } }
        if (root != null && folder != null) {
            dev.droidtop.library.EsDeArtwork.allMedia(root, "pc", folder.name)
        } else {
            emptyList()
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

    val runner = resolved
    val actions = rememberPcActions(
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                if (entry.artworkUri != null) {
                    Box(modifier = Modifier.fillMaxWidth().height(220.dp).padding(top = 24.dp)) {
                        AsyncImage(
                            model = entry.artworkUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1A), RoundedCornerShape(16.dp)),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomStart)
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                                .padding(12.dp),
                        ) {
                            Text(entry.identityLine(), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            item {
                Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.title, color = Color.White, style = MaterialTheme.typography.headlineMedium)
                    if (entry.artworkUri == null) {
                        Text(entry.identityLine(), color = Color.Gray, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            // 1. Runs with — the resolved runner and the reason it won.
            item {
                DetailRow(
                    title = "Runs with",
                    detail = when {
                        !loaded -> "Working out what can run this…"
                        runner == null -> "Nothing on this device can run this game yet"
                        else -> "${runner.label} - ${runner.reason}"
                    },
                    enabled = loaded && runners.options.isNotEmpty(),
                    onSelect = { picking = true },
                )
            }

            // 2. The primary button: Play, or the one action that makes Play possible.
            item {
                val setupAction = runner?.option?.action
                val isReady = runner?.option?.state == RunnerState.READY
                DetailRow(
                    title = when {
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
                    primary = true,
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

            status?.let { message -> item { Text(message, color = Color(0xFFB0BEC5), style = MaterialTheme.typography.bodySmall) } }

            actions.forEach { group ->
                item(key = "group:" + group.title) {
                    Text(
                        group.title,
                        color = Color.Gray,
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
                        Text("Compatibility", color = Color.Gray, style = MaterialTheme.typography.labelLarge)
                        Text(compat.summary(), color = Color.LightGray, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Other people's results on other hardware.",
                            color = Color(0xFF6F6F6F),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/** One line under the title: where it came from, what engine it is, how big it is. */
private fun LibraryEntry.identityLine(): String = buildString {
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

@Composable
private fun rememberPcActions(
    entry: LibraryEntry,
    runner: ResolvedRunner?,
    media: Int,
    onScrape: () -> Unit,
    onChooseMatch: () -> Unit,
    onViewMedia: () -> Unit,
    onCollections: () -> Unit,
    onEnginehost: (android.content.Intent) -> Unit,
    onOpenAppScreen: (className: String, extras: Map<String, String>) -> Unit,
    hasWindowsRoute: Boolean,
): List<PcActionGroup> {
    val isEngineGame = entry.kind != LibraryEntryKind.WINE_PROFILE
    val runsOnEnginehost = runner?.option?.strategy == GameLaunchStrategy.ENGINEHOST
    val isStoreGame = entry.pcInfo?.storeId != null || entry.id.substringBefore(':') in STORE_PREFIXES

    return listOf(
        PcActionGroup(
            "Game management",
            listOfNotNull(
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
        PcActionGroup(
            "Prefix and graphics",
            listOf(
                PcActionRow(
                    "Prefix and graphics",
                    if (hasWindowsRoute) {
                        "The Windows prefix this game runs in: graphics driver, DXVK, Box64 and FEX, " +
                            "components, drives and the rest"
                    } else {
                        "Only for a game with a Windows build -- this one runs natively"
                    },
                    if (hasWindowsRoute) {
                        {
                            onOpenAppScreen(
                                PC_CONTAINER_CONFIG_ACTIVITY,
                                mapOf(EXTRA_PC_ENTRY_ID to entry.id, EXTRA_PC_TITLE to entry.title),
                            )
                        }
                    } else {
                        null
                    },
                ),
            ),
        ),
        PcActionGroup(
            "Saves and controls",
            listOfNotNull(
                PcActionRow(
                    "Saves",
                    if (runsOnEnginehost) "Opens enginehost's own save settings" else "A Windows game's saves live in its prefix, under Prefix and graphics",
                    if (runsOnEnginehost) ({ onEnginehost(EngineHost.savesSettingsIntent()) }) else null,
                ),
                PcActionRow(
                    "Controls",
                    if (runsOnEnginehost) {
                        "Opens enginehost's own per-engine controls for this game"
                    } else {
                        "A Windows game's controls are its prefix's controller tab, under Prefix and graphics"
                    },
                    if (runsOnEnginehost) ({ onEnginehost(EngineHost.settingsIntent()) }) else null,
                ),
                if (isEngineGame) {
                    PcActionRow(
                        "Engine settings",
                        if (runsOnEnginehost) "Opens enginehost's own settings" else "Available while this game runs on enginehost",
                        if (runsOnEnginehost) ({ onEnginehost(EngineHost.settingsIntent()) }) else null,
                    )
                } else {
                    null
                },
            ),
        ),
        PcActionGroup(
            "Metadata and media",
            listOfNotNull(
                PcActionRow("Scrape", "Looks this game up in the PC sources", onScrape),
                PcActionRow("Choose match", "Pick the right game by hand when the scrape guessed wrong", onChooseMatch),
                if (media > 1) PcActionRow("View media", "$media images and videos scraped for this game", onViewMedia) else null,
                PcActionRow("Collections", "Which of your collections this game is in", onCollections),
            ),
        ),
    )
}

private val STORE_PREFIXES = setOf("steam", "gog", "epic", "amazon")

// :app's hosts for the gamenative screens droidtop adopts, by name
// because this module cannot depend on :app. Kept together so the two
// sides are one edit apart if a class ever moves.
private const val PC_STORE_ACTIVITY = "dev.droidtop.app.PcStoreActivity"
private const val PC_CONTAINER_CONFIG_ACTIVITY = "dev.droidtop.app.PcContainerConfigActivity"
private const val EXTRA_PC_ENTRY_ID = "dev.droidtop.app.extra.PC_ENTRY_ID"
private const val EXTRA_PC_TITLE = "dev.droidtop.app.extra.PC_TITLE"

@Composable
private fun DetailRow(
    title: String,
    detail: String,
    enabled: Boolean,
    primary: Boolean = false,
    onSelect: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled = enabled)
            .then(if (enabled) Modifier.clickable(onClick = onSelect) else Modifier)
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
            .background(
                when {
                    focused -> Color(0xFF2F2F2F)
                    primary && enabled -> Color(0xFF1F3B2A)
                    else -> Color(0xFF141414)
                },
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            title,
            color = if (enabled) Color.White else Color(0xFF7A7A7A),
            style = if (primary) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
        )
        if (detail.isNotBlank()) {
            Text(detail, color = if (enabled) Color.LightGray else Color(0xFF5F5F5F), style = MaterialTheme.typography.bodySmall)
        }
    }
}
