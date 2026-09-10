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
import dev.droidtop.library.ResolvedRunner
import dev.droidtop.library.RunnerOption
import dev.droidtop.library.RunnerState
import dev.droidtop.library.displayName
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
 * Actions that belong to build-plan steps 5-7 (store install and
 * download, prefix and graphics configuration) are listed and disabled
 * with the reason rather than omitted, so the shape of the screen does
 * not change under the user when those land.
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

    var options by remember(entry) { mutableStateOf<List<RunnerOption>>(emptyList()) }
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
            val list = PcRunnerOptions.forEntry(context, entry)
            list to PcRunnerOptions.resolvedFor(context, entry, list)
        }
        options = computed.first
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
            options = options,
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
            modifier = Modifier.fillMaxSize().weight(1f).padding(horizontal = 48.dp),
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
                        else -> "${runner.option.strategy.displayName()} - ${runner.reason}"
                    },
                    enabled = loaded && options.isNotEmpty(),
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
                        isReady -> runner.option.caveat ?: "Starts now on ${runner.option.strategy.displayName()}"
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
): List<PcActionGroup> {
    val isEngineGame = entry.kind != LibraryEntryKind.WINE_PROFILE
    val runsOnEnginehost = runner?.option?.strategy == GameLaunchStrategy.ENGINEHOST
    val isStoreGame = entry.pcInfo?.storeId != null || entry.id.substringBefore(':') in STORE_PREFIXES

    return listOf(
        PcActionGroup(
            "Game management",
            listOf(
                PcActionRow(
                    if (entry.pcInfo?.installed == false) "Install" else "Uninstall",
                    if (isStoreGame) {
                        "Store downloads arrive with the store flow (build-plan step 5)"
                    } else {
                        "This game is a folder on this device; droidtop doesn't manage it"
                    },
                    null,
                ),
                PcActionRow("Verify files", "Arrives with the store flow (build-plan step 5)", null),
            ),
        ),
        PcActionGroup(
            "Prefix and graphics",
            listOf(
                PcActionRow(
                    "Windows container settings",
                    "Arrives with the prefix step (build-plan step 7)",
                    null,
                ),
            ),
        ),
        PcActionGroup(
            "Saves and controls",
            listOfNotNull(
                PcActionRow(
                    "Saves",
                    if (runsOnEnginehost) "Opens enginehost's own save settings" else "Cloud saves arrive with the store flow (build-plan step 5)",
                    if (runsOnEnginehost) ({ onEnginehost(EngineHost.savesSettingsIntent()) }) else null,
                ),
                PcActionRow(
                    "Controls",
                    if (runsOnEnginehost) {
                        "Opens enginehost's own per-engine controls for this game"
                    } else {
                        "The Windows container's controller tab arrives with the prefix step (build-plan step 7)"
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
