package dev.droidtop.app.settings

import dev.droidtop.runtime.systemstatus.SettingsLaunch
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.droidtop.app.GamesRootPrefs
import dev.droidtop.app.LibraryCore
import dev.droidtop.app.PluginStatusWidgetProvider
import dev.droidtop.app.ScraperKeySetupActivity
import dev.droidtop.library.scraper.importGamelistXml
import dev.droidtop.library.scraper.LibraryScrapeJob
import dev.droidtop.library.consoles.ConsoleSystemDef
import dev.droidtop.library.consoles.canResolveFromFolder
import dev.droidtop.library.consoles.ConsoleSystemEntity
import dev.droidtop.library.consoles.ConsoleSystemsDatabase
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.consoles.PlatformDatabaseSnapshot
import dev.droidtop.library.consoles.PlatformDatabaseSource
import dev.droidtop.library.consoles.PlatformDatabases
import dev.droidtop.library.consoles.SystemFolders
import dev.droidtop.library.consoles.SystemOverridePrefs
import dev.droidtop.library.consoles.BiosDatabase
import dev.droidtop.library.consoles.KnownPlayers
import dev.droidtop.library.consoles.SystemBiosSpec
import dev.droidtop.library.consoles.availablePlayers
import dev.droidtop.library.integrations.IntegrationCapability
import dev.droidtop.library.integrations.IntegrationPlaceholders
import dev.droidtop.library.integrations.IntegrationStore
import dev.droidtop.library.integrations.AcquireContentSources
import dev.droidtop.library.integrations.PluginAppStatus
import dev.droidtop.library.integrations.PluginCatalog
import dev.droidtop.library.integrations.PluginCatalogScreen
import dev.droidtop.library.integrations.PluginBackground
import dev.droidtop.library.integrations.PluginFacets
import dev.droidtop.library.integrations.PluginPanels
import dev.droidtop.library.integrations.PluginSettingsRows
import dev.droidtop.library.integrations.PluginJobsScreen
import dev.droidtop.library.GameUpdates
import dev.droidtop.library.LibraryGrouping
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.PcFolderScan
import dev.droidtop.library.GameEngineDetector
import dev.droidtop.library.EnginesDatabase
import dev.droidtop.library.ScanPrune
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.pluginhost.PluginMainUi
import dev.droidtop.pluginhost.ApiResolution
import dev.droidtop.pluginhost.GrantState
import dev.droidtop.pluginhost.PluginApiResolver
import dev.droidtop.pluginhost.PluginAudit
import dev.droidtop.pluginhost.PluginGrants
import dev.droidtop.pluginhost.PluginPermissions
import dev.droidtop.pluginhost.PluginModes
import dev.droidtop.pluginhost.BackgroundProtocol
import dev.droidtop.pluginhost.PluginProviderChoices
import dev.droidtop.pluginhost.PluginKind
import dev.droidtop.pluginhost.PluginTrustState
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginConsent
import dev.droidtop.pluginhost.PermissionTier
import dev.droidtop.pluginhost.ExtensionPoints
import dev.droidtop.pluginhost.PluginCrashPolicy
import dev.droidtop.pluginhost.PluginTier
import dev.droidtop.pluginhost.PluginTiers
import dev.droidtop.pluginhost.PluginDangers
import dev.droidtop.pluginhost.PluginOriginKeys
import dev.droidtop.pluginhost.PluginSourceKeys
import dev.droidtop.pluginhost.PluginRuntimeNeeds
import dev.droidtop.pluginhost.PythonRuntimeManager
import dev.droidtop.pluginhost.RuntimeNeed
import dev.droidtop.pluginhost.FlutterRuntimeManager
import dev.droidtop.pluginhost.UserOriginKey
import dev.droidtop.pluginhost.UserOriginKeys
import dev.droidtop.pluginhost.AddKeyOutcome
import dev.droidtop.library.consoles.resolvePlayer
import dev.droidtop.library.scraper.ScraperKeyCheck
import dev.droidtop.library.gameinfo.GameInfoPrefs
import dev.droidtop.library.scraper.ScraperKeyService
import dev.droidtop.library.scraper.ScraperSource
import dev.droidtop.library.scraper.ScraperSourcePrefs
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.ButtonRole
import dev.droidtop.library.settings.CatalogChip
import dev.droidtop.library.settings.CatalogPage
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.DeveloperMenu
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.FolderPickItem
import dev.droidtop.library.settings.CatalogIcon
import dev.droidtop.library.settings.GamingSettingsCatalog
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.Notice
import dev.droidtop.library.settings.NoticeAction
import dev.droidtop.library.settings.NoticeItem
import dev.droidtop.library.settings.NoticePlacement
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.library.settings.Notices
import dev.droidtop.library.settings.PageButton
import dev.droidtop.library.settings.PageHeader
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.library.settings.TextBlockItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.library.theme.SystemThemeColors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Classification of a folder for the Console systems page.
 * PC/store and engine folders are not console system folders; they are
 * managed by the PC/engine library scans and should not show a system picker.
 */
private sealed interface FolderKind {
    data class ConsoleSystem(val resolvedSystem: ConsoleSystemDef?): FolderKind
    data class PcStore(val storeName: String, val gameCount: Int): FolderKind
    data class Engine(val gameCount: Int): FolderKind
}

/**
 * Classify [folder] and count its games for the Console systems page.
 * Returns the folder kind and game count (0 if not counted).
 */
private suspend fun classifyFolder(
    context: Context,
    folder: File,
    systemsById: Map<String, ConsoleSystemDef>,
): FolderKind = withContext(Dispatchers.IO) {
    // Store root (e.g., Steam, GOG Galaxy) or folder inside a store tree
    val storeOwner = ScanPrune.storeRootOwner(folder) ?: ScanPrune.storeTreeRoot(folder)?.let { ScanPrune.storeRootOwner(it) }
    if (storeOwner != null) {
        val defs = runCatching { EnginesDatabase.defs(context) }.getOrDefault(emptyList())
        val topFolders = PcFolderScan.gamesByTopLevelFolder(folder, defs) { _, _ -> false }
        val gameCount = topFolders.sumOf { it.games.size }
        return@withContext FolderKind.PcStore(storeOwner, gameCount)
    }
    // Engine games folder
    val defs = runCatching { EnginesDatabase.defs(context) }.getOrDefault(emptyList())
    if (defs.isNotEmpty() && GameEngineDetector.holdsSeveralGames(folder, defs, systemsById)) {
        // Count engine games directly under this folder
        var count = 0
        for (child in folder.listFiles().orEmpty()) {
            if (!child.isDirectory) continue
            if (!ScanPrune.isScannableFolder(child)) continue
            if (GameEngineDetector.isGameRoot(child, defs)) count++
        }
        return@withContext FolderKind.Engine(count)
    }
    // Console system folder (or unrecognized folder the user picked)
    val resolved = SystemOverridePrefs.resolveForFolder(context, folder.absolutePath, folder.name, systemsById)
    FolderKind.ConsoleSystem(resolved)
}

/**
 * :app's management screens as settings-catalog data (docs/SPEC.md
 * settings architecture -- per direction, EVERYTHING that is a droidtop
 * setting lives in the catalog model and is chromed by the shared
 * renderers; these used to be a hand-rolled Compose activity with its
 * own one-off look). Registered into [SettingsScreenRegistry] at process
 * start by [SettingsCatalogInitProvider], so lower modules
 * (GamingSettingsCatalog in :runtime-common, the Preference surface in
 * :shell-default) can open them by id without depending on :app.
 */
object AppSettingsCatalogs {

    const val SCREEN_CONSOLE_SYSTEMS = "console_systems"
    const val SCREEN_SCRAPER = "rom_scraper"
    const val SCREEN_PLATFORMS = "manage_platforms"
    const val SCREEN_INTEGRATIONS = AcquireContentSources.INTEGRATIONS_SCREEN_ID
    const val SCREEN_PLUGINS = AcquireContentSources.PLUGINS_SCREEN_ID
    const val SCREEN_PLUGIN_KEYS = "plugin_keys"
    const val SCREEN_WINDOWS_GAMES = "windows_games"
    const val SCREEN_ACCOUNTS_AND_SOURCES = "accounts_and_sources"
    const val SCREEN_ANDROID_SETTINGS = "android_settings"
    const val SCREEN_ENGINEHOST = "enginehost"
    const val SCREEN_UPDATES = "updates"

    @Volatile private var registered = false

    /** Rebuild the library index, the recovery path the Developer menu holds (docs/SPEC.md 7, "Developer menu"). */
    fun rebuildIndexItem(): CatalogItem =
        // A different job from Rescan: it walks no folder.
        // The index is derived from the per-game records
        // (docs/SPEC.md 7g), so it can always be rebuilt
        // from them; this is the recovery path when the
        // list looks wrong but the games have not changed.
        AsyncActionItem(
            id = "library_rebuild_index",
            title = "Rebuild the library index",
            subtitle = "Rebuilds the game list from what droidtop already knows about each " +
                "game, without scanning any folder. Use it if the list looks wrong after an " +
                "update; to pick up new or changed games, use Rescan library",
            run = { ctx, onStatus ->
                onStatus("Rebuilding...")
                val result = withContext(Dispatchers.IO) {
                    dev.droidtop.app.LibraryCore.library(ctx).rebuildIndexFromRecords()
                }
                when {
                    result.records == 0 && result.keptWithoutRecord == 0 ->
                        "Nothing to rebuild from yet: scan the library first"
                    result.keptWithoutRecord == 0 -> "Rebuilt from ${result.records} game records"
                    else -> "Rebuilt from ${result.records} game records, and kept " +
                        "${result.keptWithoutRecord} listed games that had none"
                }
            },
        )

    fun ensureRegistered() {
        if (registered) return
        registered = true
        SettingsScreenRegistry.register(consoleSystemsScreen())
        SettingsScreenRegistry.register(EmulatorsCatalog.screen())
        SettingsScreenRegistry.register(RiskyActionsCatalog.screen())
        // The one game folders screen (Droidtop/tracker#589).
        SettingsScreenRegistry.register(FoldersCatalog.screen())
        SettingsScreenRegistry.register(scraperScreen())
        SettingsScreenRegistry.register(platformsScreen())
        SettingsScreenRegistry.register(integrationsScreen())
        SettingsScreenRegistry.register(pluginsScreen())
        SettingsScreenRegistry.register(pluginKeysScreen())
        // Opened on their own by droidtop:// action links (LinkActivity, LinkRouter); More catalogs is also inside Plugins > Add.
        SettingsScreenRegistry.register(PluginCatalogScreen.screen())
        SettingsScreenRegistry.register(dev.droidtop.library.integrations.ActionLinks.screen())
        SettingsScreenRegistry.register(PluginCatalogScreen.officialScreen())
        // The link router's own screens and an app catalog's source review (docs/SPEC.md 12a "Links"), and Get apps (10b).
        SettingsScreenRegistry.register(dev.droidtop.library.integrations.LinkRouter.choiceScreen())
        SettingsScreenRegistry.register(dev.droidtop.library.integrations.LinkRouter.resultScreen())
        SettingsScreenRegistry.register(dev.droidtop.library.integrations.LinkRouter.handlersScreen())
        SettingsScreenRegistry.register(dev.droidtop.library.integrations.AppCatalogScreen.screen())
        SettingsScreenRegistry.register(dev.droidtop.library.integrations.AppCatalogScreen.reviewScreen())
        // Downloads and Updates leads with getting games and closes with each source's standing (Droidtop/tracker#644).
        SettingsScreenRegistry.register(PluginJobsScreen.screen(lead = StoresCatalog::getGamesGroups, trail = StoresCatalog::sourcesGroups))
        SettingsScreenRegistry.register(DownloadRulesCatalog.screen())
        SettingsScreenRegistry.register(dev.droidtop.library.integrations.ManualDownloads.screen())
        SettingsScreenRegistry.register(MeteredConnectionCatalog.screen())
        SettingsScreenRegistry.register(windowsGamesScreen())
        SettingsScreenRegistry.register(WineOptionsCatalog.gameScreen())
        SettingsScreenRegistry.register(WindowsInstallCatalog.screen())
        SettingsScreenRegistry.register(LinuxGameToolsCatalog.screen())
        SettingsScreenRegistry.register(RunInPrefixCatalog.screen())
        SettingsScreenRegistry.register(PrefixToolsCatalog.gameScreen())
        SettingsScreenRegistry.register(StoresCatalog.screen())
        SettingsScreenRegistry.register(SocialCatalog.screen())
        SettingsScreenRegistry.register(accountsAndSourcesScreen())
        SettingsScreenRegistry.register(androidSettingsScreen())
        SettingsScreenRegistry.register(enginehostScreen())
        SettingsScreenRegistry.register(UpdatesPage.screen())
        SettingsScreenRegistry.register(UpdatesPage.quickMenuScreen())
        SettingsScreenRegistry.register(ComputersCatalog.screen())
        SettingsScreenRegistry.register(DroidtopWideSettings.globalScreen())
        SettingsScreenRegistry.register(DroidtopWideSettings.desktopScreen())
        SettingsScreenRegistry.register(DroidtopWideSettings.standardScreen())
        SettingsScreenRegistry.register(DroidtopWideSettings.developerScreen())
        SettingsScreenRegistry.register(ContainersCatalog.screen())
    }

    // ------------------------------------------------------------------
    // Console systems: per-folder system/player/scrape management.
    // ------------------------------------------------------------------

    private fun consoleSystemsScreen(systemId: String? = null): CatalogScreen = CatalogScreen(
        id = SCREEN_CONSOLE_SYSTEMS,
        title = "Console systems",
        subtitle = "Each folder's system comes from its name; open a folder to change it",
        groups = { context -> consoleSystemsGroups(context, systemId) },
        // The search index must not pay for the folder walk in
        // consoleSystemsGroups: on a real device it held "Indexing
        // settings..." for 10-40 s before the first result
        // (Droidtop/tracker#136). It reads the static groups plus the
        // folder picker; the per-folder rows are live library data, not
        // settings to find by name.
        indexGroups = { context -> consoleSystemsGroups(context, systemId, forIndex = true) },
        // The per-system deep link the gamelist options menu's "System
        // settings" row opens (docs/SPEC.md "One consistent way into
        // Settings"): the SAME builder re-opened with the system id the
        // menu was opened from -- one screen, parameterized, never a
        // second folder/emulator picker.
        forDeepLink = { deepLinkedSystemId -> consoleSystemsScreen(deepLinkedSystemId) },
    )

    private suspend fun consoleSystemsGroups(
        context: Context,
        systemId: String? = null,
        forIndex: Boolean = false,
    ): List<CatalogGroup> = withContext(Dispatchers.IO) {
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        // The library's own answer (SystemFolders), not a second walk:
        // the folders it scans as console systems, plus the ones the person
        // picked to choose a system for. A folder the library reads as PC
        // or engine games is not a console system folder and is not here.
        // The search index passes forIndex and gets no folders at all: the
        // walk and the per-folder game counts are library-sized work.
        val rawFolders = if (forIndex) {
            emptyList()
        } else {
            SystemFolders.all(context, systemsById).map { it.first }
                .plus(SystemFolders.awaitingSystem(context))
                .distinctBy { it.absolutePath }
                .sortedBy { it.name.lowercase() }
        }
        // Classify each folder: console system, PC/store, or engine.
        val classifiedFolders = mutableListOf<Pair<File, FolderKind>>()
        for (folder in rawFolders) {
            classifiedFolders += folder to classifyFolder(context, folder, systemsById)
        }
        // The per-system deep link (docs/SPEC.md "One consistent way
        // into Settings"): the gamelist options menu's "System settings"
        // row re-opens this screen with the system id it was opened
        // from, so the folder section below lands on that one system's
        // rows instead of the top of the whole list. A system with no folder
        // here (a group whose id matches no console system, or one whose
        // folders live outside every games root) resolves to no rows and
        // the screen falls back to the full list rather than a dead
        // end; "Choose a system for another folder" is for folders whose
        // names are NOT a system's, so it stands down while one
        // system's config is what is on screen.
        val deepLinkedSystem = systemId?.let { systemsById[it] }
        val deepLinkedRows: List<CatalogItem>? = deepLinkedSystem?.let { system ->
            classifiedFolders.mapNotNull { (folder, kind) ->
                (kind as? FolderKind.ConsoleSystem)
                    ?.takeIf { it.resolvedSystem?.id == system.id }
                    ?.let { consoleFolderRow(context, folder, it) }
            }.takeIf { rows -> rows.isNotEmpty() }
        }
        listOf(
            // Regrouped from one flat run of five unrelated rows into
            // labeled sections (settings polish pass, 2026-09-25): this
            // was the one management screen with no section label at all
            // while its sibling Settings screens already used them.
            // App integrations, Plugins and their Jobs moved out to
            // Settings > Accounts and sources (droidtop UI pass, "sources
            // are a detail, never their own screen" -- docs/SPEC.md
            // settings architecture): they are not console-system
            // management, and burying them here was one more place a
            // provider-ish row hid instead of standing with its own kind.
            CatalogGroup(
                id = "console_systems_management",
                title = "Management",
                items = listOf(
                    NestedScreenItem(
                        id = "console_systems_platforms",
                        title = "Manage platforms",
                        subtitle = "Add, edit, or delete the platforms droidtop recognizes",
                        registryId = SCREEN_PLATFORMS,
                        icon = CatalogIcon.PLATFORMS,
                    ),
                    NestedScreenItem(
                        id = "console_systems_enginehost",
                        title = "Enginehost",
                        subtitle = "Engine-game runtimes, like an emulator's core list; its own settings and save storage",
                        registryId = SCREEN_ENGINEHOST,
                        icon = CatalogIcon.ENGINEHOST,
                    ),
                ),
            ),
            CatalogGroup(
                id = "console_systems_database",
                title = "Platform database",
                items = listOf(
                    // Find orphaned media and Scrape all systems are
                    // one-shot library actions, in the Games section's
                    // options menu (docs/SPEC.md 7f, "Where things live").
                    AsyncActionItem(
                        id = "console_systems_update_players",
                        title = "Update platform databases",
                        subtitle = "Players, platforms, engine routing and BIOS files; built from " +
                            (PlatformDatabaseSnapshot.shortCommit(context)
                                ?.let { "snapshot $it" } ?: "an unrecorded snapshot"),
                        run = { ctx, onStatus ->
                            withContext(Dispatchers.IO) {
                                PlatformDatabases.refresh(ctx, onStatus)
                            }
                        },
                    ),
                    // One source for the whole database tree (see
                    // PlatformDatabaseSource). Editable because these URLs
                    // ship compiled into the app and raw.githubusercontent
                    // does not reliably redirect after a repository move --
                    // without this, relocating the repo would silently
                    // break updates on every already-installed build.
                    TextInputItem(
                        id = "console_systems_db_source",
                        title = "Platform database source",
                        subtitle = "Web address the platform database is downloaded from; blank restores the default",
                        value = PlatformDatabaseSource.baseUrl(context)
                            .takeIf { it != PlatformDatabaseSource.DEFAULT_BASE_URL }
                            .orEmpty(),
                        onChange = { ctx, value -> PlatformDatabaseSource.setBaseUrl(ctx, value) },
                    ),
                ),
            ),
            CatalogGroup(
                id = "console_systems_folders",
                title = if (deepLinkedRows != null) deepLinkedSystem?.displayName else "System folders",
                items = (if (deepLinkedRows != null) {
                    deepLinkedRows
                } else if (forIndex) {
                    emptyList()
                } else if (classifiedFolders.isEmpty()) {
                    listOf<CatalogItem>(
                        ActionItem(
                            id = "console_systems_no_folders",
                            title = "No console system folders found",
                            subtitle = "Name a folder after its system (snes, psx, ...) inside a Game folder, or choose one below",
                        ),
                    )
                } else {
                    classifiedFolders.map<Pair<File, FolderKind>, CatalogItem> { (folder, kind) ->
                        when (kind) {
                            is FolderKind.PcStore -> {
                                val gameText = if (kind.gameCount == 1) "1 game" else "${kind.gameCount} games"
                                NestedScreenItem(
                                    id = "console_folder_${folder.absolutePath}",
                                    title = folder.name,
                                    subtitle = "${kind.storeName} (PC games: $gameText)",
                                    inline = folderScreen(folder, kind),
                                    valueLabel = { gameText },
                                )
                            }
                            is FolderKind.Engine -> {
                                val gameText = if (kind.gameCount == 1) "1 game" else "${kind.gameCount} games"
                                NestedScreenItem(
                                    id = "console_folder_${folder.absolutePath}",
                                    title = folder.name,
                                    subtitle = "Engine games ($gameText)",
                                    inline = folderScreen(folder, kind),
                                    valueLabel = { gameText },
                                )
                            }
                            is FolderKind.ConsoleSystem -> consoleFolderRow(context, folder, kind)
                        }
                    }
                }) + if (deepLinkedRows == null) {
                    listOf(
                        FolderPickItem(
                            id = "console_systems_choose_folder",
                            title = "Choose a system for another folder",
                            subtitle = "For a folder whose name is not a system's",
                            onPicked = { ctx, uri: Uri ->
                                val picked = GamesRootPrefs.resolveStoragePath(uri)
                                val roots = GamesRootPrefs.gamesRootPaths(ctx).map { it.trimEnd('/') + "/" }
                                when {
                                    picked == null -> "Couldn't get that folder's real path on this device; pick a folder on this device's storage"
                                    roots.none { picked.absolutePath.startsWith(it) } -> "That folder is not inside one of your Game folders; choose a folder inside one"
                                    else -> {
                                        if (SystemOverridePrefs.get(ctx, picked.absolutePath) == null) {
                                            SystemOverridePrefs.set(ctx, picked.absolutePath, SystemOverridePrefs.NOT_SET)
                                        }
                                        null
                                    }
                                }
                            },
                        ),
                    )
                } else {
                    emptyList()
                },
            ),
        )
    }

    /**
     * One console-system folder row of the Console systems screen --
     * shared by the full list and the per-system deep link so both
     * routes show the same row, built the same way. [resolvePlayer] walks
     * every known player and calls the PackageManager per candidate
     * (isPackageInstalled), so it runs ONCE here, on IO (this is called
     * from inside consoleSystemsGroups' own IO block), and never from
     * the valueLabel on the main thread: when this row's valueLabel
     * called it again from inside its own lambda, that same PackageManager
     * walk ran on EVERY recomposition of the row (every scroll frame,
     * every selection change) instead of once per real library change --
     * the settings scrolling jank the owner reported traced to exactly
     * this on Console systems, which can list dozens of these rows at
     * once (settings polish pass, 2026-09-28).
     */
    private fun consoleFolderRow(context: Context, folder: File, kind: FolderKind.ConsoleSystem): CatalogItem {
        val resolved = kind.resolvedSystem
        val player = resolved?.let { resolvePlayer(context, it) }
        return NestedScreenItem(
            id = "console_folder_${folder.absolutePath}",
            title = folder.name,
            subtitle = when {
                resolved == null -> "Not set: open to choose its system"
                player == null -> "${resolved.displayName}: no emulator installed yet"
                else -> resolved.displayName
            },
            inline = folderScreen(folder, kind),
            valueLabel = { player?.name ?: "" },
            accent = resolved?.let { SystemThemeColors.forSystem(context, it.id) },
        )
    }

    private fun folderScreen(folder: File, kind: FolderKind): CatalogScreen = CatalogScreen(
        id = "console_folder_${folder.absolutePath}",
        title = folder.name,
        groups = { context ->
            withContext(Dispatchers.IO) {
                when (kind) {
                    is FolderKind.PcStore -> {
                        val gameText = if (kind.gameCount == 1) "1 game" else "${kind.gameCount} games"
                        buildList {
                            add(
                                CatalogGroup(
                                    id = "folder_pc_store",
                                    title = null,
                                    items = listOf(
                                        ActionItem(
                                            id = "folder_pc_store_info",
                                            title = "${kind.storeName} library",
                                            subtitle = "PC games from ${kind.storeName} are found one by one ($gameText). " +
                                                "They appear under PC on the Games screen.",
                                        ),
                                    ),
                                ),
                            )
                        }
                    }
                    is FolderKind.Engine -> {
                        val gameText = if (kind.gameCount == 1) "1 game" else "${kind.gameCount} games"
                        buildList {
                            add(
                                CatalogGroup(
                                    id = "folder_engine",
                                    title = null,
                                    items = listOf(
                                        ActionItem(
                                            id = "folder_engine_info",
                                            title = "Engine games folder",
                                            subtitle = "Engine games (Ren'Py, RPG Maker, etc.) are found one by one ($gameText). " +
                                                "They appear under PC on the Games screen and launch via Enginehost.",
                                        ),
                                    ),
                                ),
                            )
                        }
                    }
                    is FolderKind.ConsoleSystem -> {
                        val systems = ConsoleSystemsRepository.allSystems(context)
                        val systemsById = systems.associateBy { it.id }
                        val resolved = kind.resolvedSystem
                        buildList {
                            add(
                                CatalogGroup(
                                    id = "folder_system",
                                    title = null,
                                    items = buildList {
                                        add(systemChoiceItem(context, folder, systems))
                                        if (resolved != null) {
                                            add(EmulatorsCatalog.systemPlayerChoiceItem(context, resolved))
                                            // docs/SPEC.md 12a "app_status": where droidtop
                                            // shows an installed app -- here, the emulator
                                            // this system's player choice actually resolved
                                            // to -- an approved app_status plugin managing
                                            // that same package gets a real entry, not just
                                            // its own separate Plugins-screen row. On-demand
                                            // only (this screen's own single open), never
                                            // list rendering.
                                            val chosenPlayer = resolvePlayer(context, resolved)
                                            if (chosenPlayer != null) {
                                                val appStatusPlugins = PluginAppStatus.sourcesFor(context, chosenPlayer.packageName)
                                                appStatusPlugins.forEach { record ->
                                                    add(
                                                        NestedScreenItem(
                                                            id = "folder_player_app_status_${resolved.id}_${record.manifest.id}",
                                                            title = "${chosenPlayer.name}: ${record.manifest.label}",
                                                            subtitle = "Status and actions this plugin offers for ${chosenPlayer.name}",
                                                            inline = PluginAppStatus.screenFor(record),
                                                        ),
                                                    )
                                                }
                                            }
                                            add(
                                                NestedScreenItem(
                                                    id = "folder_emulator_setup_${resolved.id}",
                                                    title = "Emulator setup and test",
                                                    subtitle = "What runs ${resolved.displayName}, what to install, and a launch test",
                                                    inline = EmulatorsCatalog.systemScreen(resolved),
                                                ),
                                            )
                                            add(
                                                ActionItem(
                                                    id = "folder_scrape_${folder.absolutePath}",
                                                    title = "Scrape missing artwork & metadata",
                                                    subtitle = "Fills box art, descriptions, ratings and more for games that lack them. " +
                                                        "Runs as a job under Downloads and installs: pause it there, and it carries on after a restart",
                                                    run = { ctx ->
                                                        LibraryScrapeJob.start(ctx, "Scrape ${resolved.displayName}", resolved.id, folder)
                                                    },
                                                ),
                                            )
                                            add(
                                                AsyncActionItem(
                                                    id = "folder_gamelist_${folder.absolutePath}",
                                                    title = "Import gamelist.xml",
                                                    subtitle = "Bring in another scraper's results (Skraper, Skyscraper, ARRM, ES-DE) " +
                                                        "for this folder: game details go into droidtop, artwork is used from where it sits",
                                                    run = { ctx, _ -> importGamelistXml(ctx, folder) },
                                                ),
                                            )
                                            // EmuDeck-style setup helper: firmware
                                            // check against the real Batocera BIOS
                                            // registry, when this system needs any.
                                            val bios = BiosDatabase.forSystem(context, resolved.id)
                                            if (bios != null) {
                                                // The games folder this system folder sits in,
                                                // which is not always its parent: a system
                                                // folder may be <root>/roms/<system>.
                                                val gamesRoot = GamesRootPrefs.gamesRootPaths(context)
                                                    .map { File(it) }
                                                    .filter { folder.absolutePath.startsWith(it.absolutePath.trimEnd('/') + "/") }
                                                    .maxByOrNull { it.absolutePath.length }
                                                    ?: folder.parentFile ?: folder
                                                // Presence only, counted here on IO (a value
                                                // label is drawn on the main thread); md5
                                                // hashing happens inside the screen.
                                                val biosPresent = bios.files.count { File(gamesRoot, it.file).isFile }
                                                add(
                                                    NestedScreenItem(
                                                        id = "folder_bios_${resolved.id}",
                                                        title = "BIOS files",
                                                        subtitle = "Firmware ${resolved.displayName} emulators may need, looked for in ${gamesRoot.name}/bios",
                                                        inline = biosScreen(gamesRoot, bios),
                                                        valueLabel = { _ -> "$biosPresent/${bios.files.size}" },
                                                    ),
                                                )
                                            }
                                        }
                                    },
                                ),
                            )
                            // EmuDeck-style setup helper: when no installed emulator
                            // can run this system, offer the real known presets'
                            // packages for installation instead of a dead end.
                            if (resolved != null) {
                                val installedPkgs = availablePlayers(context, resolved).map { it.packageName }.toSet()
                                val missing = KnownPlayers.forSystem(context, resolved.id)
                                    .filter { it.pkg !in installedPkgs }
                                    .distinctBy { it.pkg }
                                if (installedPkgs.isEmpty() && missing.isNotEmpty()) {
                                    add(
                                        CatalogGroup(
                                            id = "folder_get_emulator",
                                            title = "Get an emulator",
                                            items = missing.take(8).map { preset ->
                                                ActionItem(
                                                    id = "install_${preset.pkg}",
                                                    title = "Get ${preset.label}",
                                                    subtitle = preset.pkg,
                                                    run = installPackageAction(preset.pkg),
                                                )
                                            },
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )
    // One screen per system's firmware set: every registry file with its
    // real on-disk presence AND md5 verification (catches the classic
    // "right name, wrong dump"), plus the database refresh action.
    private fun biosScreen(gamesRoot: File, spec: SystemBiosSpec) = CatalogScreen(
        id = "bios_${spec.systemId}",
        title = "${spec.name} BIOS files",
        subtitle = "Checked under ${gamesRoot.absolutePath}/bios — md5-verified against Batocera's real registry",
        groups = { context ->
            withContext(Dispatchers.IO) {
                val statuses = BiosDatabase.check(gamesRoot, spec)
                listOf(
                    CatalogGroup(
                        id = "bios_files",
                        title = null,
                        items = statuses.map { status ->
                            ActionItem(
                                id = "bios_${spec.systemId}_${status.spec.file}",
                                title = status.spec.file.removePrefix("bios/"),
                                subtitle = when {
                                    !status.present -> "Missing — place it at ${File(gamesRoot, status.spec.file).absolutePath}"
                                    status.md5Ok == false -> "Present, but the md5 matches no known-good dump"
                                    status.md5Ok == true -> "Present, verified"
                                    else -> "Present (no known hash to verify against)"
                                },
                            )
                        },
                    ),
                ) + listOfNotNull(getBiosGroup(context, gamesRoot, spec))
            }
        },
    )

    // The same "Get the BIOS for this system" row the emulator setup helper shows, here for the games folder's own
    // bios folder: without it the files were only reachable from Game sources (Droidtop/tracker#270).
    private fun getBiosGroup(context: android.content.Context, gamesRoot: File, spec: SystemBiosSpec): CatalogGroup? {
        val folder = File(gamesRoot, "bios").absolutePath
        val reach = dev.droidtop.library.consoles.EmulatorSetup.writeReach("$folder/x")
        val viaHelper = reach == dev.droidtop.library.consoles.EmulatorSetup.Reach.HELPER
        if (reach != dev.droidtop.library.consoles.EmulatorSetup.Reach.DIRECT && !viaHelper) return null
        val row = dev.droidtop.library.integrations.PluginBios.row(
            context, spec.systemId, spec.name, spec,
            // droidtop's own bios folder keeps the database's layout (bios/<system>/<name>).
            dev.droidtop.library.consoles.EmulatorSetupSpec(), folder, "the games folder", viaHelper,
        )
            ?: return null
        return CatalogGroup(id = "bios_get", title = null, items = listOf(row))
    }

    internal fun installPackageAction(pkg: String): (Context) -> Unit = { ctx ->
        val market = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            Uri.parse("market://details?id=$pkg"),
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(market)
        } catch (e: android.content.ActivityNotFoundException) {
            ctx.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$pkg"),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun systemChoiceItem(context: Context, folder: File, systems: List<ConsoleSystemDef>) = ChoiceItem(
        id = "folder_system_${folder.absolutePath}",
        title = "System",
        subtitle = "Which platform this folder's games belong to",
        options = listOf(ChoiceOption("", "From the folder name")) +
            listOfNotNull(
                ChoiceOption(SystemOverridePrefs.NOT_SET, "Not set")
                    .takeIf { SystemOverridePrefs.get(context, folder.absolutePath) == SystemOverridePrefs.NOT_SET },
            ) +
            systems.filter { it.canResolveFromFolder() }.sortedBy { it.displayName.lowercase() }.map { ChoiceOption(it.id, "${it.displayName} (${it.id})") },
        current = SystemOverridePrefs.get(context, folder.absolutePath) ?: "",
        onSelect = { ctx, value ->
            SystemOverridePrefs.set(ctx, folder.absolutePath, value.ifEmpty { null })
        },
    )

    // ------------------------------------------------------------------
    // App integrations (docs/SPEC.md section 12).
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Windows games: creating the Wine environment they run inside.
    // ------------------------------------------------------------------

    /**
     * Sets up the Windows environment Wine games need.
     *
     * This is the screen `DroidtopPcGameRuntime.launchWindows` points at
     * when it finds no container. Before it existed, that error named
     * "Desktop mode > Containers", which had never been built -- so a
     * Windows game could be detected, offered Wine, and then fail with
     * instructions the user could not act on.
     */
    // ------------------------------------------------------------------
    // Android settings, one hop away.
    // ------------------------------------------------------------------

    /**
     * Direct links into every system screen the platform refuses to let
     * an app own, plus droidtop's own special-access grants -- per
     * direction: consume as much of the user's UI needs in-app as
     * possible, and make the Settings app something droidtop LINKS INTO,
     * never something the user has to go spelunking in. The link list is
     * filtered to what actually resolves on this device, so an OEM build
     * missing a screen never produces a dead row.
     */
    // ------------------------------------------------------------------
    // enginehost: an emulator to droidtop, driven through its contract.
    // ------------------------------------------------------------------

    /**
     * enginehost's surface in droidtop, shaped exactly like an
     * emulator's: an installed-runtimes list (the capabilities
     * ContentProvider the contract exposes -- advisory by that
     * contract, so it informs and never gates) and entry points into
     * enginehost's own settings screens (its CONFIGURE_SETTINGS /
     * CONFIGURE_SAVES actions), the same way any player's settings
     * activity would be linked. droidtop never reaches inside; every
     * row here is the published contract.
     */
    /**
     * An Enginehost engine id ("rpgmaker", "cmvs") as the engine's own
     * name, with its context ("2000", "ps2") where the engine has more
     * than one line. An id this does not know is shown with its words
     * capitalised rather than as the raw id.
     */
    private fun engineRuntimeName(engine: String, context: String?): String {
        val base = when (engine.lowercase()) {
            "rpgmaker" -> "RPG Maker"
            "renpy" -> "Ren'Py"
            "kirikiri" -> "KiriKiri"
            "cmvs" -> "CMVS"
            "catsystem2" -> "CatSystem2"
            "buriko" -> "BGI"
            else -> engine.split('-', '_', ' ').filter { it.isNotEmpty() }
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
        }
        val ctx = context?.takeIf { it.isNotBlank() } ?: return base
        return if (ctx.all { it.isDigit() } || ctx.uppercase() in setOf("MV", "MZ", "XP", "VX", "VXACE")) {
            "$base ${ctx.uppercase()}"
        } else {
            "$base (${ctx.uppercase()})"
        }
    }

    private fun enginehostScreen() = CatalogScreen(
        id = SCREEN_ENGINEHOST,
        title = "Enginehost",
        subtitle = "The separate app that runs engine games natively",
        groups = { context ->
            val installed = dev.droidtop.library.EngineHost.isInstalled(context)
            val bundles = if (installed) {
                dev.droidtop.library.EnginehostCapabilities.installedBundles(context)
            } else emptyList()
            listOf(
                CatalogGroup(
                    id = "enginehost_actions",
                    title = null,
                    items = buildList {
                        if (!installed) {
                            add(
                                ActionItem(
                                    id = "enginehost_missing",
                                    title = "Enginehost isn't installed",
                                    subtitle = "Engine games fall back to Wine or a Linux container until it is",
                                ),
                            )
                            return@buildList
                        }
                        add(
                            ActionItem(
                                id = "enginehost_settings",
                                title = "Enginehost settings",
                                subtitle = "Opens Enginehost's own settings",
                                run = { ctx ->
                                    ctx.startActivity(dev.droidtop.library.EngineHost.settingsIntent())
                                },
                            ),
                        )
                        add(
                            ActionItem(
                                id = "enginehost_saves",
                                title = "Save storage",
                                subtitle = "One shared place for engine saves, and moving saves there, in Enginehost's own screen",
                                run = { ctx ->
                                    ctx.startActivity(dev.droidtop.library.EngineHost.savesSettingsIntent())
                                },
                            ),
                        )
                    },
                ),
                CatalogGroup(
                    id = "enginehost_bundles",
                    title = "Installed engine runtimes",
                    items = if (!installed) {
                        emptyList()
                    } else if (bundles.isEmpty()) {
                        listOf(
                            ActionItem(
                                id = "enginehost_no_bundles",
                                title = "No runtime bundles installed yet",
                                subtitle = "An engine game offers its bundle at launch, installed unasked when droidtop is sure which",
                            ),
                        )
                    } else {
                        // The engine's own name with the version it runs
                        // in the value column; the bundle's package id,
                        // plugin version and source address are on its
                        // Details page, not in the row (UI pass
                        // 2026-09-24, M5: package ids and URLs in the list).
                        bundles.map { bundle ->
                            val name = engineRuntimeName(bundle.engine, bundle.engineContext)
                            NestedScreenItem(
                                id = "enginehost_bundle_${bundle.bundleId}",
                                title = name,
                                subtitle = bundle.supportedSeries.takeIf { it.isNotEmpty() }
                                    ?.let { "Runs versions ${it.joinToString(", ") { series -> "$series.x" }}" },
                                valueLabel = { bundle.runtimeVersion },
                                inline = CatalogScreen(
                                    id = "enginehost_bundle_detail_${bundle.bundleId}",
                                    title = name,
                                    groups = {
                                        listOf(
                                            CatalogGroup(
                                                id = "enginehost_bundle_details",
                                                title = "Details",
                                                items = listOfNotNull(
                                                    ActionItem(id = "bundle_id", title = "Bundle", value = bundle.bundleId),
                                                    bundle.runtimeVersion?.let {
                                                        ActionItem(id = "bundle_runtime", title = "Engine version", value = it)
                                                    },
                                                    bundle.pluginVersion?.let {
                                                        ActionItem(id = "bundle_plugin", title = "Plugin version", value = it)
                                                    },
                                                    bundle.origin?.let {
                                                        ActionItem(id = "bundle_origin", title = "Source", subtitle = it)
                                                    },
                                                ),
                                            ),
                                        )
                                    },
                                ),
                            )
                        }
                    },
                ),
            )
        },
    )

    private fun androidSettingsScreen() = CatalogScreen(
        id = SCREEN_ANDROID_SETTINGS,
        title = "Android settings",
        subtitle = "Direct links to every reachable system screen, and droidtop's own permission grants",
        groups = { context ->
            val controls = dev.droidtop.runtime.systemstatus.SystemControls
            listOf(
                CatalogGroup(
                    id = "droidtop_grants",
                    title = "droidtop's access",
                    items = listOf(
                        ActionItem(
                            id = "grant_app_details",
                            title = "droidtop's app info",
                            subtitle = "Permissions, storage, notifications for droidtop itself",
                            run = { ctx -> SettingsLaunch.start(ctx, controls.appDetailsIntent(ctx)) },
                        ),
                        ActionItem(
                            id = "grant_write_settings",
                            title = "Modify system settings",
                            // State in the value column, what it is for in
                            // the subtitle (UI pass 2026-09-24, M14).
                            subtitle = "Lets droidtop change brightness, screen timeout and auto-rotate",
                            value = if (controls.canWriteBrightness(context)) "Granted" else "Not granted",
                            run = { ctx -> SettingsLaunch.start(ctx, controls.brightnessGrantIntent(ctx)) },
                        ),
                        ActionItem(
                            id = "grant_dnd",
                            title = "Do Not Disturb access",
                            subtitle = "Lets droidtop turn Do Not Disturb on and off",
                            value = if (controls.hasDndAccess(context)) "Granted" else "Not granted",
                            run = { ctx -> SettingsLaunch.start(ctx, controls.dndGrantIntent()) },
                        ),
                    ),
                ),
                CatalogGroup(
                    id = "android_links",
                    title = "System screens",
                    items = controls.settingsLinks(context).map { link ->
                        ActionItem(
                            id = "link_${link.id}",
                            title = link.label,
                            run = { ctx -> SettingsLaunch.start(ctx, link.intent) },
                        )
                    },
                ),
            )
        },
    )

    private fun windowsGamesScreen() = CatalogScreen(
        id = SCREEN_WINDOWS_GAMES,
        title = "Windows games",
        subtitle = "Where Windows games run: the Wine build, graphics and the folders it can reach",
        groups = { context -> windowsGamesGroups(context) },
        // The Wine option rows read the installed components and droidtop's
        // component catalog (a network fetch once a day); search does not wait
        // on that.
        indexGroups = { context -> windowsGamesGroups(context, wineOptions = false) },
    )

    private suspend fun windowsGamesGroups(context: Context, wineOptions: Boolean = true): List<CatalogGroup> {
        val runtime = dev.droidtop.library.PcGameRuntimeRegistry.runtime
        val roots = withContext(Dispatchers.IO) { GamesRootPrefs.gamesRootPaths(context).sorted() }
        val provisioned = withContext(Dispatchers.IO) { runtime?.isProvisioned == true }
        // The state launch changes too: Not set up, Installing N%, Ready, or why it failed.
        val setupState = withContext(Dispatchers.IO) { dev.droidtop.library.WindowsSetup.current(context) }

        return listOf(
            CatalogGroup(
                id = "windows_setup",
                title = null,
                items = buildList {
                    if (runtime == null) {
                        add(
                            ActionItem(
                                id = "windows_unavailable",
                                title = "Windows support isn't loaded",
                                subtitle = "This build cannot run Windows games, so there is nothing to set up",
                            ),
                        )
                        return@buildList
                    }
                    // The row says where setup is (its value) and asks once before
                    // the long download or reinstall starts (Droidtop/tracker#299);
                    // the same WindowsSetup path launch and the game page use.
                    add(windowsSetupItem(provisioned, setupState))
                    // Wine's process tree counts against Android's limit on child processes (Droidtop/tracker#816).
                    PhantomProcessRow.item(context, "windows_child_processes")?.let(::add)
                    PhantomBreakdownRow.item(context, "windows_child_processes_what")?.let(::add)
                    // A new game from an installer, in a prefix of its own (docs/SPEC.md 7c).
                    if (provisioned) {
                        add(
                            NestedScreenItem(
                                id = "windows_install_row",
                                title = "Install a Windows game",
                                subtitle = "Run a GOG offline installer or any setup program, then add what it installed to your games",
                                registryId = WindowsInstallCatalog.SCREEN_ID,
                            ),
                        )
                    }
                },
            ),
        ) + (
            // The shared environment's Wine build, emulation, graphics
            // driver and Direct3D: the default every game without settings
            // of its own runs with (docs/SPEC.md 5a). Before setup they are
            // the device's defaults, and the choices are setup's
            // (Droidtop/tracker#372).
            if (wineOptions && runtime != null) WineOptionsCatalog.groups(context, entryId = null, title = null) else emptyList()
        ) + listOf(
            CatalogGroup(
                id = "windows_drives",
                title = "Folders Wine can reach",
                items = if (roots.isEmpty()) {
                    listOf(
                        ActionItem(
                            id = "windows_no_roots",
                            title = "No game folders added yet",
                            subtitle = "Add one under Game folders first: Windows games need a folder the environment can see",
                        ),
                    )
                } else {
                    // The SAME assignment provision writes -- one rule in
                    // EnvironmentMounts.wineDrives, so this preview cannot
                    // drift from what a game actually sees.
                    dev.droidtop.runtime.EnvironmentMounts.ofFolders(roots).wineDrives().map { (letter, path) ->
                        ActionItem(
                            id = "windows_drive_$letter",
                            title = "$letter:  $path",
                            subtitle = if (provisioned) {
                                "Mapped when the environment was set up"
                            } else {
                                "Will be mapped when you set up the environment"
                            },
                        )
                    }
                },
            ),
        )
    }

    /**
     * The ONE settings area for every account and source droidtop has --
     * store sign-ins, scraper credentials, plugin code and the app
     * integrations and keys that go with it (docs/SPEC.md settings
     * architecture). Per direction: users see games and systems; a
     * source (store, plugin, scraper, site) is a detail on a game and a
     * filter, never its own screen. Before this pass a store's sign-in
     * lived on "Stores and folders", a scraper's credentials lived under
     * the Scraper screen split into one group per provider, and Plugins/
     * App integrations/Jobs were buried three levels deep under Console
     * systems (which is about ROM systems, not sources) -- four
     * mechanisms doing the same job of "manage where droidtop gets
     * something from". This is the one.
     */
    private fun accountsAndSourcesScreen() = CatalogScreen(
        id = SCREEN_ACCOUNTS_AND_SOURCES,
        title = "Accounts and sources",
        subtitle = "Every account, scraper, plugin and integration droidtop uses, with its status and actions",
        groups = { context -> accountsAndSourcesGroups(context) },
    )

    private suspend fun accountsAndSourcesGroups(context: Context): List<CatalogGroup> = withContext(Dispatchers.IO) {
        val signedInStores = PcStore.entries.count { it.signedIn(context) }
        val pairedComputers = dev.droidtop.net.peer.Computers.list(context).size

        val activeIntegrations = IntegrationStore.available(context).size
        val installedPlugins = PluginStore.installed(context)
        val pluginsValueLabel = when {
            installedPlugins.isEmpty() -> "none"
            installedPlugins.any { it.trust == PluginTrustState.PENDING } ->
                "${installedPlugins.count { it.trust == PluginTrustState.PENDING }} awaiting approval"
            else -> {
                // A plugin whose runtime is missing is approved but cannot run: it is "needs setup", never "active".
                val runnable = installedPlugins.filter { it.runnable() }
                val needSetup = runnable.count { PluginRuntimeNeeds.missing(context, it.manifest) != null }
                val active = runnable.size - needSetup
                when {
                    needSetup == 0 -> "$active active"
                    active == 0 -> "$needSetup need setup"
                    else -> "$active active, $needSetup need setup"
                }
            }
        }

        listOfNotNull(
            CatalogGroup(
                id = "accounts_stores",
                title = "Accounts",
                items = listOf(
                    GitHubAccountCatalog.accountRow(context),
                    // Each store's sign-in, library and sync live on its own page in Stores (docs/SPEC.md 7j "Places").
                    NestedScreenItem(
                        id = "accounts_stores_link",
                        title = "Game sources",
                        subtitle = "Steam, GOG, Epic Games, Amazon Games and itch.io: sign in or out, library, sync",
                        registryId = StoresCatalog.SCREEN_ID,
                        valueLabel = { "$signedInStores of ${PcStore.entries.size} signed in" },
                        icon = CatalogIcon.GLOBAL,
                    ),
                    // The person's computers running droidtop-agent (docs/SPEC.md 7o).
                    ComputersCatalog.linkRow(pairedComputers),
                ),
            ),
            CatalogGroup(
                id = "accounts_scrapers",
                title = "Scraper sources",
                items = listOf(
                    guidedKeyRow(context, ScraperKeyService.SCREENSCRAPER, "ScreenScraper"),
                    guidedKeyRow(context, ScraperKeyService.THEGAMESDB, "TheGamesDB"),
                    guidedKeyRow(context, ScraperKeyService.IGDB, "IGDB (PC & engine games)"),
                    guidedKeyRow(context, ScraperKeyService.STEAMGRIDDB, "SteamGridDB (PC & engine games)"),
                ),
            ),
            CatalogGroup(
                id = "accounts_achievements",
                title = "Achievements and game info",
                items = listOf(
                    guidedKeyRow(context, ScraperKeyService.RETROACHIEVEMENTS, "RetroAchievements"),
                    ToggleItem(
                        id = "accounts_game_info",
                        title = "Play times and compatibility",
                        subtitle = "When a game's page opens, asks HowLongToBeat how long it takes and the emulator's public " +
                            "compatibility list how it runs. Sends the game's title to HowLongToBeat; a list is downloaded",
                        current = GameInfoPrefs.lookupsOn(context),
                        onToggle = { ctx, value -> GameInfoPrefs.setLookups(ctx, value) },
                    ),
                ),
            ),
            CatalogGroup(
                id = "accounts_debrid",
                title = "Debrid services",
                items = debridAccountRows(context) + listOf(
                    ToggleItem(
                        id = "accounts_debrid_spread",
                        title = "Spread mode",
                        subtitle = "When two accounts would cost the same, use the one fetching the fewest torrents for " +
                            "droidtop instead of the first in your order, so one account's slots do not fill while another's sit idle",
                        current = dev.droidtop.library.debrid.DebridAccounts.spread(context),
                        onToggle = { ctx, value -> dev.droidtop.library.debrid.DebridAccounts.setSpread(ctx, value) },
                    ),
                    ToggleItem(
                        id = "accounts_debrid_wait",
                        title = "Wait for the service",
                        subtitle = "When no account has a torrent yet, wait while the service fetches it instead of " +
                            "downloading it from other people. A torrent an account has is always fetched from it",
                        current = dev.droidtop.library.debrid.DebridAccounts.waits(context),
                        onToggle = { ctx, value -> dev.droidtop.library.debrid.DebridAccounts.setWaits(ctx, value) },
                    ),
                ),
            ),
            CatalogGroup(
                id = "accounts_hosts",
                title = "File hosts",
                items = listOf(
                    guidedKeyRow(context, ScraperKeyService.PIXELDRAIN, "PixelDrain"),
                ),
            ),
            // droidtop's own sign-ins for download sites (docs/SPEC.md 12a "Download sites", Droidtop/tracker#648).
            CatalogGroup(
                id = "accounts_download_sites",
                title = "Download sites",
                items = downloadSiteRows(context) + addDownloadSiteRow(),
            ),
            CatalogGroup(
                id = "accounts_plugins",
                title = "Plugins and integrations",
                items = ElevatedAccessCatalog.rows(context) + listOf(
                    NestedScreenItem(
                        id = "accounts_plugins_screen",
                        title = "Plugins",
                        subtitle = "Extra features you install and approve, kept out of droidtop's own data",
                        registryId = SCREEN_PLUGINS,
                        valueLabel = { pluginsValueLabel },
                    ),
                    NestedScreenItem(
                        id = "accounts_integrations_screen",
                        title = "App integrations",
                        subtitle = "Hook other installed apps into droidtop, e.g. a downloader for a system's games",
                        registryId = SCREEN_INTEGRATIONS,
                        valueLabel = { if (activeIntegrations == 0) "none" else "$activeIntegrations active" },
                        icon = CatalogIcon.INTEGRATIONS,
                    ),
                ),
            ),
            // What droidtop can set on Android itself, through the elevated access above (Droidtop/tracker#816).
            DeviceSetupRows.group(context),
        )
    }

    /** What Add site's address field holds until Add is pressed. */
    @Volatile private var pendingSiteAddress = ""

    /**
     * One row per download site (docs/SPEC.md 12a "Download sites"), as a plugin's account row behaves: a shipped site
     * signed out signs in at once; signed in, or an added site, opens its page with Sign out, and Remove for an added one.
     * Disk: call off the main thread.
     */
    private fun downloadSiteRows(context: Context): List<CatalogItem> = dev.droidtop.pluginhost.DownloadSites.all(context).map { site ->
        val sites = dev.droidtop.pluginhost.DownloadSites
        val id = "accounts_download_site_" + site.domain.replace(Regex("[^a-z0-9]"), "_")
        // Expired: the site refused the session droidtop sent with a download (#648 slice 4); signing in again clears it.
        val state = when {
            !sites.signedIn(context, site) -> "Not signed in"
            sites.expired(context, site) -> "Expired"
            else -> "Signed in"
        }
        val subtitle = "Your sign-in stays in droidtop, and plugins never see it"
        // The state drawn beside its words (show, then say: Droidtop/tracker#738); an expired sign-in waits for the person.
        val mark = when (state) {
            "Signed in" -> dev.droidtop.library.settings.StatusMark.SIGNED_IN
            "Expired" -> dev.droidtop.library.settings.StatusMark.ATTENTION
            else -> dev.droidtop.library.settings.StatusMark.SIGNED_OUT
        }
        if (site.shipped && state != "Signed in") {
            return@map AsyncActionItem(id = id, title = site.name, subtitle = subtitle, value = state, statusMark = mark, run = { ctx, _ -> signInToSite(ctx, site) })
        }
        NestedScreenItem(
            id = id,
            title = site.name,
            subtitle = subtitle,
            valueLabel = { state },
            statusMark = mark,
            inline = CatalogScreen(
                id = "${id}_screen",
                title = site.name,
                subtitle = site.domain,
                groups = { ctx ->
                    val signedIn = withContext(Dispatchers.IO) { sites.signedIn(ctx, site) }
                    val expired = signedIn && withContext(Dispatchers.IO) { sites.expired(ctx, site) }
                    listOf(
                        CatalogGroup(
                            id = "${id}_actions",
                            title = null,
                            items = buildList {
                                if (!signedIn || expired) {
                                    add(
                                        AsyncActionItem(
                                            id = "${id}_sign_in",
                                            title = if (expired) "Sign in again" else "Sign in",
                                            value = if (expired) "Expired" else "Not signed in",
                                            run = { c, _ -> signInToSite(c, site) },
                                        ),
                                    )
                                }
                                if (signedIn) {
                                    add(
                                        AsyncActionItem(
                                            id = "${id}_sign_out",
                                            title = "Sign out",
                                            value = "Signed in",
                                            confirmTitle = "Sign out of ${site.name}?",
                                            run = { c, _ -> if (withContext(Dispatchers.IO) { sites.signOut(c, site) }) "Signed out of ${site.name}" else "Already signed out" },
                                        ),
                                    )
                                }
                                if (!site.shipped) {
                                    add(
                                        AsyncActionItem(
                                            id = "${id}_remove",
                                            title = "Remove",
                                            subtitle = "Takes ${site.name} off the list and signs out of it",
                                            confirmTitle = "Remove ${site.name}?",
                                            run = { c, _ -> withContext(Dispatchers.IO) { sites.remove(c, site) }; "Removed ${site.name}" },
                                        ),
                                    )
                                }
                            },
                        ),
                    )
                },
            ),
        )
    }

    /** Signs in to a download site in droidtop's web view; waits for the view to close, so never on the main thread. */
    private suspend fun signInToSite(ctx: Context, site: dev.droidtop.pluginhost.DownloadSites.Site): String =
        if (withContext(Dispatchers.IO) { dev.droidtop.pluginhost.DownloadSites.signIn(ctx, site) }) "Signed in to ${site.name}" else "Not signed in to ${site.name}"

    /** The list's last row: Add site asks for the site's https address (its sign-in page) and adds it. */
    private fun addDownloadSiteRow(): CatalogItem = NestedScreenItem(
        id = "accounts_download_sites_add",
        title = "Add site",
        subtitle = "Another download site to sign in to once, by its address",
        inline = CatalogScreen(
            id = "accounts_download_sites_add_screen",
            title = "Add site",
            groups = {
                listOf(
                    CatalogGroup(
                        id = "accounts_download_sites_add_group",
                        title = null,
                        items = listOf(
                            TextInputItem(
                                id = "accounts_download_sites_add_address",
                                title = "Site address",
                                subtitle = "Its https address, best its sign-in page, such as https://example.com/login",
                                value = pendingSiteAddress,
                                onChange = { _, v -> pendingSiteAddress = v.trim() },
                            ),
                            AsyncActionItem(
                                id = "accounts_download_sites_add_run",
                                title = "Add",
                                run = { ctx, _ ->
                                    when (val added = withContext(Dispatchers.IO) { dev.droidtop.pluginhost.DownloadSites.add(ctx, pendingSiteAddress) }) {
                                        is dev.droidtop.pluginhost.DownloadSites.Added.Site -> {
                                            pendingSiteAddress = ""
                                            "Added ${added.site.name}. Open it under Download sites to sign in"
                                        }
                                        is dev.droidtop.pluginhost.DownloadSites.Added.Refused -> added.reason
                                    }
                                },
                            ),
                        ),
                    ),
                )
            },
        ),
    )

    /**
     * An optional source that needs the person's own credential: one row,
     * its state ("Not set", "Not tested", "Connected") as the value, and the
     * numbered guide, QR code and fields behind it (ScraperKeySetupActivity).
     */
    /**
     * Each debrid account in the person's order (Droidtop/tracker#638): its key row, whether it is used for downloads,
     * and moving it in the order, which settles a tie between accounts a job costs the same (the cheapest serves first).
     */
    private fun debridAccountRows(context: Context): List<CatalogItem> {
        val accounts = dev.droidtop.library.debrid.DebridAccounts
        val services = mapOf("torbox" to (ScraperKeyService.TORBOX to "TorBox"), "realdebrid" to (ScraperKeyService.REALDEBRID to "Real-Debrid"))
        val order = accounts.order(context)
        return order.withIndex().flatMap { (index, id) ->
            val (service, title) = services[id] ?: return@flatMap emptyList()
            buildList<CatalogItem> {
                add(guidedKeyRow(context, service, title))
                if (service.isConfigured(context)) {
                    add(
                        ToggleItem(
                            id = "accounts_debrid_used_$id",
                            title = "Use $title for downloads",
                            subtitle = "Off keeps it signed in but unused",
                            current = accounts.used(context, id),
                            onToggle = { ctx, value -> accounts.setUsed(ctx, id, value) },
                        ),
                    )
                }
                if (index > 0) add(ActionItem(id = "accounts_debrid_up_$id", title = "Move $title up", subtitle = "Asked before ${services[order[index - 1]]?.second} when they cost the same", run = { ctx -> accounts.move(ctx, id, earlier = true) }))
            }
        }
    }

    private fun guidedKeyRow(context: Context, service: ScraperKeyService, title: String) = ActionItem(
        id = "accounts_" + service.name.lowercase(),
        title = title,
        value = ScraperKeyCheck.state(context, service),
        icon = CatalogIcon.GLOBAL,
        run = { ctx -> ctx.startActivity(ScraperKeySetupActivity.intent(ctx, service).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
    )

    private fun integrationsScreen() = CatalogScreen(
        id = SCREEN_INTEGRATIONS,
        title = "App integrations",
        subtitle = "Declared as .json files you add, never bundled or synced: which apps you hook in is yours",
        groups = { context ->
            val declared = withContext(Dispatchers.IO) {
                IntegrationStore.seedExampleIfEmpty(context)
                IntegrationStore.all(context).map { it to IntegrationStore.isInstalled(context, it.packageName) }
            }
            val folderLabel = IntegrationStore.userDirLabel(context)
            listOf(
                CatalogGroup(
                    id = "integrations_list",
                    title = null,
                    items = buildList {
                        if (declared.isEmpty()) {
                            add(
                                ActionItem(
                                    id = "integrations_none",
                                    title = "No integrations yet",
                                    subtitle = "Add an integration file below, or copy one into $folderLabel " +
                                        "over USB or a file manager — example.json.txt there shows the format",
                                ),
                            )
                        }
                        declared.forEach { (integration, installed) ->
                            add(
                                ActionItem(
                                    id = "integration_${integration.id}",
                                    title = integration.label,
                                    subtitle = buildString {
                                        append(integration.capability.display)
                                        append(" on ")
                                        append(integration.capability.surface)
                                        append(" - ")
                                        append(if (installed) integration.packageName else "${integration.packageName} is NOT installed, so it is hidden everywhere but here")
                                        IntegrationPlaceholders.usedIn(integration.argumentsTemplate)
                                            .takeIf { it.isNotEmpty() }
                                            ?.let { append("  |  uses ").append(it.joinToString(" ")) }
                                    },
                                ),
                            )
                        }
                        add(
                            DocumentPickItem(
                                id = "integrations_add",
                                title = "Add integration file",
                                subtitle = "Pick a .json integration; it is copied into $folderLabel",
                                // Not application/json: many file managers
                                // label a .json as octet-stream, which a
                                // narrower filter would hide. The file is
                                // parsed before it is kept.
                                mimeType = "*/*",
                                onPicked = IntegrationStore::import,
                            ),
                        )
                    },
                ),
            )
        },
    )

    /** True on a debuggable build -- avoids needing android.buildFeatures.buildConfig just for the debug-only test actions. */
    internal fun ctxIsDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Accounts and sources > Plugins > one plugin > Permissions (docs/plugin-api.md 4.4): every declared permission with its state and when
     * the audit log last saw it used. A change is written at once and takes effect on the plugin's next call.
     */
    /**
     * Accounts and sources > Plugins > Apps that start games (Droidtop/tracker#124, docs/SPEC.md 7 "Other apps that start
     * games"): every app that asked, with its answer as the same three chips a plugin's permission has. An app is listed
     * once it asked; nothing here can add one.
     */
    internal fun externalLaunchScreen() = CatalogScreen(
        id = "plugins_external_launch_screen",
        title = "Apps that start games",
        subtitle = "An app asks the first time; a change applies to its next request",
        groups = { context ->
            withContext(Dispatchers.IO) {
                val callers = dev.droidtop.app.ExternalGameLaunch.callers(context)
                val rows: List<CatalogItem> = callers.map { pkg ->
                    val state = dev.droidtop.app.ExternalGameLaunch.stateOf(context, pkg) ?: GrantState.ASK
                    dev.droidtop.library.settings.ChoiceItem(
                        id = "plugins_external_launch_$pkg",
                        title = dev.droidtop.app.ExternalGameLaunch.label(context, pkg),
                        subtitle = pkg,
                        options = listOf(
                            dev.droidtop.library.settings.ChoiceOption(GrantState.GRANTED.name, "Allowed"),
                            dev.droidtop.library.settings.ChoiceOption(GrantState.ASK.name, "Ask first", dev.droidtop.library.settings.NoticeTone.Caution),
                            dev.droidtop.library.settings.ChoiceOption(GrantState.DENIED.name, "Blocked", dev.droidtop.library.settings.NoticeTone.Danger),
                        ),
                        current = state.name,
                        onSelect = { ctx, value ->
                            GrantState.fromId(value)?.let { chosen ->
                                noticeScope.launch { withContext(Dispatchers.IO) { dev.droidtop.app.ExternalGameLaunch.set(ctx, pkg, chosen) } }
                            }
                        },
                    )
                }
                listOf(
                    CatalogGroup(
                        id = "plugins_external_launch_apps",
                        title = null,
                        items = rows.ifEmpty {
                            listOf(ActionItem(id = "plugins_external_launch_none", title = "No app has asked yet"))
                        },
                    ),
                )
            }
        },
        live = PluginGrants.changes,
    )

    /** Where a plugin's extension point shows, in plain words, or null when it has no particular place (nothing is shown rather than a placeholder). */
    internal fun placementWords(mode: String): String? = when (mode) {
        PluginConsent.MODE_ANY -> null
        "Gaming" -> "In Gaming"
        "Mobile" -> "In Mobile"
        "Desktop" -> "In Desktop"
        else -> mode
    }

    private fun scraperScreen() = CatalogScreen(
        id = SCREEN_SCRAPER,
        // The name the Settings row that opens it uses, which is ES-DE's
        // own name for its scraper menu (UI pass 2026-09-24, M8).
        title = "Scraper",
        // The ScreenScraper sentence used to say droidtop had no developer ID,
        // long after one was registered (ScreenScraperDevCredentials), so the
        // page argued against its own default (UI pass 2026-09-24, finding H7).
        subtitle = "One source at a time, like real ES-DE. ScreenScraper and the libretro database " +
            "work without an account; TheGamesDB needs a free API key, and your own ScreenScraper " +
            "login raises how much you can scrape per day",
        groups = { context ->
            listOf(
                CatalogGroup(
                    id = "scraper_source",
                    title = null,
                    items = listOf(
                        ChoiceItem(
                            id = "scraper_source_choice",
                            title = "Scraper source",
                            options = listOf(
                                ChoiceOption(ScraperSource.SCREENSCRAPER.name, "ScreenScraper (ES-DE's default)"),
                                ChoiceOption(ScraperSource.THEGAMESDB.name, "TheGamesDB"),
                                ChoiceOption(
                                    ScraperSource.LIBRETRO.name,
                                    "libretro database (no account; genre/developer/year, boxart, no descriptions)",
                                ),
                            ),
                            current = ScraperSourcePrefs.get(context).name,
                            onSelect = { ctx, value -> ScraperSourcePrefs.set(ctx, ScraperSource.valueOf(value)) },
                        ),
                        // PC and engine games are scraped from a
                        // different set of sources than console ROMs,
                        // because none of the ROM scrapers indexes "a
                        // Ren'Py build in a folder" at all -- same
                        // one-source-at-a-time model, different list.
                        ChoiceItem(
                            id = "pc_scraper_source_choice",
                            title = "PC & engine game source",
                            subtitle = "Searched by name. Once a game is found, IGDB, the Steam store and SteamGridDB " +
                                "add what they hold for that same game when they are set up",
                            // A metadata plugin is a choice beside the built-in sources, asked alone like them
                            // (Droidtop/tracker#67).
                            options = dev.droidtop.library.scraper.PcScraperSource.entries.map {
                                ChoiceOption(it.name, it.label)
                            } + dev.droidtop.library.integrations.PluginMetadataSources.sources(context).map {
                                ChoiceOption(dev.droidtop.library.scraper.PcScraperSourcePrefs.PLUGIN_PREFIX + it.record.manifest.id, "${it.label} (plugin)")
                            },
                            current = dev.droidtop.library.scraper.PcScraperSourcePrefs.plugin(context)
                                ?.let { dev.droidtop.library.scraper.PcScraperSourcePrefs.PLUGIN_PREFIX + it }
                                ?: dev.droidtop.library.scraper.PcScraperSourcePrefs.get(context).name,
                            onSelect = { ctx, value ->
                                val prefix = dev.droidtop.library.scraper.PcScraperSourcePrefs.PLUGIN_PREFIX
                                if (value.startsWith(prefix)) {
                                    dev.droidtop.library.scraper.PcScraperSourcePrefs.setPlugin(ctx, value.removePrefix(prefix))
                                } else {
                                    dev.droidtop.library.scraper.PcScraperSourcePrefs.set(
                                        ctx,
                                        dev.droidtop.library.scraper.PcScraperSource.valueOf(value),
                                    )
                                }
                            },
                        ),
                    ),
                ),
                CatalogGroup(
                    // The library-wide runs, as jobs under Downloads and installs (docs/SPEC.md 7h,
                    // "The whole library"), honouring the "Scrape these games" filter below.
                    id = "scraper_run",
                    title = "Scrape now",
                    items = listOf(
                        ActionItem(
                            id = "scrape_run_all",
                            title = "Scrape the whole library",
                            subtitle = "Console games, then PC and engine games, with the sources chosen above",
                            run = { ctx -> LibraryScrapeJob.start(ctx, "Scrape the whole library") },
                        ),
                        ActionItem(
                            id = "scrape_run_pc",
                            title = "Scrape PC and engine games",
                            run = { ctx -> LibraryScrapeJob.start(ctx, "Scrape PC and engine games", pcOnly = true) },
                        ),
                    ),
                ),
                CatalogGroup(
                    id = "scraper_options",
                    title = "Scrape options",
                    items = listOf(
                        ChoiceItem(
                            id = "scrape_filter",
                            title = "Scrape these games",
                            options = dev.droidtop.library.scraper.ScrapeFilter.entries.map {
                                ChoiceOption(it.name, it.label)
                            },
                            current = dev.droidtop.library.scraper.ScrapeOptionsPrefs.filter(context).name,
                            onSelect = { ctx, value ->
                                dev.droidtop.library.scraper.ScrapeOptionsPrefs.setFilter(
                                    ctx,
                                    dev.droidtop.library.scraper.ScrapeFilter.valueOf(value),
                                )
                            },
                        ),
                        ChoiceItem(
                            id = "scrape_offer",
                            title = "After the first library scan",
                            subtitle = "What droidtop does about fetching box art and details when your games have been scanned",
                            options = dev.droidtop.library.scraper.ScrapeOffer.Answer.entries.map { ChoiceOption(it.id, it.label) },
                            current = dev.droidtop.library.scraper.ScrapeOffer.current(context).id,
                            onSelect = { ctx, value ->
                                dev.droidtop.library.scraper.ScrapeOffer.Answer.entries.firstOrNull { it.id == value }
                                    ?.let { dev.droidtop.library.scraper.ScrapeOffer.set(ctx, it) }
                            },
                        ),
                        // PC and engine games get their details by themselves (Droidtop/tracker#568), on by default.
                        ToggleItem(
                            id = "scrape_pc_auto",
                            title = "Get details for PC games automatically",
                            subtitle = "After start and each store sync, for PC and engine games missing art. No account needed",
                            current = dev.droidtop.library.scraper.PcAutoScrape.enabled(context),
                            onToggle = { ctx, on -> dev.droidtop.library.scraper.PcAutoScrape.setEnabled(ctx, on) },
                        ),
                        ToggleItem(
                            id = "scrape_content_metadata",
                            title = "Fetch game details",
                            subtitle = "Descriptions, developer, publisher, genre, release date, rating, players",
                            current = dev.droidtop.library.scraper.ScrapeOptionsPrefs.scrapeMetadata(context),
                            onToggle = { ctx, value -> dev.droidtop.library.scraper.ScrapeOptionsPrefs.setScrapeMetadata(ctx, value) },
                        ),
                        ToggleItem(
                            id = "scrape_content_artwork",
                            title = "Fetch box art",
                            subtitle = "Cover images, including from the libretro database, which needs no account",
                            current = dev.droidtop.library.scraper.ScrapeOptionsPrefs.scrapeArtwork(context),
                            onToggle = { ctx, value -> dev.droidtop.library.scraper.ScrapeOptionsPrefs.setScrapeArtwork(ctx, value) },
                        ),
                        ToggleItem(
                            id = "scrape_content_screenshots",
                            title = "Fetch screenshots",
                            current = dev.droidtop.library.scraper.ScrapeOptionsPrefs.scrapeScreenshots(context),
                            onToggle = { ctx, value -> dev.droidtop.library.scraper.ScrapeOptionsPrefs.setScrapeScreenshots(ctx, value) },
                        ),
                        ToggleItem(
                            id = "scrape_content_titlescreens",
                            title = "Fetch title screens",
                            current = dev.droidtop.library.scraper.ScrapeOptionsPrefs.scrapeTitleScreens(context),
                            onToggle = { ctx, value -> dev.droidtop.library.scraper.ScrapeOptionsPrefs.setScrapeTitleScreens(ctx, value) },
                        ),
                        ToggleItem(
                            id = "scrape_content_marquees",
                            title = "Fetch marquees (wheel logos)",
                            current = dev.droidtop.library.scraper.ScrapeOptionsPrefs.scrapeMarquees(context),
                            onToggle = { ctx, value -> dev.droidtop.library.scraper.ScrapeOptionsPrefs.setScrapeMarquees(ctx, value) },
                        ),
                        ToggleItem(
                            id = "scrape_content_physicalmedia",
                            title = "Fetch physical media images",
                            current = dev.droidtop.library.scraper.ScrapeOptionsPrefs.scrapePhysicalMedia(context),
                            onToggle = { ctx, value -> dev.droidtop.library.scraper.ScrapeOptionsPrefs.setScrapePhysicalMedia(ctx, value) },
                        ),
                        ToggleItem(
                            id = "scrape_content_fanart",
                            title = "Fetch fan art",
                            current = dev.droidtop.library.scraper.ScrapeOptionsPrefs.scrapeFanArt(context),
                            onToggle = { ctx, value -> dev.droidtop.library.scraper.ScrapeOptionsPrefs.setScrapeFanArt(ctx, value) },
                        ),
                        ToggleItem(
                            id = "scrape_content_videos",
                            title = "Fetch videos",
                            subtitle = "Game preview videos; the largest downloads by far",
                            current = dev.droidtop.library.scraper.ScrapeOptionsPrefs.scrapeVideos(context),
                            onToggle = { ctx, value -> dev.droidtop.library.scraper.ScrapeOptionsPrefs.setScrapeVideos(ctx, value) },
                        ),
                        ToggleItem(
                            id = "scrape_content_miximages",
                            title = "Generate miximages",
                            subtitle = "Composes the screenshot, box, and marquee into the hero image most themes display",
                            current = dev.droidtop.library.scraper.ScrapeOptionsPrefs.generateMiximages(context),
                            onToggle = { ctx, value -> dev.droidtop.library.scraper.ScrapeOptionsPrefs.setGenerateMiximages(ctx, value) },
                        ),
                        ToggleItem(
                            id = "scrape_miximage_rotate_boxes",
                            title = "Rotate horizontal boxes in miximages",
                            subtitle = "Turns a box wider than it is tall on its side, so it takes the space of an upright one",
                            current = dev.droidtop.library.scraper.ScrapeOptionsPrefs.miximageRotateHorizontalBoxes(context),
                            onToggle = { ctx, value -> dev.droidtop.library.scraper.ScrapeOptionsPrefs.setMiximageRotateHorizontalBoxes(ctx, value) },
                        ),
                    ),
                ),
                CatalogGroup(
                    // ScreenScraper/TheGamesDB/IGDB/SteamGridDB account
                    // fields moved to Settings > Accounts and sources,
                    // with every other account droidtop has (docs/SPEC.md
                    // settings architecture): a scraper's credentials are
                    // a source's detail, not this screen's own -- this
                    // screen is scrape BEHAVIOR (which source, what to
                    // fetch), not accounts.
                    id = "scraper_accounts_link",
                    title = null,
                    items = listOf(
                        NestedScreenItem(
                            id = "scraper_accounts_link_row",
                            title = "Accounts and sources",
                            subtitle = "ScreenScraper, TheGamesDB, IGDB and SteamGridDB credentials",
                            registryId = SCREEN_ACCOUNTS_AND_SOURCES,
                            icon = CatalogIcon.GLOBAL,
                        ),
                    ),
                ),
                CatalogGroup(
                    id = "scraper_config_transfer",
                    title = "Moving credentials between devices",
                    items = listOf(
                        ActionItem(
                            id = "scraper_backup_pointer",
                            title = "Back up / restore settings",
                            subtitle = "The settings backup in Settings includes everything here, " +
                                "credentials included — one file restores a working configuration",
                            run = { ctx ->
                                // Component by name, same as OnboardingActivity's
                                // own launch of this screen.
                                ctx.startActivity(
                                    android.content.Intent().apply {
                                        component = android.content.ComponentName(ctx.packageName, "com.android.launcher3.settings.SettingsActivity")
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    },
                                )
                            },
                        ),
                    ),
                ),
            )
        },
    )

    // ------------------------------------------------------------------
    // Platform CRUD.
    // ------------------------------------------------------------------

    private fun platformsScreen() = CatalogScreen(
        id = SCREEN_PLATFORMS,
        title = "Manage platforms",
        subtitle = "Every platform droidtop recognizes. Open one to edit or delete it",
        groups = { context ->
            val dao = ConsoleSystemsDatabase.get(context).consoleSystemDao()
            if (dao.count() == 0) ConsoleSystemsRepository.allSystems(context)
            val systems = dao.getAll()
            listOf(
                CatalogGroup(
                    id = "platforms_actions",
                    title = null,
                    items = listOf(
                        NestedScreenItem(
                            id = "platforms_add",
                            title = "Add platform",
                            inline = platformEditScreen(null),
                        ),
                        AsyncActionItem(
                            id = "platforms_restore",
                            title = "Restore defaults",
                            subtitle = "Reset every built-in platform to its original values",
                            confirmTitle = "Restore built-in platforms?",
                            run = { ctx, _ ->
                                ConsoleSystemsRepository.restoreDefaults(ctx)
                                "Built-in platforms restored"
                            },
                        ),
                    ),
                ),
                CatalogGroup(
                    id = "platforms_list",
                    title = "Platforms",
                    items = systems.map { entity ->
                        NestedScreenItem(
                            id = "platform_${entity.id}",
                            // The platform's name, and a count rather than
                            // its whole extension list; the id, the list and
                            // the core are on the platform's own page (UI
                            // pass 2026-09-24, L5: "core: null" and
                            // 30-item lists in every row).
                            title = entity.displayName,
                            subtitle = listOfNotNull(
                                entity.extensionsCsv.split(',').count { it.isNotBlank() }
                                    .takeIf { it > 0 }
                                    ?.let { if (it == 1) "1 file type" else "$it file types" },
                                if (entity.isBuiltIn) "Built-in" else "Added by you",
                            ).joinToString(" · "),
                            inline = platformEditScreen(entity),
                        )
                    },
                ),
            )
        },
    )

    // Edit = write-through per field; Add = pending buffer + explicit
    // create (the id is the primary key, so nothing exists to write
    // through until it's chosen).
    private fun platformEditScreen(existing: ConsoleSystemEntity?): CatalogScreen {
        var newId = ""
        var newName = ""
        var newExtensions = ""
        var newCore = ""
        return CatalogScreen(
            id = "platform_edit_${existing?.id ?: "new"}",
            title = existing?.let { "Edit ${it.displayName}" } ?: "Add platform",
            subtitle = existing?.let { "Id \"${it.id}\" is permanent (it names the ROMs subfolder)" },
            groups = { context ->
                val dao = ConsoleSystemsDatabase.get(context).consoleSystemDao()
                val entity = existing?.id?.let { id -> dao.getAll().firstOrNull { it.id == id } }
                // Deleted (by this page's own "Delete platform" or elsewhere):
                // no fields, because editing one would upsert the row back.
                if (existing != null && entity == null) {
                    return@CatalogScreen listOf(
                        CatalogGroup(
                            id = "platform_fields",
                            title = null,
                            items = listOf(
                                ActionItem(
                                    id = "platform_deleted",
                                    title = "${existing.displayName} was deleted",
                                    subtitle = "Go back to the platform list",
                                ),
                            ),
                        ),
                    )
                }
                listOf(
                    CatalogGroup(
                        id = "platform_fields",
                        title = null,
                        items = buildList<CatalogItem> {
                            if (entity == null) {
                                add(
                                    TextInputItem(
                                        id = "platform_new_id",
                                        title = "Id",
                                        subtitle = "Used as the ROMs subfolder name, e.g. \"psx\"",
                                        value = newId,
                                        onChange = { _, v -> newId = v.trim() },
                                    ),
                                )
                            }
                            add(
                                TextInputItem(
                                    id = "platform_name",
                                    title = "Display name",
                                    value = entity?.displayName ?: newName,
                                    onChange = { ctx, v ->
                                        if (entity != null) {
                                            ConsoleSystemsDatabase.get(ctx).consoleSystemDao().update(entity.copy(displayName = v.trim().ifBlank { entity.id }))
                                        } else {
                                            newName = v
                                        }
                                    },
                                ),
                            )
                            add(
                                TextInputItem(
                                    id = "platform_extensions",
                                    title = "File extensions",
                                    subtitle = "Comma-separated, e.g. \"nes,unf\"",
                                    value = entity?.extensionsCsv ?: newExtensions,
                                    onChange = { ctx, v ->
                                        val cleaned = v.split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
                                        if (entity != null) {
                                            ConsoleSystemsDatabase.get(ctx).consoleSystemDao().update(entity.copy(extensionsCsv = cleaned))
                                        } else {
                                            newExtensions = cleaned
                                        }
                                    },
                                ),
                            )
                            add(
                                TextInputItem(
                                    id = "platform_core",
                                    title = "RetroArch core",
                                    subtitle = "Optional, e.g. \"nestopia\"",
                                    value = entity?.retroArchCore ?: newCore,
                                    onChange = { ctx, v ->
                                        if (entity != null) {
                                            ConsoleSystemsDatabase.get(ctx).consoleSystemDao().update(entity.copy(retroArchCore = v.trim().ifBlank { null }))
                                        } else {
                                            newCore = v
                                        }
                                    },
                                ),
                            )
                            if (entity == null) {
                                add(
                                    AsyncActionItem(
                                        id = "platform_create",
                                        title = "Create platform",
                                        subtitle = "Needs at least an id",
                                        run = { ctx, _ ->
                                            if (newId.isBlank()) {
                                                "Needs an id"
                                            } else {
                                                val created = ConsoleSystemEntity(
                                                    id = newId,
                                                    displayName = newName.ifBlank { newId },
                                                    extensionsCsv = newExtensions,
                                                    retroArchCore = newCore.ifBlank { null },
                                                    isBuiltIn = false,
                                                )
                                                ConsoleSystemsDatabase.get(ctx).consoleSystemDao().upsert(created)
                                                newId = ""
                                                newName = ""
                                                newExtensions = ""
                                                newCore = ""
                                                "Added ${created.displayName}"
                                            }
                                        },
                                    ),
                                )
                            } else {
                                add(
                                    AsyncActionItem(
                                        id = "platform_delete_${entity.id}",
                                        title = "Delete platform",
                                        subtitle = if (entity.isBuiltIn) "Built-in — Restore defaults can bring it back" else "Removes this custom platform",
                                        confirmTitle = "Delete ${entity.displayName}?",
                                        run = { ctx, _ ->
                                            ConsoleSystemsDatabase.get(ctx).consoleSystemDao().delete(entity.id)
                                            "Deleted ${entity.displayName}"
                                        },
                                    ),
                                )
                            }
                        },
                    ),
                )
            },
        )
    }
}
