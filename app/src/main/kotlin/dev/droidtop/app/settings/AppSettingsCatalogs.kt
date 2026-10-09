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
import dev.droidtop.library.scraper.ScraperKeyService
import dev.droidtop.library.scraper.ScraperSource
import dev.droidtop.library.scraper.ScraperSourcePrefs
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.FolderPickItem
import dev.droidtop.library.settings.CatalogIcon
import dev.droidtop.library.settings.GamingSettingsCatalog
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.library.theme.SystemThemeColors
import java.io.File
import kotlinx.coroutines.Dispatchers
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
    const val SCREEN_ROM_FOLDERS = "rom_folders"
    const val SCREEN_SCRAPER = "rom_scraper"
    const val SCREEN_PLATFORMS = "manage_platforms"
    const val SCREEN_INTEGRATIONS = AcquireContentSources.INTEGRATIONS_SCREEN_ID
    const val SCREEN_PLUGINS = AcquireContentSources.PLUGINS_SCREEN_ID
    const val SCREEN_PLUGIN_KEYS = "plugin_keys"
    const val SCREEN_WINDOWS_GAMES = "windows_games"
    const val SCREEN_PC_STORES = "pc_stores"
    const val SCREEN_ACCOUNTS_AND_SOURCES = "accounts_and_sources"
    const val SCREEN_ANDROID_SETTINGS = "android_settings"
    const val SCREEN_ENGINEHOST = "enginehost"
    const val SCREEN_UPDATES = "updates"
    const val SCREEN_F95_IMPORT = "f95_import"

    @Volatile private var registered = false

    fun ensureRegistered() {
        if (registered) return
        registered = true
        SettingsScreenRegistry.register(consoleSystemsScreen())
        SettingsScreenRegistry.register(EmulatorsCatalog.screen())
        SettingsScreenRegistry.register(romFoldersScreen())
        SettingsScreenRegistry.register(scraperScreen())
        SettingsScreenRegistry.register(platformsScreen())
        SettingsScreenRegistry.register(integrationsScreen())
        // The one "Get games" screen every menu opens (docs/SPEC.md 12a "Get games everywhere").
        SettingsScreenRegistry.register(AcquireContentSources.chooseSystemScreen())
        SettingsScreenRegistry.register(pluginsScreen())
        SettingsScreenRegistry.register(pluginKeysScreen())
        SettingsScreenRegistry.register(PluginJobsScreen.screen())
        SettingsScreenRegistry.register(windowsGamesScreen())
        SettingsScreenRegistry.register(WineOptionsCatalog.gameScreen())
        SettingsScreenRegistry.register(pcStoresScreen())
        SettingsScreenRegistry.register(StoresCatalog.screen())
        SettingsScreenRegistry.register(SocialCatalog.screen())
        SettingsScreenRegistry.register(accountsAndSourcesScreen())
        SettingsScreenRegistry.register(androidSettingsScreen())
        SettingsScreenRegistry.register(enginehostScreen())
        SettingsScreenRegistry.register(updatesScreen())
        SettingsScreenRegistry.register(F95ImportCatalog.screen())
        SettingsScreenRegistry.register(ComputersCatalog.screen())
        SettingsScreenRegistry.register(DroidtopWideSettings.globalScreen())
        SettingsScreenRegistry.register(DroidtopWideSettings.desktopScreen())
        SettingsScreenRegistry.register(DroidtopWideSettings.standardScreen())
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
        // rows instead of the top of the whole list -- the same targeted
        // deep link that menu's "Get games" row already uses
        // (AcquireContentSources.systemScreen). A system with no folder
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
                            run = {},
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
                                            run = {},
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
                                            run = {},
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
                                            // Third-party "get games for this system"
                                            // hooks the user declared (docs/SPEC.md
                                            // section 12/12a): the JSON and plugin
                                            // mechanisms unified into ONE "Get
                                            // games" screen (AcquireContentSources,
                                            // library-core) -- the system and its
                                            // real destination folder are both
                                            // known here, exactly what that screen
                                            // needs.
                                            add(
                                                NestedScreenItem(
                                                    id = "folder_acquire_${resolved.id}",
                                                    title = "Get games",
                                                    subtitle = "Search an installed plugin or integration for ${resolved.displayName}",
                                                    inline = AcquireContentSources.systemScreen(resolved.id, resolved.displayName, folder),
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
                                run = {},
                            )
                        },
                    ),
                )
            }
        },
    )

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
                                    run = {},
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
                                subtitle = "Launching an engine game offers the matching bundle, and installs it without asking when droidtop is sure which one it needs",
                                run = {},
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
                                                    ActionItem(id = "bundle_id", title = "Bundle", value = bundle.bundleId, run = {}),
                                                    bundle.runtimeVersion?.let {
                                                        ActionItem(id = "bundle_runtime", title = "Engine version", value = it, run = {})
                                                    },
                                                    bundle.pluginVersion?.let {
                                                        ActionItem(id = "bundle_plugin", title = "Plugin version", value = it, run = {})
                                                    },
                                                    bundle.origin?.let {
                                                        ActionItem(id = "bundle_origin", title = "Source", subtitle = it, run = {})
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

    // ------------------------------------------------------------------
    // Software updates: droidtop's own release check and self-update
    // (docs/SPEC.md "Releases and updates"; machinery in AppSelfUpdate).
    // ------------------------------------------------------------------

    private fun updatesScreen() = CatalogScreen(
        id = SCREEN_UPDATES,
        title = "Updates",
        subtitle = "What has a newer version, and how droidtop checks for its own",
        groups = { context ->
            val update = dev.droidtop.app.update.AppSelfUpdate
            availableUpdatesGroups(context) + listOf(
                CatalogGroup(
                    id = "updates_droidtop",
                    title = "droidtop's own updates",
                    items = listOf(
                        ChoiceItem(
                            id = "updates_frequency",
                            title = "Check for updates",
                            subtitle = "Fetches one small file describing the latest build; nothing about this " +
                                "device or your library is sent. Picking Never stops the automatic checks; Check now still works",
                            options = dev.droidtop.app.update.AppSelfUpdate.Frequency.entries
                                .map { ChoiceOption(it.name, it.label) },
                            current = update.frequency(context).name,
                            onSelect = { ctx, value ->
                                update.setFrequency(ctx, dev.droidtop.app.update.AppSelfUpdate.Frequency.valueOf(value))
                            },
                        ),
                        ToggleItem(
                            id = "updates_unmetered_only",
                            title = "Only on Wi-Fi and other unmetered networks",
                            subtitle = "Skips the scheduled check while on mobile data or a metered connection",
                            current = update.unmeteredOnly(context),
                            onToggle = { ctx, value -> update.setUnmeteredOnly(ctx, value) },
                        ),
                        ChoiceItem(
                            id = "updates_channel",
                            title = "Build channel",
                            subtitle = "Which builds this device follows. Unstable gets every new build; the " +
                                "others get a build once it has been promoted to them",
                            options = dev.droidtop.app.update.AppSelfUpdate.Channel.entries
                                .map { ChoiceOption(it.name, it.label) },
                            current = update.channel(context).name,
                            onSelect = { ctx, value ->
                                update.setChannel(ctx, dev.droidtop.app.update.AppSelfUpdate.Channel.valueOf(value))
                            },
                        ),
                        ToggleItem(
                            id = "updates_debug_builds",
                            title = "Install debug builds",
                            // Real question this answers (rig,
                            // p1-dt-updater-debug-build-mismatch): a
                            // device can be ON a debug build (a fresh
                            // sideload, or CI's own artifact) while this
                            // stays Off, which is the normal starting
                            // state, not a stuck one. Both variants are
                            // built from the same commit and signed with
                            // the same persistent CI key (app/build.gradle.kts),
                            // so Check now here correctly detects a newer
                            // build either way and installs cleanly over
                            // a debug build even with this Off -- turning
                            // it off and checking again is exactly how a
                            // debug install gets back to the faster
                            // release one.
                            subtitle = when {
                                update.debugBuilds(context) ->
                                    "Debug builds run several times slower. Turn this off and check again to go back"
                                ctxIsDebuggable(context) ->
                                    "Currently running a debug build. Check now still finds and installs the faster " +
                                        "release build with this off"
                                else -> "Much slower builds for debugging droidtop itself. Leave this off to play"
                            },
                            current = update.debugBuilds(context),
                            onToggle = { ctx, value -> update.setDebugBuilds(ctx, value) },
                        ),
                        // ONE check: it checks now, whatever the schedule
                        // says, and installs a newer build (verified against
                        // the release's digest, then Android's installer,
                        // which checks the signing key and asks). There used
                        // to be a check-only row beside it that pointed at a
                        // "Download and install" row which did not exist (UI
                        // pass 2026-09-24, L4). The same pass is reachable
                        // over adb: am broadcast -a dev.droidtop.UPDATE_NOW
                        // -n dev.droidtop.app/.UpdateNowReceiver.
                        AsyncActionItem(
                            id = "updates_install",
                            title = "Check now",
                            subtitle = (update.lastAttempt(context)?.let { last ->
                                "Last checked " + android.text.format.DateUtils.getRelativeDateTimeString(
                                    context, last, android.text.format.DateUtils.MINUTE_IN_MILLIS,
                                    android.text.format.DateUtils.WEEK_IN_MILLIS, 0,
                                )
                            } ?: "Not checked yet") + ". Installs a newer build if there is one; Android asks you to confirm",
                            value = update.installedVersionName(context),
                            run = { ctx, onStatus ->
                                withContext(Dispatchers.IO) {
                                    dev.droidtop.app.update.UpdateNow.runNow(ctx, waitForOutcome = true) { status ->
                                        onStatus(status)
                                    }
                                }
                            },
                        ),
                    ),
                ),
            )
        },
    )

    /**
     * What has a newer version, from the data that exists today (docs/SPEC.md 7j "Places",
     * Droidtop/tracker#222): droidtop's own newer build as the last check saw it, installed
     * plugins against the cached catalog, and games whose source names a version the library does
     * not have. Android apps are not here: nothing yet knows an installed app's latest version
     * (the install and update manager, Droidtop/tracker#261, is what will). Reads only what is
     * already cached or published; it never starts a network call or a walk.
     */
    private suspend fun availableUpdatesGroups(context: Context): List<CatalogGroup> = withContext(Dispatchers.IO) {
        val selfUpdate = dev.droidtop.app.update.AppSelfUpdate
        val newerBuild = selfUpdate.newerSeenVersionName(context)
        val installed = PluginStore.installed(context)
        val index = PluginCatalog.lastGoodIndex(context)
        val pluginUpdates = index?.let { PluginCatalog.updatesFor(installed, it) }.orEmpty()
        val published = LibraryCore.library(context).backgroundScanState(LibraryKinds.GAMES).value
        val gameUpdates = published?.let { entries ->
            LibraryGrouping.group(entries).mapNotNull { group -> group.game.availableUpdate?.let { group.game.name to it } }
        }.orEmpty().sortedBy { it.first.lowercase() }

        val shown = buildList<dev.droidtop.library.settings.CatalogItem> {
            add(
                ActionItem(
                    id = "updates_droidtop_status",
                    title = "droidtop",
                    subtitle = if (newerBuild != null) {
                        "Installed ${selfUpdate.installedVersionName(context)}. Check now, below, installs it"
                    } else {
                        "Installed ${selfUpdate.installedVersionName(context)}. No newer build seen by the last check"
                    },
                    value = newerBuild?.let { "Update to $it" } ?: "Up to date",
                    run = {},
                ),
            )
            pluginUpdates.forEach { (record, release) ->
                add(
                    ActionItem(
                        id = "updates_plugin_${record.manifest.id}",
                        title = record.manifest.label,
                        subtitle = "Plugin, installed ${record.manifest.version}",
                        value = "Update to ${release.version}",
                        run = {},
                    ),
                )
            }
            if (index == null && installed.isNotEmpty()) {
                add(
                    ActionItem(
                        id = "updates_plugins_unknown",
                        title = "Plugins",
                        subtitle = "The plugin catalog has not been fetched yet. Plugins, Add, Browse catalog checks it",
                        value = "Not checked",
                        run = {},
                    ),
                )
            }
            if (pluginUpdates.isNotEmpty()) {
                add(
                    AsyncActionItem(
                        id = "updates_plugins_update_all",
                        title = "Update all plugins",
                        subtitle = "Downloads and verifies each update, then installs it the way a single update installs",
                        run = { ctx, onStatus -> PluginCatalog.updateAll(ctx, onStatus) },
                    ),
                )
            }
            gameUpdates.take(MAX_GAME_UPDATE_ROWS).forEach { (name, version) ->
                add(
                    ActionItem(
                        id = "updates_game_${name.lowercase()}",
                        title = name,
                        subtitle = "Game",
                        value = GameUpdates.line(version),
                        run = {},
                    ),
                )
            }
            if (gameUpdates.size > MAX_GAME_UPDATE_ROWS) {
                add(
                    ActionItem(
                        id = "updates_games_more",
                        title = "${gameUpdates.size - MAX_GAME_UPDATE_ROWS} more games have updates",
                        subtitle = "PC Games, Filters, Update available lists them all",
                        run = {},
                    ),
                )
            }
        }
        listOf(CatalogGroup(id = "updates_available", title = "Available", items = shown))
    }

    private const val MAX_GAME_UPDATE_ROWS = 30

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
        subtitle = "The Wine environment Windows games run inside, its Wine build and graphics, and the folders it can reach",
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
                                run = {},
                            ),
                        )
                        return@buildList
                    }
                    // The row says where setup is (its value) and asks once before
                    // the long download or reinstall starts (Droidtop/tracker#299);
                    // the same WindowsSetup path launch and the game page use.
                    add(windowsSetupItem(provisioned, setupState))
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
                            subtitle = "Add one under Game folders first — a Windows environment that cannot see your games is not much use",
                            run = {},
                        ),
                    )
                } else {
                    // The SAME assignment provision writes -- one rule in
                    // WineDriveMapping, so this preview cannot drift from
                    // what a game actually sees.
                    dev.droidtop.library.WineDriveMapping.assign(roots).map { (letter, path) ->
                        ActionItem(
                            id = "windows_drive_$letter",
                            title = "$letter:  $path",
                            subtitle = if (provisioned) {
                                "Mapped when the environment was set up"
                            } else {
                                "Will be mapped when you set up the environment"
                            },
                            run = {},
                        )
                    }
                },
            ),
        )
    }

    // ------------------------------------------------------------------
    // Stores, folders and downloads: where PC games come FROM.
    // ------------------------------------------------------------------

    /**
     * The PC surface's own first-run repairs and store state (docs/SPEC.md
     * 7i, build-plan step 6): sign in to a store, add a games folder, set
     * up Windows games, see what is downloading.
     *
     * A catalog screen rather than a screen of its own, for two reasons.
     * It is reachable from both settings surfaces for free, and the
     * Gaming PC surface renders it in place through the same
     * CatalogNavigator it already uses, so "the first-run cards" and "the
     * surface's options menu" are one list of rows instead of two
     * implementations of the same four actions.
     *
     * Every row states the real state it found: a store says whether it is
     * signed in, and each count is read rather than assumed.
     */
    private fun pcStoresScreen() = CatalogScreen(
        id = SCREEN_PC_STORES,
        // Not "Stores and folders" any more: the store SIGN-INS moved to
        // Settings > Accounts and sources with every other account and
        // source droidtop has (docs/SPEC.md settings architecture, "a
        // source is a detail on a game and a filter, never its own
        // screen"). This screen is what is left once accounts are gone:
        // the PC surface's own first-run setup for folders and downloads.
        title = "PC setup",
        subtitle = "The folders droidtop scans, the Wine environment for Windows games, and what is downloading",
        groups = { context -> pcStoresGroups(context) },
    )

    private suspend fun pcStoresGroups(context: Context): List<CatalogGroup> {
        val folders = withContext(Dispatchers.IO) { GamesRootPrefs.gamesRootPaths(context) }
        val signedInCount = withContext(Dispatchers.IO) { PcStore.entries.count { it.signedIn(context) } }

        return listOf(
            CatalogGroup(
                id = "pc_stores_accounts",
                title = null,
                items = listOf(
                    NestedScreenItem(
                        id = "pc_stores_accounts_link",
                        title = "Stores",
                        subtitle = "Sign in to Steam, GOG, Epic, Amazon Games or itch.io to download your library",
                        registryId = StoresCatalog.SCREEN_ID,
                        valueLabel = { "$signedInCount of ${PcStore.entries.size} stores signed in" },
                        icon = CatalogIcon.GLOBAL,
                    ),
                ),
            ),
            CatalogGroup(
                id = "pc_stores_folders",
                title = "Folders and setup",
                items = listOf(
                    NestedScreenItem(
                        id = "pc_stores_game_folders",
                        title = "Game folders",
                        subtitle = "The folders droidtop scans for games, Windows and engine games alike",
                        registryId = SCREEN_ROM_FOLDERS,
                        valueLabel = { if (folders.isEmpty()) "none yet" else "${folders.size}" },
                        icon = CatalogIcon.GAME_FOLDERS,
                    ),
                    NestedScreenItem(
                        id = "pc_stores_windows",
                        title = "Windows games",
                        subtitle = "The Wine environment Windows games run inside, and the folders it can reach",
                        registryId = SCREEN_WINDOWS_GAMES,
                        icon = CatalogIcon.WINDOWS_GAMES,
                    ),
                    // The one jobs list, where store installs run (docs/SPEC.md 7g "Stores").
                    NestedScreenItem(
                        id = "pc_stores_downloads",
                        title = "Downloads",
                        subtitle = "What is downloading or waiting, with Pause, Resume and Cancel",
                        registryId = dev.droidtop.library.integrations.PluginJobsScreen.ID,
                    ),
                ),
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
        subtitle = "Every account, scraper, plugin and integration droidtop can use — one row per source, its status and its actions",
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

        listOf(
            CatalogGroup(
                id = "accounts_stores",
                title = "Accounts",
                items = listOf(
                    GitHubAccountCatalog.accountRow(context),
                    // Each store's sign-in, library and sync live on its own page in Stores (docs/SPEC.md 7j "Places").
                    NestedScreenItem(
                        id = "accounts_stores_link",
                        title = "Stores",
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
                id = "accounts_plugins",
                title = "Plugins and integrations",
                items = ElevatedAccessCatalog.rows(context) + listOf(
                    NestedScreenItem(
                        id = "accounts_plugins_screen",
                        title = "Plugins",
                        subtitle = "Installed plugin code: searched, approved and run in its own process, never in droidtop's databases",
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
        )
    }

    /**
     * An optional source that needs the person's own credential: one row,
     * its state ("Not set", "Not tested", "Connected") as the value, and the
     * numbered guide, QR code and fields behind it (ScraperKeySetupActivity).
     */
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
        subtitle = "Declared as .json files you add, never bundled or synced — which apps you hook in is yours alone",
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
                                    run = {},
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
                                    run = {},
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

    /**
     * The plugin approval/management screen (docs/SPEC.md 12a). Every
     * mutation here (approve, deny, enable/disable, uninstall) goes
     * straight through [PluginStore], the one place allowed to change
     * plugin trust state, and the screen is rebuilt from disk after each
     * one -- same "no separate save step" shape [integrationsScreen]
     * uses.
     */
    /** True on a debuggable build -- avoids needing android.buildFeatures.buildConfig just for the debug-only test actions. */
    internal fun ctxIsDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun pluginsScreen() = CatalogScreen(
        id = SCREEN_PLUGINS,
        title = "Plugins",
        subtitle = "Run in their own process, only after you approve them",
        groups = { context -> pluginsGroups(context) },
        // A plugin's own page opens the Permissions screen of one plugin through here (a denied plugin view's way to change it).
        forDeepLink = { pluginId -> pluginPermissionsScreen(pluginId) },
    )

    private suspend fun pluginsGroups(context: Context): List<CatalogGroup> = withContext(Dispatchers.IO) {
        val installed = PluginStore.installed(context)
        // Read once here, off the main thread; valueLabel below is a
        // plain (non-suspend) closure the renderer may call on the main
        // thread, so it must not touch the key store itself.
        val userKeys = UserOriginKeys.load(UserOriginKeys.storeFile(context))
        val resolution = PluginApiResolver.current(context)
        val grantStore = PluginGrants.forContext(context)
        val providerChoices = PluginProviderChoices.forContext(context).all()
        // The catalog as last fetched, never a network call: a card says when its plugin has an update (Decky's badge);
        // the update itself is on the plugin's page and in Updates.
        val catalogIndex = PluginCatalog.lastGoodIndex(context)

        listOf(
            CatalogGroup(
                id = "plugins_installed",
                title = if (installed.isEmpty()) null else "Installed",
                items = buildList {
                    if (installed.isEmpty()) {
                        add(
                            ActionItem(
                                id = "plugins_none",
                                title = "No plugins installed",
                                subtitle = "Approve a plugin on its own page",
                                run = {},
                            ),
                        )
                    }
                    installed.forEach { record ->
                        val grants = grantStore.read(record.manifest.id)
                        add(
                            pluginCard(
                                record,
                                userKeys,
                                resolution.waiting[record.manifest.id],
                                grants,
                                PluginRuntimeNeeds.missing(context, record.manifest),
                                updateAvailable = catalogIndex?.let { PluginCatalog.updateFor(it, record) } != null,
                            ),
                        )
                    }
                },
            ),
        ) + providedByGroups(resolution, providerChoices) + listOf(
            CatalogGroup(
                id = "plugins_add",
                title = "Add",
                items = listOf(
                    NestedScreenItem(
                        id = "plugins_add_catalog",
                        title = "Browse catalog",
                        subtitle = "From droidtop-platforms and origins you trust",
                        inline = PluginCatalogScreen.screen(),
                    ),
                    DocumentPickItem(
                        id = "plugins_add_file",
                        title = "Install plugin file",
                        subtitle = "A signed .droidplugin.tar.xz bundle",
                        mimeType = "*/*",
                        // A downloaded bundle is in Downloads; the picker otherwise opened on an empty Documents (rig, build 1535).
                        startIn = "Download",
                        onPicked = { ctx, uri -> PluginStore.importFromPicker(ctx, uri) },
                    ),
                ),
            ),
            CatalogGroup(
                id = "plugins_repositories",
                title = null,
                items = listOf(GitHubAccountCatalog.reposRow(userKeys.values.count { it.repo != null })),
            ),
            CatalogGroup(
                id = "plugins_advanced",
                title = "Advanced",
                items = listOf(
                    NestedScreenItem(
                        id = "plugins_keys_you_trust",
                        title = "Keys you trust",
                        subtitle = "Origins whose signatures are accepted",
                        registryId = SCREEN_PLUGIN_KEYS,
                        valueLabel = {
                            if (userKeys.isEmpty()) "Official only" else "Official + ${userKeys.size} added by you"
                        },
                    ),
                ),
            ),
        )
    }

    private fun pluginTrustBadge(origin: String, userKeys: Map<String, UserOriginKey>): String = when {
        PluginOriginKeys.isOfficial(origin) -> "Official"
        userKeys.containsKey(origin) -> userKeys.getValue(origin).repo?.let { "Verified by: $it" } ?: "Added by you"
        else -> "NOT TRUSTED"
    }

    /** The installed-plugins list row: what it's called, what it adds in plain words, its trust badge and its state -- the whole card, one tap into [pluginDetailScreen]. */
    private fun pluginCard(
        record: dev.droidtop.pluginhost.PluginRecord,
        userKeys: Map<String, UserOriginKey>,
        waiting: List<dev.droidtop.pluginhost.RequiredApi>?,
        grants: PluginGrants.Snapshot,
        runtimeNeed: RuntimeNeed?,
        updateAvailable: Boolean = false,
    ): NestedScreenItem {
        val m = record.manifest
        val wantsNewAccess = grants.wantsNewAccess
        val enabledPoints = when (record.trust) {
            PluginTrustState.PENDING -> PluginGrants.defaultTicked(record)
                .filterTo(HashSet()) { it.startsWith(PluginPermissions.PROVIDE_PREFIX) }
            PluginTrustState.APPROVED -> m.v2.provides.mapNotNullTo(HashSet()) { entry ->
                (PluginPermissions.PROVIDE_PREFIX + entry.point).takeIf { PluginGrants.provideState(record, grants, entry.point) == GrantState.GRANTED }
            }
            else -> emptySet()
        }
        val state = when {
            record.trust == PluginTrustState.PENDING -> "Needs approval"
            record.trust == PluginTrustState.DENIED -> "Denied"
            record.disabledReason != null -> "Crashed"
            record.trust == PluginTrustState.APPROVED && !record.enabled -> "Disabled"
            // Approved but its runtime is not on the device: it cannot run, so it is never "Running".
            runtimeNeed != null -> "Needs setup"
            // Waiting is not disabled: the plugin resumes by itself when a provider returns (docs/plugin-api.md 2.3).
            waiting != null -> "Waiting"
            record.trust == PluginTrustState.APPROVED && wantsNewAccess -> "Wants new access"
            record.trust == PluginTrustState.APPROVED && updateAvailable -> "Update available"
            record.trust == PluginTrustState.APPROVED -> "Running"
            else -> "Unknown"
        }
        val trustBadge = pluginTrustBadge(m.origin, userKeys)
        return NestedScreenItem(
            id = "plugin_${m.id}",
            title = m.label,
            subtitle = pluginSummary(m, enabledPoints) + " - " + trustBadge + " - " + PluginTiers.badge(record, grants),
            inline = pluginDetailScreen(m.id, m.label),
            valueLabel = { state },
        )
    }

    /**
     * One plugin's own page (owner: "actual design", not a flat list):
     * what it does, its trust and running state, approve/deny/enable,
     * what a python/flutter-kind plugin still needs before it can run,
     * its version and any catalog update, and Uninstall -- with the
     * id, origin and digest technical fields behind the page's own
     * Advanced fold at the bottom, off the page's face. Permission
     * grant/revoke per plugin is agent pluginapi's model landing separately
     * (docs/plugin-api.md); this page has the seam (the "What it
     * provides" group below) but no controls yet -- nothing here should invent
     * a permissions UI ahead of that data actually existing.
     *
     * `groups` re-reads [PluginStore] and the cached catalog index fresh
     * on every (re)entry (same contract as every other [CatalogScreen]),
     * so approving, enabling or uninstalling on this page and coming
     * back always shows the current record, never one captured when the
     * parent list was built.
     */
    private fun pluginDetailScreen(pluginId: String, label: String) = CatalogScreen(
        id = "plugin_detail_$pluginId",
        title = label,
        groups = { context ->
            withContext(Dispatchers.IO) {
                val record = PluginStore.installed(context).firstOrNull { it.manifest.id == pluginId }
                if (record == null) {
                    listOf(
                        CatalogGroup(
                            id = "plugin_detail_gone",
                            title = null,
                            items = listOf(
                                ActionItem(
                                    id = "plugin_detail_gone_row",
                                    title = "This plugin is no longer installed",
                                    run = {},
                                ),
                            ),
                        ),
                    )
                } else {
                    val userKeys = UserOriginKeys.load(UserOriginKeys.storeFile(context))
                    pluginDetailGroups(context, record, userKeys)
                }
            }
        },
    )

    private fun pluginDetailGroups(
        context: Context,
        record: dev.droidtop.pluginhost.PluginRecord,
        userKeys: Map<String, UserOriginKey>,
    ): List<CatalogGroup> {
        val m = record.manifest
        val resolution = PluginApiResolver.current(context)
        val grantSnapshot = PluginGrants.forContext(context).read(m.id)
        val runtimeNeed = PluginRuntimeNeeds.missing(context, m)
        val statusGroup = buildList<CatalogItem> {
            val statusLine = when {
                record.trust == PluginTrustState.PENDING -> "Awaiting approval"
                record.trust == PluginTrustState.DENIED -> "Denied"
                record.disabledReason != null -> "Disabled: ${record.disabledReason}"
                record.trust == PluginTrustState.APPROVED && record.enabled && runtimeNeed != null ->
                    "Needs the ${runtimeNeed.runtime} runtime (${runtimeNeed.sizeLabel}) to run"
                record.trust == PluginTrustState.APPROVED && record.enabled -> "Running"
                else -> "Disabled"
            }
            val trustLine = when {
                PluginOriginKeys.isOfficial(m.origin) -> "Official"
                userKeys.containsKey(m.origin) -> userKeys.getValue(m.origin).repo?.let { "Verified by: $it" } ?: "Added by you"
                else -> "NOT TRUSTED: no trusted key for this origin anymore (see Keys you trust)"
            }
            // A pending status used to look like a button but had no action. The approval controls below
            // are the action; keep the trust/status row for plugins that already have a decision.
            if (record.trust != PluginTrustState.PENDING) {
                add(ActionItem(id = "plugin_${m.id}_status", title = statusLine, subtitle = trustLine, run = {}))
                // docs/plugin-api.md 5.3: where its code runs decides what it can reach without asking droidtop.
                add(ActionItem(id = "plugin_${m.id}_access", title = PluginTiers.badge(record, grantSnapshot), subtitle = accessLine(record, grantSnapshot), run = {}))
            }
            // The headline above is a plain sentence; what the plugin actually reported is one press away,
            // for its developer or a bug report (docs/SPEC.md 12a, Droidtop/tracker#167).
            val detail = record.disabledDetail
            if (record.disabledReason != null && detail != null) {
                add(
                    NestedScreenItem(
                        id = "plugin_${m.id}_detail",
                        title = "Technical details",
                        subtitle = "Plugin details",
                        inline = CatalogScreen(
                            id = "plugin_${m.id}_detail_screen",
                            title = "Technical details",
                            groups = { _ ->
                                listOf(
                                    CatalogGroup(
                                        id = "plugin_${m.id}_detail_group",
                                        title = null,
                                        items = listOf(ActionItem(id = "plugin_${m.id}_detail_text", title = "${m.label} reported", subtitle = detail, run = {})),
                                    ),
                                )
                            },
                        ),
                    ),
                )
            }
            resolution.waiting[m.id]?.let { missing ->
                add(
                    ActionItem(
                        id = "plugin_${m.id}_waiting",
                        title = "Needs " + missing.joinToString { it.api },
                        subtitle = "Waiting for a running plugin",
                        run = {},
                    ),
                )
            }
            if (grantSnapshot.wantsNewAccess) {
                add(
                    ActionItem(
                        id = "plugin_${m.id}_new_access",
                        title = "Wants new access",
                        subtitle = "An update asks for more; nothing new is on until you choose",
                        run = {},
                    ),
                )
            }
            when (record.trust) {
                PluginTrustState.PENDING -> Unit
                PluginTrustState.APPROVED -> {
                    add(
                        ToggleItem(
                            id = "plugin_${m.id}_enabled",
                            title = "Enabled",
                            current = record.enabled,
                            onToggle = { ctx, on -> PluginStore.setEnabled(ctx, m.id, on); pluginsChanged(ctx) },
                        ),
                    )
                }
                PluginTrustState.DENIED -> Unit
            }
            if (m.requestsRoot) {
                add(
                    ActionItem(
                        id = "plugin_${m.id}_root",
                        title = if (record.rootApproved) "Uses root" else "Can use root",
                        subtitle = if (record.rootApproved) "Approved" else "Not granted: approve with root above to allow it",
                        run = {},
                    ),
                )
            }
        }

        // Approval is a list (docs/plugin-api.md 4.3): every item is a tick box, the actions come after it, and the plugin runs with exactly what is ticked.
        val approvalTicks = if (record.trust == PluginTrustState.PENDING) tickBuffer(m.id, PluginGrants.defaultTicked(record)) else null
        val approveItems: List<CatalogItem> = if (approvalTicks == null) {
            emptyList()
        } else {
            fun approve(ctx: Context, root: Boolean) {
                PluginStore.setApproval(ctx, m.id, approved = true, grantRoot = root, ticked = approvalTicks.toSet())
                pendingTicks.remove(m.id)
                pluginsChanged(ctx)
            }
            buildList {
                add(
                    ActionItem(
                        id = "plugin_${m.id}_approve",
                        title = "Approve",
                        subtitle = if (m.requestsRoot) {
                            "Allows what is ticked above. This plugin can also use root as an optional enhancement when your device has it -- its core function must still work without it. Approving here does NOT grant root; use \"Approve and allow root\" for that."
                        } else {
                            "Allows what is ticked above and runs in its own process from now on. You can change any of it later under Permissions."
                        },
                        run = { ctx -> approve(ctx, false) },
                    ),
                )
                if (m.requestsRoot) {
                    add(
                        ActionItem(
                            id = "plugin_${m.id}_approve_root",
                            title = "Approve and allow root",
                            subtitle = "Only used if this device has root",
                            confirmTitle = "Let \"${m.label}\" use root on this device?",
                            run = { ctx -> approve(ctx, true) },
                        ),
                    )
                }
                add(
                    ActionItem(
                        id = "plugin_${m.id}_deny",
                        title = "Deny",
                        subtitle = "Stays installed, never runs",
                        run = { ctx -> pendingTicks.remove(m.id); PluginStore.setApproval(ctx, m.id, approved = false, grantRoot = false); pluginsChanged(ctx) },
                    ),
                )
            }
        }
        // An update that added items asks about those only, on the same list (docs/plugin-api.md 4.3, "Updates").
        val freshIds = if (record.trust == PluginTrustState.APPROVED) grantSnapshot.fresh.filterTo(HashSet()) { grantSnapshot.states[it] == GrantState.ASK } else emptySet<String>()
        val newKey = "${m.id}#new"
        val newTicks = if (freshIds.isEmpty()) null else tickBuffer(newKey, PluginGrants.defaultTicked(record).filterTo(HashSet()) { it in freshIds })
        val newAccessGroups: List<CatalogGroup> = if (newTicks == null) {
            emptyList()
        } else {
            pluginConsentGroups(context, record, userKeys, ticks = newTicks, only = freshIds) + CatalogGroup(
                id = "plugin_${m.id}_new_access_group",
                title = null,
                items = listOf(
                    ActionItem(
                        id = "plugin_${m.id}_new_access_save",
                        title = "Allow what is ticked",
                        subtitle = "Changes only the new items",
                        run = { ctx ->
                            PluginGrants.forContext(ctx).answerNew(record, freshIds, newTicks.toSet())
                            pendingTicks.remove(newKey)
                            pluginsChanged(ctx)
                        },
                    ),
                ),
            )
        }

        // "Asks for" is what approval is about; once approved, the Permissions screen below holds the real
        // state of each one, and a second list saying "Asks first" beside "7 allowed, 0 ask" contradicts it.
        val consentGroups = pluginConsentGroups(context, record, userKeys, ticks = approvalTicks)
            .filterNot { record.trust == PluginTrustState.APPROVED && it.id.endsWith("_consent_asks") }

        // Grants exist once the plugin is approved (docs/plugin-api.md 4.4): one screen per plugin, no second place.
        val permissionsGroup: CatalogGroup? = if (record.trust != PluginTrustState.APPROVED) {
            null
        } else {
            val rows = permissionRows(record, grantSnapshot)
            val summary = "${rows.count { it.state == GrantState.GRANTED }} allowed, ${rows.count { it.state == GrantState.ASK }} ask first, ${rows.count { it.state == GrantState.DENIED }} blocked"
            CatalogGroup(
                id = "plugin_${m.id}_permissions_group",
                title = "Permissions",
                items = listOf(
                    NestedScreenItem(
                        id = "plugin_${m.id}_permissions",
                        title = "Permissions",
                        subtitle = "What it may do, and when it last did",
                        inline = pluginPermissionsScreen(m.id),
                        valueLabel = { summary },
                    ),
                    NestedScreenItem(
                        id = "plugin_${m.id}_activity",
                        title = "Activity",
                        subtitle = "What it asked droidtop to do, newest first",
                        inline = pluginActivityScreen(m.id),
                    ),
                ),
            )
        }

        val enabledPoints = when {
            approvalTicks != null -> approvalTicks
                .filterTo(HashSet()) { it.startsWith(PluginPermissions.PROVIDE_PREFIX) }
            record.trust == PluginTrustState.APPROVED -> m.v2.provides.mapNotNullTo(HashSet()) { entry ->
                val id = PluginPermissions.PROVIDE_PREFIX + entry.point
                id.takeIf { PluginGrants.provideState(record, grantSnapshot, entry.point) == GrantState.GRANTED }
            }
            else -> emptySet()
        }
        val providesGroup = listOf<CatalogItem>(
            ActionItem(
                id = "plugin_${m.id}_provides",
                title = pluginSummary(m, enabledPoints),
                run = {},
            ),
        )

        // What the plugin is for, first (Decky's model, Droidtop/tracker#316): its Quick Menu panel, its settings, its
        // own app and the app it manages. The panel is the same screen the Quick Menu's Plugins section opens, so the
        // Standard and Desktop modes, which have no Quick Menu, reach it here.
        val useGroup = buildList<CatalogItem> {
            if (record.runnable()) {
                PluginPanels.panelsFor(context).firstOrNull { it.pluginId == m.id }?.let { panel ->
                    add(
                        NestedScreenItem(
                            id = "plugin_${m.id}_panel",
                            title = "Panel",
                            subtitle = "What the plugin shows in the Quick Menu",
                            inline = PluginPanels.panelScreen(context, panel, PluginPanels.SURFACE_SETTINGS, game = null, withMore = false),
                        ),
                    )
                }
            }
            if (record.runnable() && PluginCapability.SETTINGS_ROWS in m.capabilities) {
                add(
                    NestedScreenItem(
                        id = "plugin_${m.id}_settings_rows",
                        title = "Settings",
                        subtitle = "Settings this plugin contributes",
                        inline = PluginSettingsRows.screenFor(record),
                    ),
                )
            }
            // The plugin's own full-screen app (ui.main, docs/plugin-api.md 1.7), the same row in every mode.
            if (PluginMainUi.offered(record)) {
                add(
                    AsyncActionItem(
                        id = "plugin_${m.id}_open_main",
                        title = "Open ${m.label}",
                        subtitle = "Its own screen, full-screen. Back returns here",
                        run = { ctx, _ -> PluginMainUi.open(ctx, record) ?: "Opened" },
                    ),
                )
            }
            if (record.runnable() && PluginCapability.APP_STATUS in m.capabilities) {
                add(
                    NestedScreenItem(
                        id = "plugin_${m.id}_app_status",
                        title = "App status",
                        subtitle = "Status and actions for the app this plugin manages",
                        inline = PluginAppStatus.screenFor(record),
                    ),
                )
            }
        }

        // Shown on THIS plugin's own page, not as a top-level runtimes
        // list (owner direction): a python/flutter_embed-kind plugin is
        // the only reason the runtime matters, so the download/remove
        // action lives where that reason is visible. Fetching it
        // automatically as part of approval is agent pluginapi's
        // permission-model work landing separately; today it is still an
        // explicit action, same as before.
        val runtimeGroup: List<CatalogItem> = listOfNotNull(runtimeItem(context, m.kind, m.label, runtimeNeed))

        val updateGroup = buildList<CatalogItem> {
            val index = PluginCatalog.lastGoodIndex(context)
            val release = index?.let { PluginCatalog.updateFor(it, record) }
            val plugin = index?.pluginById(m.id)
            add(
                if (release != null && plugin != null) {
                    AsyncActionItem(
                        id = "plugin_${m.id}_update",
                        title = "Version ${m.version}",
                        subtitle = "An update is available in the catalog",
                        value = "Update to ${release.version}",
                        run = { ctx, onStatus -> PluginCatalog.install(ctx, plugin, release, onStatus) },
                    )
                } else {
                    ActionItem(
                        id = "plugin_${m.id}_version",
                        title = "Version ${m.version}",
                        subtitle = if (index == null) "Catalog not fetched yet: open Add > Browse catalog to check" else "Matches the catalog's latest stable release",
                        run = {},
                    )
                },
            )
        }

        val advancedGroup = listOf(
            ActionItem(id = "plugin_${m.id}_id", title = "Plugin id", subtitle = m.id, run = {}),
            ActionItem(id = "plugin_${m.id}_origin", title = "Origin", subtitle = m.origin, run = {}),
            ActionItem(id = "plugin_${m.id}_digest", title = "Archive digest", subtitle = record.archiveDigest, run = {}),
            // docs/plugin-api.md 5.3: loads the plugin as a call would and shows what its process reached when it tried.
            AsyncActionItem(
                id = "plugin_${m.id}_containment",
                title = "Containment check",
                subtitle = "Where it runs, and whether it can reach the network, droidtop's files or shared storage by itself",
                run = { ctx, _ ->
                    val policy = PluginCrashPolicy(ctx.applicationContext)
                    try {
                        policy.containmentReport(record)
                    } finally {
                        policy.shutdown()
                    }
                },
            ),
        ) + listOfNotNull(
            // A debug build's crash-containment check (docs/SPEC.md 12a). Its status tile itself is in the plugin's panel.
            if (record.runnable() && PluginCapability.STATUS_TILE in m.capabilities && ctxIsDebuggable(context)) {
                AsyncActionItem(
                    id = "plugin_${m.id}_force_crash",
                    title = "Debug: force a crash",
                    subtitle = "droidtop should survive and disable this plugin",
                    confirmTitle = "Force ${m.label} to crash now?",
                    run = { ctx, _ ->
                        val policy = PluginCrashPolicy(ctx.applicationContext)
                        try {
                            val result = policy.invoke(record, PluginCapability.STATUS_TILE, mapOf("query" to "force-crash"))
                            if (result.ok) "Unexpected success: ${result.values}" else "Crashed as expected: ${result.error}"
                        } finally {
                            policy.shutdown()
                        }
                    },
                )
            } else {
                null
            },
        )

        // A runtime the plugin cannot run without goes straight under the status line that says so, where
        // the person is looking; an installed one (only its removal) stays further down.
        val runtimeGroupItem = if (runtimeGroup.isEmpty()) null else CatalogGroup(id = "plugin_${m.id}_runtime_group", title = "Runtime", items = runtimeGroup)
        return listOfNotNull(
            CatalogGroup(id = "plugin_${m.id}_status_group", title = null, items = statusGroup),
            runtimeGroupItem.takeIf { runtimeNeed != null },
            useGroup.takeIf { it.isNotEmpty() }?.let { CatalogGroup(id = "plugin_${m.id}_use_group", title = null, items = it) },
        ) + newAccessGroups + consentGroups + listOfNotNull(
            approveItems.takeIf { it.isNotEmpty() }?.let { CatalogGroup(id = "plugin_${m.id}_approve_group", title = null, items = it) },
            permissionsGroup,
            CatalogGroup(id = "plugin_${m.id}_provides_group", title = "What it provides", items = providesGroup),
            runtimeGroupItem.takeIf { runtimeNeed == null },
            CatalogGroup(id = "plugin_${m.id}_update_group", title = "Version", items = updateGroup),
            CatalogGroup(
                id = "plugin_${m.id}_uninstall_group",
                title = null,
                items = listOf(
                    ActionItem(
                        id = "plugin_${m.id}_uninstall",
                        title = "Uninstall",
                        confirmTitle = "Remove ${m.label} and its data?",
                        run = { ctx -> PluginStore.uninstall(ctx, m.id); pluginsChanged(ctx) },
                    ),
                ),
            ),
            CatalogGroup(id = "plugin_${m.id}_advanced_group", title = "Advanced", items = advancedGroup),
        )
    }

    /**
     * The runtime row of a plugin's own page: when the plugin cannot run without a runtime the device
     * lacks, the one labelled action that downloads it; when it is installed, its removal (the surface
     * asks for an explicit yes first). Null for a kind that needs no runtime.
     */
    private fun runtimeItem(context: Context, kind: PluginKind, forPluginLabel: String, need: RuntimeNeed?): CatalogItem? {
        if (need != null) {
            return AsyncActionItem(
                id = "plugins_${need.runtime.lowercase()}_runtime_download",
                title = need.actionLabel,
                subtitle = "Press A to download; $forPluginLabel needs it",
                run = { ctx, onStatus -> PluginRuntimeNeeds.install(ctx, need, onStatus) ?: "${need.runtime} runtime installed" },
            )
        }
        return when (kind) {
            PluginKind.PYTHON -> ActionItem(
                id = "plugins_python_runtime_remove",
                title = "Python runtime: installed",
                subtitle = "CPython ${PythonRuntimeManager.installedVersion(context) ?: PythonRuntimeManager.pinnedVersion(context)} (${PythonRuntimeManager.currentAbi()}); Python plugins stop until it is downloaded again",
                confirmTitle = "Remove the downloaded Python runtime?",
                run = { ctx -> PythonRuntimeManager.remove(ctx) },
            )
            PluginKind.FLUTTER_EMBED -> ActionItem(
                id = "plugins_flutter_runtime_remove",
                title = "Flutter runtime: installed",
                subtitle = "Flutter engine ${FlutterRuntimeManager.pinnedVersion(context)} (${FlutterRuntimeManager.currentAbi()}); Flutter plugins stop until it is downloaded again",
                confirmTitle = "Remove the downloaded Flutter runtime?",
                run = { ctx -> FlutterRuntimeManager.remove(ctx) },
            )
            else -> null
        }
    }

    /** What the access badge means for [record], in one sentence (docs/plugin-api.md 4.6, 5.3). */
    private fun accessLine(record: dev.droidtop.pluginhost.PluginRecord, grants: PluginGrants.Snapshot): String {
        PluginTiers.refusal(record, grants)?.let { return it }
        return if (PluginTiers.of(record, grants) == PluginTier.FULL_TRUST) {
            "Runs with droidtop's own access: it can do anything droidtop can, and only what it asks droidtop to do is listed under Activity"
        } else {
            "Runs sealed in a process of its own: no network, files or permissions except what droidtop does for it, as allowed under Permissions"
        }
    }

    /** One line of the Permissions screen: a permission, a high-risk point it may provide, or an export, with its current state. */
    private data class PermissionRow(val id: String, val label: String, val reason: String?, val tier: PermissionTier, val state: GrantState)

    /** Every item [record]'s grants cover, in the manifest's order (docs/plugin-api.md 4.3, 4.4). */
    private fun permissionRows(
        record: dev.droidtop.pluginhost.PluginRecord,
        snap: PluginGrants.Snapshot,
        providerLabels: Map<String, String> = emptyMap(),
    ): List<PermissionRow> {
        val m = record.manifest
        val rows = mutableListOf<PermissionRow>()
        for (declared in m.v2.permissions) {
            if (declared.id.startsWith(PluginPermissions.PROVIDE_PREFIX)) continue
            val state = PluginGrants.stateOf(record, snap, declared.id) ?: continue
            rows += PermissionRow(declared.id, PluginPermissions.labelFor(declared.id) ?: providerLabels[declared.id] ?: declared.id, declared.reason, PluginGrants.tierOf(declared), state)
        }
        for (entry in m.v2.provides) {
            val point = ExtensionPoints.find(entry.point) ?: continue
            val id = PluginPermissions.PROVIDE_PREFIX + entry.point
            val tier = when (point.risk) {
                dev.droidtop.pluginhost.PointRisk.CRITICAL -> PermissionTier.CRITICAL
                dev.droidtop.pluginhost.PointRisk.HIGH -> PermissionTier.DANGEROUS
                else -> PermissionTier.NORMAL
            }
            rows += PermissionRow(id, point.label, point.lets.ifEmpty { null }, tier, PluginGrants.provideState(record, snap, entry.point))
        }
        for (export in m.v2.exports) {
            rows += PermissionRow(PluginGrants.EXPORT_PREFIX + export.api, "Offer ${export.api} to other plugins", null, PermissionTier.NORMAL, PluginGrants.exportState(snap, export.api))
        }
        return rows.distinctBy { it.id }
    }

    /**
     * Accounts and sources > Plugins > one plugin > Permissions (docs/plugin-api.md 4.4): every declared permission with its state and when
     * the audit log last saw it used. A change is written at once and takes effect on the plugin's next call.
     */
    private fun pluginPermissionsScreen(pluginId: String) = CatalogScreen(
        id = "plugin_permissions_$pluginId",
        title = "Permissions",
        subtitle = "A change takes effect on the plugin's next call",
        groups = { context -> withContext(Dispatchers.IO) { pluginPermissionGroups(context, pluginId) } },
    )

    /**
     * Accounts and sources > Plugins > one plugin > Activity (docs/plugin-api.md 4.6): the activity log, newest first. For a
     * contained plugin that is everything it reached beyond its own process that is logged; a full-trust plugin can also
     * act directly, which nothing can list, and the screen says so.
     */
    private fun pluginActivityScreen(pluginId: String) = CatalogScreen(
        id = "plugin_activity_$pluginId",
        title = "Activity",
        groups = { context -> withContext(Dispatchers.IO) { pluginActivityGroups(context, pluginId) } },
    )

    private fun pluginActivityGroups(context: Context, pluginId: String): List<CatalogGroup> {
        val record = PluginStore.installed(context).firstOrNull { it.manifest.id == pluginId }
        val grants = PluginGrants.forContext(context).read(pluginId)
        val note = when {
            record == null -> "This plugin was removed; its activity is kept for 7 days"
            PluginTiers.of(record, grants) == PluginTier.FULL_TRUST ->
                "This plugin runs with full access; only what it asks droidtop to do is listed."
            else ->
                "This plugin runs contained: everything it does beyond its own process goes through droidtop. Its network and shared-file use and every sensitive call are listed; everyday calls (its own data, short messages) are not."
        }
        val format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        val entries = PluginAudit.forContext(context).entries(pluginId).asReversed().take(ACTIVITY_SHOWN)
        val rows = entries.mapIndexed { i, e ->
            ActionItem(
                id = "plugin_${pluginId}_activity_$i",
                title = (PluginPermissions.labelFor(e.permission) ?: e.permission) + ": " + e.api + " " + e.op,
                subtitle = listOfNotNull(
                    format.format(java.util.Date(e.atMs)),
                    e.target.takeIf { it.isNotBlank() },
                    if (e.result == "ok") null else "refused or failed: ${e.result}",
                    e.via.takeIf { it.isNotEmpty() }?.let { "via ${it.joinToString(" > ")}" },
                ).joinToString(" - "),
                run = {},
            )
        }
        return listOfNotNull(
            CatalogGroup(id = "plugin_activity_note", title = null, items = listOf(ActionItem(id = "plugin_activity_note_row", title = note, run = {}))),
            CatalogGroup(
                id = "plugin_activity_entries",
                title = "Newest first",
                items = rows.ifEmpty { listOf(ActionItem(id = "plugin_activity_empty", title = "Nothing logged yet", run = {})) },
            ),
        )
    }

    /** After anything that changes which plugins run or what they may do: the status widget and background work follow. */
    private fun pluginsChanged(context: Context) {
        PluginStatusWidgetProvider.requestUpdate(context)
        PluginBackground.changed(context)
    }

    private fun pluginPermissionGroups(context: Context, pluginId: String): List<CatalogGroup> {
        val installed = PluginStore.installed(context)
        val record = installed.firstOrNull { it.manifest.id == pluginId }
            ?: return listOf(
                CatalogGroup(
                    id = "plugin_permissions_gone",
                    title = null,
                    items = listOf(ActionItem(id = "plugin_permissions_gone_row", title = "This plugin is no longer installed", run = {})),
                ),
            )
        val snap = PluginGrants.forContext(context).read(pluginId)
        val audit = PluginAudit.forContext(context)
        val providerLabels = installed.flatMap { it.manifest.v2.exports }.flatMap { it.permissions }.associate { it.id to it.label }
        val rows = permissionRows(record, snap, providerLabels)
        val format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        fun item(row: PermissionRow): CatalogItem {
            val lastUsed = audit.lastUsed(pluginId, row.id)?.let { "Last used ${format.format(java.util.Date(it))}" }
            val wanted = if (row.id in snap.wanted) "It tried to use this but could not ask you first" else null
            return dev.droidtop.library.settings.ChoiceItem(
                id = "plugin_${pluginId}_perm_${row.id}",
                title = row.label,
                subtitle = listOfNotNull(row.reason, wanted, lastUsed).joinToString(" - ").ifEmpty { null },
                options = listOf(
                    dev.droidtop.library.settings.ChoiceOption(GrantState.GRANTED.name, "Allowed"),
                    dev.droidtop.library.settings.ChoiceOption(GrantState.ASK.name, "Ask first"),
                    dev.droidtop.library.settings.ChoiceOption(GrantState.DENIED.name, "Blocked"),
                ),
                current = row.state.name,
                onSelect = { ctx, value ->
                    GrantState.fromId(value)?.let { PluginGrants.forContext(ctx).set(pluginId, row.id, it) }
                    pluginsChanged(ctx)
                },
            )
        }
        // "Where it appears" (docs/plugin-api.md 1.9): one switch per mode the plugin has a place in; off takes every
        // contribution of this plugin out of that mode, and nowhere else.
        val modeLabels = mapOf(PluginModes.GAMING to "Gaming", PluginModes.STANDARD to "Android home screen", PluginModes.DESKTOP to "Desktop")
        val modeItems = PluginModes.modesOf(record.manifest).map { mode ->
            ToggleItem(
                id = "plugin_${pluginId}_mode_$mode",
                title = modeLabels[mode] ?: mode,
                current = PluginModes.allowed(snap, mode),
                onToggle = { ctx, on ->
                    PluginGrants.forContext(ctx).set(pluginId, PluginModes.key(mode), if (on) GrantState.GRANTED else GrantState.DENIED)
                    pluginsChanged(ctx)
                },
            )
        }
        // Each background service and schedule has its own switch (docs/plugin-api.md 3 E8, E9), under the point's.
        val backgroundItems = record.manifest.v2.provides
            .filter { it.point == BackgroundProtocol.SERVICE_POINT || it.point == BackgroundProtocol.SCHEDULE_POINT }
            .map { entry ->
                val service = entry.point == BackgroundProtocol.SERVICE_POINT
                val every = BackgroundProtocol.everyMs(entry)
                ToggleItem(
                    id = "plugin_${pluginId}_bg_${entry.point}_${BackgroundProtocol.entryId(entry)}",
                    title = entry.label ?: record.manifest.label,
                    subtitle = when {
                        service -> "Keeps running in the background"
                        every == null -> "Cannot run: its schedule is not between 15 minutes and 30 days"
                        else -> "Runs every ${every / 60_000} minutes"
                    },
                    current = BackgroundProtocol.entryOn(snap, entry),
                    onToggle = { ctx, on ->
                        PluginGrants.forContext(ctx).set(pluginId, BackgroundProtocol.entryKey(entry), if (on) GrantState.GRANTED else GrantState.DENIED)
                        pluginsChanged(ctx)
                    },
                )
            }
        val rest = rows
        return listOfNotNull(
            CatalogGroup(
                id = "plugin_permissions_access",
                title = "Access",
                items = listOf(
                    ActionItem(
                        id = "plugin_permissions_access_row",
                        title = PluginTiers.badge(record, snap),
                        subtitle = if (record.manifest.contractVersion < 2) {
                            "Written before permissions existed: it runs with droidtop's own access, and only what it asks droidtop to do is listed under Activity"
                        } else {
                            accessLine(record, snap)
                        },
                        run = {},
                    ),
                ),
            ),
            modeItems.takeIf { it.isNotEmpty() }?.let { CatalogGroup("plugin_permissions_modes", "Where it appears", it) },
            backgroundItems.takeIf { it.isNotEmpty() }?.let { CatalogGroup("plugin_permissions_background", "Background tasks", it) },
            rest.filter { it.tier == PermissionTier.CRITICAL }.takeIf { it.isNotEmpty() }?.let { CatalogGroup("plugin_permissions_critical", "Critical", it.map(::item)) },
            rest.filter { it.tier == PermissionTier.DANGEROUS }.takeIf { it.isNotEmpty() }?.let { CatalogGroup("plugin_permissions_dangerous", "Sensitive", it.map(::item)) },
            rest.filter { it.tier == PermissionTier.NORMAL }.takeIf { it.isNotEmpty() }?.let { CatalogGroup("plugin_permissions_normal", "Can", it.map(::item)) },
            if (rows.isEmpty()) {
                CatalogGroup(
                    id = "plugin_permissions_none",
                    title = null,
                    items = listOf(ActionItem(id = "plugin_permissions_none_row", title = "This plugin asks for no permissions", run = {})),
                )
            } else {
                null
            },
        )
    }

    /** "Provided by" (docs/plugin-api.md 2.3): one choice per API that more than one running plugin provides. Nothing is ranked for the user. */
    private fun providedByGroups(resolution: ApiResolution, chosen: Map<String, String>): List<CatalogGroup> {
        val shared = resolution.providers.filter { it.value.size > 1 }
        if (shared.isEmpty()) return emptyList()
        return listOf(
            CatalogGroup(
                id = "plugins_provided_by",
                title = "Provided by",
                items = shared.map { (api, list) ->
                    dev.droidtop.library.settings.ChoiceItem(
                        id = "plugins_provided_by_$api",
                        title = api,
                        subtitle = "Which plugin answers this for the plugins that use it",
                        options = list.map { dev.droidtop.library.settings.ChoiceOption(it.plugin.manifest.id, it.plugin.manifest.label) },
                        current = chosen[api]?.takeIf { id -> list.any { it.plugin.manifest.id == id } } ?: list.first().plugin.manifest.id,
                        onSelect = { ctx, value -> PluginProviderChoices.forContext(ctx).choose(api, value) },
                    )
                },
            ),
        )
    }

    /** What a plugin adds, in plain words, for the installed-list row and the detail page's provides row. */
    private fun pluginSummary(m: dev.droidtop.pluginhost.PluginManifest, enabledPoints: Set<String>): String =
        if (m.v2.provides.isNotEmpty()) {
            m.v2.provides
                .filter { PluginPermissions.PROVIDE_PREFIX + it.point in enabledPoints }
                .mapNotNull { ExtensionPoints.find(it.point)?.label }
                .distinct()
                .joinToString()
                .ifEmpty { "No extensions allowed" }
        } else {
            m.capabilities.joinToString { it.display }.ifEmpty { "No capabilities declared" }
        }

    /**
     * The approval list of one plugin (docs/plugin-api.md 4.3): what it adds and where, what it can do, what it
     * asks for, what it offers and what it uses from other plugins, and what this droidtop does not support.
     * Built from the registries by [PluginConsent]. With [ticks] every item that has a grant key is a tick box
     * the user can switch off before approving, high-risk ones marked; the box state lives in [ticks] until the
     * Approve action writes it. Without [ticks] the list is read-only. [only] cuts the list down to what an
     * update added.
     */
    private fun pluginConsentGroups(
        context: Context,
        record: dev.droidtop.pluginhost.PluginRecord,
        userKeys: Map<String, UserOriginKey>,
        ticks: MutableSet<String>? = null,
        only: Set<String>? = null,
    ): List<CatalogGroup> {
        val m = record.manifest
        val scope = if (only == null) "" else "new_"
        val full = PluginConsent.of(m, PluginStore.installed(context)) { pluginTrustBadge(it, userKeys) }
        val view = if (only == null) full else full.only(only)
        fun info(key: String, title: String, subtitle: String? = null, value: String? = null) =
            ActionItem(id = "plugin_${m.id}_$scope$key", title = title, subtitle = subtitle, value = value, run = {})
        fun line(key: String, l: dev.droidtop.pluginhost.ConsentLine, lead: String? = null, trail: String? = null, value: String? = null): CatalogItem {
            val grantKey = l.id
            if (ticks == null || grantKey == null) {
                return info(key, l.title, listOfNotNull(lead, l.detail, trail).joinToString(" - ").ifEmpty { null }, value)
            }
            return ToggleItem(
                id = "plugin_${m.id}_${scope}tick_$grantKey",
                title = if (l.highRisk) "${l.title} (high risk)" else l.title,
                subtitle = listOfNotNull(lead, l.detail, trail).joinToString(" - ").ifEmpty { null },
                current = grantKey in ticks,
                onToggle = { _, on -> if (on) ticks.add(grantKey) else ticks.remove(grantKey) },
            )
        }
        fun group(key: String, title: String, items: List<CatalogItem>) =
            if (items.isEmpty()) null else CatalogGroup(id = "plugin_${m.id}_consent_$scope$key", title = title, items = items)
        return listOfNotNull(
            if (only != null) {
                null
            } else if (view.olderPluginFullAccess) {
                group("older", "Older plugin", listOf(info("older", "Full access (older plugin)", "Written before permissions existed: it can do everything it could before, and droidtop does not contain it")))
            } else if (view.asksFullAccess) {
                group("access", "Access", listOf(info("access", "Asks for full access", "Allowed, it runs with droidtop's own access and can do anything droidtop can; only what it asks droidtop to do is listed under Activity")))
            } else {
                group("access", "Access", listOf(info("access", "Contained", "Runs sealed in a process of its own: it can reach only what is listed here, and only through droidtop")))
            },
            group(
                "adds", "Adds",
                view.adds.flatMap { (mode, lines) -> lines.mapIndexed { i, l -> line("adds_${mode}_$i", l, lead = mode.takeIf { ticks != null }, value = mode.takeIf { ticks == null }) } },
            ),
            group("can", "Can", view.can.mapIndexed { i, l -> line("can_$i", l) }),
            group(
                "asks", "Asks for",
                view.asks.mapIndexed { i, a ->
                    line(
                        "asks_$i", a.line,
                        trail = if (a.needed) "Needed" else null,
                        value = if (a.tier == PermissionTier.CRITICAL) "Critical" else "Asks first",
                    )
                },
            ),
            group("offers", "Offers to other plugins", view.offers.mapIndexed { i, l -> line("offers_$i", l) }),
            group(
                "uses", "Uses from other plugins",
                view.uses.mapIndexed { i, u ->
                    info(
                        "uses_$i", u.line.title,
                        listOfNotNull(u.line.detail, if (u.optional) "Optional" else "Needed").joinToString(" - "),
                        if (u.providerLabel != null) "${u.providerLabel} (${u.providerBadge})" else "No plugin provides this yet",
                    )
                },
            ),
            group("unsupported", "Not supported by this version of droidtop", view.unsupported.mapIndexed { i, t -> info("unsupported_$i", t) }),
        )
    }

    /** The most activity lines one plugin's Activity screen shows; the log keeps up to 2,000. */
    private const val ACTIVITY_SHOWN = 200

    /** The tick boxes of one approval list, kept between redraws until the user answers. Keyed by plugin, and by plugin plus "#new" for an update's list. */
    private val pendingTicks = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    private fun tickBuffer(key: String, start: Set<String>): MutableSet<String> =
        pendingTicks.getOrPut(key) { java.util.Collections.synchronizedSet(start.toMutableSet()) }

    // ------------------------------------------------------------------
    // Keys you trust (docs/SPEC.md 12a): user-trusted plugin origin keys.
    // ------------------------------------------------------------------

    /**
     * A fetched key awaiting the user's one TOFU confirmation -- shown
     * as explicit rows, never trusted on fetch. [existing] is non-null
     * when the source now publishes a DIFFERENT key than the one already
     * trusted for that origin: then the only way forward is the
     * explicit replace, behind a warning naming both fingerprints.
     */
    private data class ProposedKey(
        val origin: String,
        val keyBase64: String,
        val sourceUrl: String,
        val existing: UserOriginKey?,
    )

    // Buffers between the text fields and their actions, the same
    // pending-buffer shape pendingRootPath uses below.
    private var pendingKeySourceUrl = ""
    private var pendingKeyManualOrigin = ""
    private var pendingKeyManualKey = ""
    private var pendingKeyProposal: ProposedKey? = null

    private fun pluginKeysScreen() = CatalogScreen(
        id = SCREEN_PLUGIN_KEYS,
        title = "Keys you trust",
        subtitle = "Origins whose plugin signatures droidtop verifies. The official one is certified inside droidtop itself; " +
            "any other is one YOU chose to trust: third-party, not official, and droidtop has not vetted it",
        groups = { context ->
            val userKeys = withContext(Dispatchers.IO) { UserOriginKeys.load(UserOriginKeys.storeFile(context)) }
            val proposal = pendingKeyProposal
            listOf(
                CatalogGroup(
                    id = "plugin_keys_trusted",
                    title = "Trusted origins",
                    items = buildList {
                        add(
                            ActionItem(
                                id = "plugin_keys_official",
                                title = PluginOriginKeys.OFFICIAL_ORIGIN,
                                subtitle = "Official: certified inside droidtop itself. Plugin master key " +
                                    (PluginOriginKeys.masterKeyBase64()?.let(UserOriginKeys::fingerprint) ?: "not pinned in this build") +
                                    "; legacy key " + (UserOriginKeys.fingerprint(PluginOriginKeys.officialKeyBase64()) ?: "unavailable"),
                                run = {},
                            ),
                        )
                        if (userKeys.isEmpty()) {
                            add(
                                ActionItem(
                                    id = "plugin_keys_none",
                                    title = "No keys added by you",
                                    subtitle = "Only the official origin is trusted. Add a plugin source below and droidtop fetches its key, or paste one by hand",
                                    run = {},
                                ),
                            )
                        }
                        userKeys.values.sortedBy { it.origin }.forEach { entry ->
                            add(
                                ActionItem(
                                    id = "plugin_key_${entry.origin}",
                                    title = entry.origin,
                                    subtitle = buildString {
                                        append("Added by you: third-party, NOT official. Key fingerprint ")
                                        append(UserOriginKeys.fingerprint(entry.keyBase64) ?: "unreadable")
                                        entry.source?.let { append(". Added from $it") }
                                    },
                                    run = {},
                                ),
                            )
                            add(
                                AsyncActionItem(
                                    id = "plugin_key_${entry.origin}_remove",
                                    title = "Stop trusting \"${entry.origin}\"",
                                    subtitle = "Plugins it signed stop running and can no longer be updated, until you trust it again",
                                    confirmTitle = "Stop trusting \"${entry.origin}\"?",
                                    run = { ctx, _ ->
                                        if (UserOriginKeys.remove(UserOriginKeys.storeFile(ctx), entry.origin)) {
                                            pluginsChanged(ctx)
                                            "No longer trusting \"${entry.origin}\": its plugins are flagged on the Plugins screen"
                                        } else {
                                            "Nothing to remove"
                                        }
                                    },
                                ),
                            )
                        }
                    },
                ),
                CatalogGroup(
                    id = "plugin_keys_source",
                    title = "Add a plugin source",
                    items = listOf(
                        TextInputItem(
                            id = "plugin_keys_source_url",
                            title = "Source address",
                            subtitle = "A GitHub plugin repo (https://github.com/<owner>/<repo>), a catalog index ending in .json, " +
                                "or a plain https address. droidtop fetches the key it publishes and asks you to trust it below; " +
                                "plain http addresses are refused",
                            value = pendingKeySourceUrl,
                            onChange = { _, v -> pendingKeySourceUrl = v.trim() },
                        ),
                        AsyncActionItem(
                            id = "plugin_keys_source_fetch",
                            title = "Fetch this source's key",
                            subtitle = "Trust-on-first-use: nothing is trusted until you confirm it below",
                            run = { ctx, onStatus -> fetchSourceKey(ctx, pendingKeySourceUrl, onStatus) },
                        ),
                    ),
                ),
            ) + listOfNotNull(
                proposal?.let { proposedKeyGroup(it) },
                CatalogGroup(
                    id = "plugin_keys_manual",
                    title = "Add a key by hand (for sources that publish no key)",
                    items = listOf(
                        TextInputItem(
                            id = "plugin_keys_manual_origin",
                            title = "Origin id",
                            subtitle = "The origin's own id, e.g. acme; every plugin it signs carries it in its name (<origin>.<name>). " +
                                "It can never be \"${PluginOriginKeys.OFFICIAL_ORIGIN}\": that is the official origin",
                            value = pendingKeyManualOrigin,
                            onChange = { _, v -> pendingKeyManualOrigin = v.trim() },
                        ),
                        TextInputItem(
                            id = "plugin_keys_manual_key",
                            title = "Public key",
                            subtitle = "The origin's public key, pasted exactly as its author publishes it",
                            // The value column beside a row's title is narrow:
                            // the whole ~124-char base64 squeezed this row's
                            // title and description out of the row once one
                            // was saved (the Origin id row above kept both,
                            // Droidtop/tracker#51), so the row shows a
                            // truncated prefix and the buffer keeps the full
                            // key "Add this key" below reads.
                            value = pendingKeyManualKey.take(16) + if (pendingKeyManualKey.length > 16) "…" else "",
                            multiline = true,
                            onChange = { _, v -> pendingKeyManualKey = v.trim() },
                        ),
                        AsyncActionItem(
                            id = "plugin_keys_manual_add",
                            title = "Add this key",
                            subtitle = "Shown as \"Added by you\" on every plugin it signs -- third-party, never official",
                            run = { ctx, _ -> addManualKey(ctx, pendingKeyManualOrigin, pendingKeyManualKey) },
                        ),
                        DocumentPickItem(
                            id = "plugin_keys_manual_file",
                            title = "Add key from a file",
                            subtitle = "A droidtop-plugin-key.json file (it holds the origin and the key), or a plain key file with the Origin id filled in above",
                            // Not application/json: many file managers label a
                            // .json as octet-stream, which a narrower filter
                            // would hide. The file is parsed before it is kept.
                            mimeType = "*/*",
                            onPicked = { ctx, uri -> importKeyFile(ctx, uri) },
                        ),
                    ),
                ),
            )
        },
    )

    /** The fetched-key review: WHO, WHICH key, what trusting does NOT mean -- and the changed-key warning when it is one. */
    private fun proposedKeyGroup(proposal: ProposedKey): CatalogGroup {
        val fingerprint = UserOriginKeys.fingerprint(proposal.keyBase64) ?: "unreadable"
        val existingFingerprint = proposal.existing?.let { UserOriginKeys.fingerprint(it.keyBase64) ?: "unreadable" }
        return CatalogGroup(
            id = "plugin_keys_proposal",
            title = "Review before trusting",
            items = listOf(
                ActionItem(
                    id = "plugin_keys_proposal_info",
                    title = if (proposal.existing == null) {
                        "Origin \"${proposal.origin}\", from ${proposal.sourceUrl}"
                    } else {
                        "WARNING: ${proposal.sourceUrl} now publishes a DIFFERENT key for \"${proposal.origin}\""
                    },
                    subtitle = if (proposal.existing == null) {
                        "Key fingerprint $fingerprint. Third-party, not official: droidtop has not vetted it, so trusting it " +
                            "is your call; plugins from it will show as \"Added by you\"."
                    } else {
                        "You trusted fingerprint $existingFingerprint; this fetch publishes $fingerprint. A changed key can " +
                            "mean the source rotated it, or that the source or this fetch is compromised, and droidtop cannot " +
                            "tell the two apart. Nothing has been changed yet; replacing the stored key means plugins signed " +
                            "by the OLD key stop running."
                    },
                    run = {},
                ),
                AsyncActionItem(
                    id = "plugin_keys_proposal_trust",
                    title = if (proposal.existing == null) {
                        "Trust origin \"${proposal.origin}\" from this source"
                    } else {
                        "Replace the stored key for \"${proposal.origin}\""
                    },
                    subtitle = if (proposal.existing == null) {
                        "Updates from this source are then verified against this key automatically"
                    } else {
                        "Only do this if you have reason to believe the source really rotated its key"
                    },
                    confirmTitle = if (proposal.existing == null) {
                        "Trust origin \"${proposal.origin}\" and everything it signs?"
                    } else {
                        "Replace the trusted key for \"${proposal.origin}\"? Plugins signed by the old key will stop running."
                    },
                    run = { ctx, _ -> commitTrustedKey(ctx, proposal) },
                ),
                ActionItem(
                    id = "plugin_keys_proposal_discard",
                    title = "Discard",
                    subtitle = "Trust nothing, change nothing",
                    run = { _ -> pendingKeyProposal = null },
                ),
            ),
        )
    }

    /**
     * The fetch half of trust-on-first-use: fetches the source's
     * published key (validating it) and only ever PROPOSES it -- the
     * group above is where the user decides. Runs on the renderers' IO
     * dispatcher (an [AsyncActionItem] run), like every other network
     * action on this screen.
     */
    private fun fetchSourceKey(context: Context, url: String, onStatus: (String) -> Unit): String {
        if (url.isBlank()) return "Type the source's address first"
        onStatus("Fetching $url...")
        return when (val fetched = PluginSourceKeys.fetchKey(url, dev.droidtop.net.GitHubTokenStore.get(context))) {
            is PluginSourceKeys.FetchResult.Failed -> fetched.reason
            is PluginSourceKeys.FetchResult.Fetched -> {
                val published = fetched.key
                val existing = UserOriginKeys.load(UserOriginKeys.storeFile(context))[published.origin]
                when {
                    existing != null && UserOriginKeys.fingerprint(published.keyBase64) == UserOriginKeys.fingerprint(existing.keyBase64) ->
                        "Origin \"${published.origin}\" is already trusted with this same key -- nothing to do"
                    existing != null -> {
                        pendingKeyProposal = ProposedKey(published.origin, published.keyBase64, url, existing)
                        "This source now publishes a DIFFERENT key for \"${published.origin}\" than you trusted. Review the warning below -- nothing has been changed."
                    }
                    else -> {
                        pendingKeyProposal = ProposedKey(published.origin, published.keyBase64, url, null)
                        "Origin \"${published.origin}\" wants to be trusted. Review it below -- nothing is trusted until you confirm."
                    }
                }
            }
        }
    }

    /** The confirm half: writes the proposal (add, or the explicitly-confirmed replace) and clears it. */
    private fun commitTrustedKey(context: Context, proposal: ProposedKey): String {
        val store = UserOriginKeys.storeFile(context)
        val outcome = if (proposal.existing == null) {
            UserOriginKeys.add(store, proposal.origin, proposal.keyBase64, proposal.sourceUrl)
        } else {
            UserOriginKeys.replace(store, proposal.origin, proposal.keyBase64, proposal.sourceUrl)
        }
        val message = when (outcome) {
            is AddKeyOutcome.Added ->
                if (proposal.existing == null) {
                    "Origin \"${proposal.origin}\" is now trusted -- plugins it signs will verify against this key"
                } else {
                    "Replaced the key for \"${proposal.origin}\" -- plugins signed by the OLD key no longer verify"
                }
            AddKeyOutcome.AlreadyTrustedSameKey -> "Origin \"${proposal.origin}\" is already trusted with this same key"
            is AddKeyOutcome.KeyChanged ->
                "The stored key for \"${proposal.origin}\" changed since this fetch -- fetch the source again and review the warning"
            is AddKeyOutcome.Refused -> "Refused: ${outcome.reason}"
        }
        pendingKeyProposal = null
        PluginStatusWidgetProvider.requestUpdate(context)
        return message
    }

    private fun addManualKey(context: Context, origin: String, key: String): String {
        if (origin.isBlank()) return "Type the origin's id first"
        if (key.isBlank()) return "Paste the origin's public key first"
        return addKeyOutcomeMessage(UserOriginKeys.add(UserOriginKeys.storeFile(context), origin, key, source = null))
    }

    /**
     * A picked key file: the published JSON shape (origin + key, the
     * same two fields a source publishes) or, failing that, a raw
     * base64 key file whose origin comes from the Origin id field.
     * Runs on the renderers' IO dispatcher, like every other
     * [DocumentPickItem] onPicked.
     */
    private fun importKeyFile(context: Context, uri: Uri): String {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return "Couldn't read that file"
        val store = UserOriginKeys.storeFile(context)
        PluginSourceKeys.parsePublishedKey(text)?.let { published ->
            return addKeyOutcomeMessage(UserOriginKeys.add(store, published.origin, published.keyBase64, source = null))
        }
        if (pendingKeyManualOrigin.isBlank()) {
            return "That file isn't a droidtop-plugin-key.json (it needs \"origin\" and \"key\"), " +
                "and the Origin id field above is empty; fill it in to add a plain key file"
        }
        return addKeyOutcomeMessage(UserOriginKeys.add(store, pendingKeyManualOrigin, text, source = null))
    }

    /** The one wording for what [UserOriginKeys.add] decided, shared by every manual add path. */
    private fun addKeyOutcomeMessage(outcome: AddKeyOutcome): String = when (outcome) {
        is AddKeyOutcome.Added ->
            "Origin \"${outcome.entry.origin}\" is now trusted -- plugins it signs will verify against this key"
        AddKeyOutcome.AlreadyTrustedSameKey ->
            "That origin is already trusted with this same key"
        is AddKeyOutcome.KeyChanged ->
            "Refused: \"${outcome.stored.origin}\" is already trusted with a DIFFERENT key. If its source really rotated " +
                "the key, add the source's address above and fetch it so you can compare both fingerprints -- or stop " +
                "trusting the origin first, then add the new key."
        is AddKeyOutcome.Refused -> "Refused: ${outcome.reason}"
    }

    // ------------------------------------------------------------------
    // ROM folders (games roots).
    // ------------------------------------------------------------------

    // Buffers the typed path between the field and the Add action, the
    // same pending-buffer shape addCustomPlayerScreen uses.
    private var pendingRootPath = ""

    private fun romFoldersScreen() = CatalogScreen(
        id = SCREEN_ROM_FOLDERS,
        // One name for one concept: the Settings row that opens this
        // screen already said "Game folders" and the screen itself said
        // "ROM folders", one navigation step apart -- and the folders are
        // not only ROMs: engine and Windows games are found in them too.
        title = "Game folders",
        subtitle = "Console games go in a folder named for their system (snes, psx) inside one of these; " +
            "PC and engine games can be anywhere under them. Rescan the library after a change",
        groups = { context ->
            listOf(
                CatalogGroup(
                    id = "rom_folders_add",
                    title = null,
                    items = listOf(
                        FolderPickItem(
                            id = "rom_folders_pick",
                            title = "Add a folder",
                            subtitle = "An SD card, a second internal folder, anywhere ROMs live",
                            onPicked = { ctx, uri: Uri ->
                                val resolved = GamesRootPrefs.resolveStoragePath(uri)
                                if (resolved != null) {
                                    GamesRootPrefs.addGamesRoot(ctx, resolved)
                                    null
                                } else {
                                    "Couldn't resolve that folder to a real path on this device — not added"
                                }
                            },
                        ),
                    ),
                ),
                CatalogGroup(
                    id = "rom_folders_add_by_path",
                    title = "Add a folder by path",
                    items = listOf(
                        TextInputItem(
                            id = "rom_folders_path",
                            title = "Folder path",
                            subtitle = "The picker only offers what Android calls a storage volume. " +
                                "An emulator's host share (BlueStacks' /mnt/windows/BstSharedFolder), " +
                                "a mount a rooted device adds itself, or a USB drive under /mnt is none " +
                                "of those, and is typed here instead",
                            value = pendingRootPath,
                            onChange = { _, value -> pendingRootPath = value },
                        ),
                        AsyncActionItem(
                            id = "rom_folders_path_add",
                            title = "Add this folder",
                            subtitle = "Checked for real before it is stored: it must exist, be a folder, and be readable",
                            run = { ctx, _ ->
                                val typed = pendingRootPath
                                val error = withContext(Dispatchers.IO) {
                                    GamesRootPrefs.addGamesRootByPath(ctx, typed)
                                }
                                if (error == null) {
                                    pendingRootPath = ""
                                    "Added " + typed.trim()
                                } else {
                                    error
                                }
                            },
                        ),
                    ),
                ),
                CatalogGroup(
                    id = "rom_folders_rescan",
                    title = null,
                    // The same action Gaming settings offers, not a second
                    // one: this screen changes WHICH folders are scanned,
                    // so it is where a user wants to act on that change.
                    items = listOf(
                        GamingSettingsCatalog.rescanLibraryItem(),
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
                        ),
                    ),
                ),
                CatalogGroup(
                    id = "rom_folders_list",
                    title = "Scanned folders",
                    items = GamesRootPrefs.gamesRootPaths(context).sorted().map { path ->
                        ActionItem(
                            id = "rom_folder_$path",
                            title = path,
                            subtitle = "Select twice to stop scanning this folder",
                            confirmTitle = "Remove $path?",
                            run = { ctx -> GamesRootPrefs.removeGamesRoot(ctx, path) },
                        )
                    }.ifEmpty {
                        listOf(ActionItem(id = "rom_folders_none", title = "No game folders configured", run = {}))
                    },
                ),
            )
        },
    )

    // ------------------------------------------------------------------
    // Scraper source + credentials.
    // ------------------------------------------------------------------

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
                            options = dev.droidtop.library.scraper.PcScraperSource.entries.map {
                                ChoiceOption(it.name, it.label)
                            },
                            current = dev.droidtop.library.scraper.PcScraperSourcePrefs.get(context).name,
                            onSelect = { ctx, value ->
                                dev.droidtop.library.scraper.PcScraperSourcePrefs.set(
                                    ctx,
                                    dev.droidtop.library.scraper.PcScraperSource.valueOf(value),
                                )
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
                            subtitle = "Turns a box wider than it is tall on its side, so it takes the same space as an upright one",
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
                            subtitle = "The settings backup in Global settings includes everything here, " +
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
                                    run = {},
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
