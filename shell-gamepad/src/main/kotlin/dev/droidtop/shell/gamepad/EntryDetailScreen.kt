package dev.droidtop.shell.gamepad

import androidx.compose.ui.res.stringResource
import dev.droidtop.runtime.systemstatus.SettingsLaunch
import android.util.Log
import dev.droidtop.library.userFacingErrorMessage
import dev.droidtop.shell.gamepad.input.PadCadence
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.droidtop.runtime.tasks.text
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.droidtop.library.settings.CatalogPrefs
import dev.droidtop.library.settings.GamingSettingsCatalog
import dev.droidtop.library.EngineGameProvider
import dev.droidtop.library.Library
import dev.droidtop.runtime.tasks.TaskManager
import dev.droidtop.library.AppCategoryRules
import dev.droidtop.library.AppGameMarks
import dev.droidtop.library.AppUsageAccess
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.PcSource
import dev.droidtop.shell.gamepad.query.APPS_SCOPE_ID
import dev.droidtop.shell.gamepad.query.LibraryFacet
import dev.droidtop.shell.gamepad.query.LibraryFilterSheet
import dev.droidtop.shell.gamepad.query.LibraryQuery
import dev.droidtop.shell.gamepad.query.retroQueryScope
import dev.droidtop.shell.gamepad.query.LibrarySortSheet
import dev.droidtop.shell.gamepad.query.PersistQuery
import dev.droidtop.shell.gamepad.query.LibrarySearchDialog
import dev.droidtop.shell.gamepad.query.appsActiveView
import dev.droidtop.shell.gamepad.query.appsEmptyMessage
import dev.droidtop.shell.gamepad.query.appsLandingQuery
import dev.droidtop.shell.gamepad.query.appsStripLabel
import dev.droidtop.shell.gamepad.query.appsStripViews
import dev.droidtop.shell.gamepad.query.appsViewCounts
import dev.droidtop.shell.gamepad.query.appsViewQuery
import dev.droidtop.shell.gamepad.query.pillText
import dev.droidtop.shell.gamepad.query.SheetAction
import dev.droidtop.shell.gamepad.query.appsQueryScope
import dev.droidtop.shell.gamepad.query.rememberSavedViews
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.needsInstall
import dev.droidtop.library.scraper.isPcOrEngineGame
import dev.droidtop.shell.gamepad.pc.CapsuleStatusBadge
import dev.droidtop.shell.gamepad.pc.onPcGamesTab
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.consoles.PlatformsDatabase
import dev.droidtop.library.displayName
import dev.droidtop.library.itemName
import dev.droidtop.library.kindLine
import dev.droidtop.library.integrations.Integration
import dev.droidtop.library.integrations.IntegrationCapability
import dev.droidtop.library.integrations.IntegrationStore
import dev.droidtop.library.integrations.OpenWithTarget
import dev.droidtop.library.integrations.openWithChipLabel
import dev.droidtop.library.integrations.openWithTargetsFor
import dev.droidtop.library.theme.SystemThemeColors
import dev.droidtop.library.theme.EsDeCollectionKind
import dev.droidtop.library.theme.ThemeAssets
import dev.droidtop.library.scanFollowingGamesRoots
import dev.droidtop.library.theme.EsDeTransitionAnimation
import dev.droidtop.library.theme.boolOrNull
import dev.droidtop.library.theme.primaryListElement
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.ControllerLayouts
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.FocusedHintRow
import dev.droidtop.shell.gamepad.input.FocusedHints
import dev.droidtop.shell.gamepad.input.LocalFocusedHints
import dev.droidtop.shell.gamepad.input.LocalShellMenus
import dev.droidtop.shell.gamepad.input.ShellMenuHints
import dev.droidtop.shell.gamepad.input.ShellMenuPills
import dev.droidtop.shell.gamepad.input.ShellMenus
import dev.droidtop.shell.gamepad.input.declaresHints
import dev.droidtop.shell.gamepad.input.menuStep
import dev.droidtop.shell.gamepad.input.onPad
import dev.droidtop.shell.gamepad.input.ownPadButtons
import dev.droidtop.shell.gamepad.input.rememberHintList
import dev.droidtop.shell.gamepad.theme.EsDeListItem
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import dev.droidtop.shell.gamepad.theme.UiSound
import dev.droidtop.shell.gamepad.theme.EsDeSystemListView
import dev.droidtop.shell.gamepad.theme.EsDeThemedView
import dev.droidtop.shell.gamepad.theme.EsDeSystemElementLayer
import dev.droidtop.shell.gamepad.theme.EsDeSystemSlot
import dev.droidtop.shell.gamepad.theme.EsDeViewLayer
import dev.droidtop.library.theme.EsDeSystemSlide
import dev.droidtop.library.theme.strOrNull
import androidx.compose.animation.core.Animatable
import dev.droidtop.library.theme.EsDeViewTransition
import dev.droidtop.shell.gamepad.theme.ES_DE_SYSTEM_FADE_MS
import dev.droidtop.shell.gamepad.theme.esDeSystemFadeSpec
import dev.droidtop.shell.gamepad.theme.esDeTransitionContext
import dev.droidtop.shell.gamepad.theme.esDeTransitionKind
import dev.droidtop.shell.gamepad.theme.esDeViewTransition
import dev.droidtop.shell.gamepad.theme.ThemePrefs
import dev.droidtop.ui.shared.Motion
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen detail view for one entry — one horizontal row of primary
 * actions (Launch, leading/highlighted) instead of launch being a card's
 * only behavior. Reached via a card's Y/Info action, closed via B/Back.
 */
@Composable
internal fun EntryDetailScreen(
    entry: LibraryEntry,
    library: Library,
    onLaunch: () -> Unit,
    onClose: () -> Unit,
    onPrimaryFocus: (String?) -> Unit = {},
    /** The entry after an edit of its metadata, for the list that holds it (Droidtop/tracker#766). */
    onEntryChanged: (LibraryEntry) -> Unit = {},
) {
    val context = LocalContext.current
    val launchFocus = remember { FocusRequester() }
    LaunchedEffect(entry) { launchFocus.requestFocus() }

    var editingMetadata by remember { mutableStateOf(false) }
    var viewingMedia by remember(entry) { mutableStateOf(false) }
    var pickingMatch by remember(entry) { mutableStateOf(false) }
    var scrapeStatus by remember(entry) { mutableStateOf<String?>(null) }
    var scrapeResult by remember(entry) { mutableStateOf<String?>(null) }
    var pluginActionStatus by remember(entry) { mutableStateOf<String?>(null) }
    var pluginActionScreen by remember(entry) { mutableStateOf<dev.droidtop.library.settings.CatalogScreen?>(null) }
    // Everything scraped for this game, for the media viewer: listed on
    // IO once per entry, never while drawing (the same as PcGameMenu).
    val media by produceState(emptyList<Pair<String, String>>(), entry) {
        value = withContext(Dispatchers.IO) {
            val romFile = java.io.File(entry.id)
            val gamesRoot = dev.droidtop.library.GamesRoots.rootOf(dev.droidtop.library.GamesRoots.current(context), romFile)
            val systemId = entry.systemId
            if (gamesRoot != null && systemId != null) {
                dev.droidtop.library.EsDeArtwork.allMedia(gamesRoot, systemId, romFile.nameWithoutExtension)
            } else {
                emptyList()
            }
        }
    }
    var editingCollections by remember { mutableStateOf(false) }

    // The user's own "open with" hooks (docs/SPEC.md section 12), paired
    // with the real files on this entry droidtop itself cannot open.
    // Resolved off the main thread: which of the entry's files exist,
    // the integrations folder, and whether the PackageManager has each
    // declared target app.
    var openWithTargets by remember(entry) { mutableStateOf<List<dev.droidtop.library.integrations.OpenWithTarget>>(emptyList()) }
    var openWith by remember(entry) { mutableStateOf<List<Integration>>(emptyList()) }
    var integrationError by remember(entry) { mutableStateOf<String?>(null) }
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

    // Context actions plugins offer on this game or app (docs/plugin-api.md 3 C4). Which ones apply comes from
    // manifests alone, and `enabled` is asked once, when this screen opens, never while drawing.
    val contextTarget = remember(entry) { dev.droidtop.library.integrations.PluginContextActions.targetOf(entry) }
    var pluginActions by remember(entry) { mutableStateOf<List<dev.droidtop.library.integrations.PluginContextActions.Action>>(emptyList()) }
    // Rows plugins add to a game's page (docs/plugin-api.md 3 C18): on this screen each section is a chip that opens
    // it as a page, where its rows, inputs and actions work; the PC game page draws them as its own rows instead.
    var pluginSections by remember(entry) { mutableStateOf<List<dev.droidtop.library.integrations.PluginGameSections.Section>>(emptyList()) }
    LaunchedEffect(entry) {
        pluginActions = withContext(Dispatchers.IO) {
            dev.droidtop.library.integrations.PluginContextActions.actionsFor(context, contextTarget, dev.droidtop.pluginhost.PluginModes.GAMING)
                .filter { dev.droidtop.library.integrations.PluginContextActions.enabled(context, it, contextTarget) }
        }
        pluginSections = withContext(Dispatchers.IO) {
            dev.droidtop.library.integrations.PluginGameSections.sectionsFor(context, contextTarget)
        }
    }

    // Console ROMs and native apps only: a PC or engine game never
    // reaches this screen (see the PC branch at the call site). Its
    // launch-strategy picker, scrape action and "choose match" live in
    // PcGameMenu alone.
    val isRomEntry = entry.kind == LibraryEntryKind.CONSOLE_ROM
    // Achievements, play time and an emulator's compatibility, asked for once when the page opens (docs/SPEC.md 7h,
    // "Game info"); a console game only, an app has none of them.
    val gameInfo = rememberGameInfoRows(entry, enabled = isRomEntry)

    if (editingMetadata) {
        GameMetadataEditor(
            entry = entry,
            library = library,
            onDismiss = { editingMetadata = false },
            onSaved = onEntryChanged,
        )
        return
    }
    if (pickingMatch) {
        ManualMatchPicker(
            entry = entry,
            onApplied = { scrapeResult = it },
            onDismiss = { pickingMatch = false },
        )
    }
    if (viewingMedia) {
        MediaViewer(title = entry.title, media = media, onClose = { viewingMedia = false })
        return
    }
    if (editingCollections) {
        CollectionMembershipEditor(
            entry = entry,
            library = library,
            onDismiss = { editingCollections = false },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(LocalShellWindow.current.edgePadding)
            // B closes the detail; the system back key reaches the same
            // close through the shell's BackHandler for it (SPEC 6e).
            .onPad { press ->
                if (press.action == GamepadAction.B) {
                    onClose()
                    true
                } else {
                    false
                }
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (entry.artworkUri != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(MenuTokens.Card, Corners.Plate),
            ) {
                if (entry.kind == LibraryEntryKind.NATIVE_ANDROID_APP) {
                    // An app's artwork is its launcher icon: drawn at an
                    // icon's size on the plate, never cropped and blown up
                    // into a blurred banner (UI pass 2026-09-24, L1).
                    AsyncImage(
                        model = entry.artworkUri,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(96.dp).align(Alignment.Center),
                    )
                } else {
                    AsyncImage(
                        model = entry.artworkUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().background(MenuTokens.Card, Corners.Plate),
                    )
                }
                // Platform/kind label overlaid on the art, matching Daijishō's
                // own detail-screen layout (boxart with the platform name
                // overlaid at the bottom of the art) -- structure, not pixels.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, MenuTokens.Scrim)))
                        .padding(12.dp),
                ) {
                    Text(entry.kind.itemName(), color = MenuTokens.OnSurface, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(dev.droidtop.library.GameNaming.displayName(entry.title), color = MenuTokens.OnSurface, style = MaterialTheme.typography.headlineMedium)
        if (entry.artworkUri == null) {
            Text(entry.kind.itemName(), color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.titleMedium)
        }
        if (entry.playtimeSeconds > 0) {
            Text(stringResource(R.string.played_for, dev.droidtop.library.format.ValueFormat.duration(entry.playtimeSeconds)), color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
        }
        gameInfo.forEach { row ->
            Text(
                row.value?.let { "${row.title}: $it" } ?: row.title,
                color = MenuTokens.OnSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            row.subtitle?.let { Text(it, color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall) }
        }
        // What this game's Switch files add up to -- "Update v131072 ·
        // 2 DLC", or, for a row that is itself an update/DLC whose base
        // game is missing, that fact. Empty (and so drawn not at all)
        // for a base game with nothing beside it.
        entry.switchFacts?.line()?.takeIf { it.isNotEmpty() }?.let {
            Text(it, color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
        }
        val detailScope = rememberCoroutineScope()
        // Scrolls rather than clipping: on a phone the chips outgrow the
        // width, and a chip off the edge is an action nobody can reach.
        Row(
            modifier = Modifier.padding(top = 16.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ShellChip(
                "Launch",
                primary = true,
                modifier = Modifier
                    .focusRequester(launchFocus)
                    .onFocusChanged { onPrimaryFocus(if (it.isFocused) "Launch" else null) },
                onClick = onLaunch,
            )
            gameInfo.filter { it.chip != null }.forEach { row ->
                ShellChip(
                    row.chip.orEmpty(),
                    onClick = {
                        val page = row.screen
                        val address = row.url
                        val step = row.run
                        if (step != null) step(context) else if (page != null) pluginActionScreen = page else if (address != null) dev.droidtop.library.Links.open(context, address)
                    },
                )
            }
            // Real ConsoleRomProvider-specific concept -- same honest
            // "not applicable" gating Library.toggleFavorite/
            // saveMetadata already use for a non-ROM entry.
            if (isRomEntry) {
                ShellChip("Choose match", onClick = { pickingMatch = true })
            }
            // A Switch game with an update or DLC beside it: opens its emulator and names the files to pick
            // (SwitchContentHandoff).
            if (dev.droidtop.library.consoles.SwitchContentHandoff.applies(entry)) {
                ShellChip(
                    "Add update and DLC",
                    onClick = {
                        detailScope.launch {
                            integrationError = dev.droidtop.library.consoles.SwitchContentHandoff.open(context, entry)
                        }
                    },
                )
            }
            if (media.size > 1) {
                ShellChip("View media (${media.size})", onClick = { viewingMedia = true })
            }
            pluginActions.forEach { action ->
                ShellChip(
                    action.label,
                    onClick = {
                        pluginActionStatus = "${action.label}…"
                        pluginActionScreen = null
                        detailScope.launch {
                            val outcome = withContext(Dispatchers.IO) {
                                dev.droidtop.library.integrations.PluginContextActions.run(context, action, contextTarget)
                            }
                            pluginActionStatus = outcome.message
                            pluginActionScreen = outcome.screen
                        }
                    },
                )
            }
            pluginSections.forEach { section ->
                ShellChip(
                    section.label,
                    onClick = {
                        detailScope.launch {
                            // Built off the main thread: the page's game context reads the plugin's grants.
                            pluginActionScreen = withContext(Dispatchers.IO) {
                                dev.droidtop.library.integrations.PluginGameSections.screenFor(context, section, contextTarget)
                            }
                        }
                    },
                )
            }
            // One chip per (hook, openable file). Never a substitution and
            // never a silent pick: droidtop has no manual reader and no
            // full video player of its own, so these open a door rather
            // than taking over a behaviour, and if the user declared two
            // hooks they both appear and the user chooses -- droidtop does
            // not rank them.
            openWith.forEach { integration ->
                openWithTargets.forEach { target ->
                    ShellChip(
                        openWithChipLabel(integration, target, openWithTargets.size),
                        onClick = {
                            integrationError = runCatching {
                                IntegrationStore.run(
                                    context = context,
                                    integration = integration,
                                    systemId = entry.systemId,
                                    file = target.file,
                                )
                            }.exceptionOrNull()?.let { failure ->
                                Log.w("droidtop.gamepad", "Integration failed: ${integration.label}", failure)
                                "${integration.label} failed: ${userFacingErrorMessage(failure)}"
                            }
                        },
                    )
                }
            }
            if (isRomEntry) {
                ShellChip("Edit metadata", onClick = { editingMetadata = true })
                ShellChip("Collections", onClick = { editingCollections = true })
                ShellChip(
                    scrapeStatus?.let { "Scraping…" } ?: "Scrape",
                    onClick = {
                        if (scrapeStatus == null) {
                            scrapeStatus = "Scraping ${entry.title}…"
                            detailScope.launch {
                                val romFile = java.io.File(entry.id)
                                val folder = romFile.parentFile
                                val systemsById = withContext(Dispatchers.IO) {
                                    dev.droidtop.library.consoles.ConsoleSystemsRepository.allSystems(context)
                                }.associateBy { it.id }
                                val system = entry.systemId?.let { systemsById[it] }
                                scrapeResult = if (folder == null || system == null) {
                                    "Can't resolve this game's system folder."
                                } else {
                                    dev.droidtop.library.scraper.scrapeSystemArtwork(
                                        context,
                                        folder,
                                        system,
                                        onlyRom = romFile,
                                    )
                                }
                                scrapeStatus = null
                            }
                        }
                    },
                )
            }
            // An app's own actions, on its own screen (UI pass 2026-09-24,
            // L1). Android's screens do the work and Uninstall asks its own
            // confirmation. There is no Back chip: B is on the hint row,
            // which is also its touch route.
            if (entry.kind == LibraryEntryKind.NATIVE_ANDROID_APP) {
                ShellChip("App info", onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", entry.id, null),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                })
                ShellChip("Uninstall", onClick = { startUninstall(context, entry.id) })
            }
        }
        (pluginActionStatus ?: scrapeStatus ?: scrapeResult)?.let {
            Text(it, color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        // An integration drives another app's own real Activity, so it can
        // fail for reasons droidtop cannot see coming (the template names a
        // component that app no longer exports). Shown, not swallowed.
        integrationError?.let {
            Text(it, color = MenuTokens.NoticeDanger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
    }
    pluginActionScreen?.let { screen ->
        val close = { pluginActionScreen = null }
        // The one sheet with the ground painted (CatalogSheet), so the screen behind never shows through (rig,
        // 0.2.0-dev.2070).
        CatalogSheet(root = screen, onExit = close)
    }
}
