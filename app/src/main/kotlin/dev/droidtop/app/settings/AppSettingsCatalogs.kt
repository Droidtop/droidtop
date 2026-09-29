package dev.droidtop.app.settings

import android.content.Context
import android.net.Uri
import dev.droidtop.app.GamesRootPrefs
import dev.droidtop.app.PluginStatusWidgetProvider
import dev.droidtop.library.scraper.importGamelistXml
import dev.droidtop.library.scraper.scrapeSystemArtwork
import dev.droidtop.library.consoles.ConsoleSystemDef
import dev.droidtop.library.consoles.canResolveFromFolder
import dev.droidtop.library.consoles.ConsoleSystemEntity
import dev.droidtop.library.consoles.ConsoleSystemsDatabase
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.consoles.CustomPlayerPrefs
import dev.droidtop.library.consoles.PlayerOverridePrefs
import dev.droidtop.library.consoles.PlatformDatabaseSnapshot
import dev.droidtop.library.consoles.PlatformDatabaseSource
import dev.droidtop.library.consoles.PlatformDatabases
import dev.droidtop.library.consoles.SystemFolders
import dev.droidtop.library.consoles.SystemOverridePrefs
import dev.droidtop.library.consoles.BiosDatabase
import dev.droidtop.library.consoles.KnownPlayers
import dev.droidtop.library.consoles.SystemBiosSpec
import dev.droidtop.library.consoles.availablePlayers
import dev.droidtop.library.consoles.libretroCoreId
import dev.droidtop.library.integrations.IntegrationCapability
import dev.droidtop.library.integrations.IntegrationPlaceholders
import dev.droidtop.library.integrations.IntegrationStore
import dev.droidtop.library.integrations.AcquireContentSources
import dev.droidtop.library.integrations.PluginEventBus
import dev.droidtop.library.integrations.PluginAppStatus
import dev.droidtop.library.integrations.PluginCatalog
import dev.droidtop.library.integrations.PluginCatalogScreen
import dev.droidtop.library.integrations.PluginSettingsRows
import dev.droidtop.library.integrations.PluginJobsScreen
import dev.droidtop.library.PcFolderScan
import dev.droidtop.library.GameEngineDetector
import dev.droidtop.library.EnginesDatabase
import dev.droidtop.library.ScanPrune
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.pluginhost.ApiResolution
import dev.droidtop.pluginhost.GrantState
import dev.droidtop.pluginhost.PluginApiResolver
import dev.droidtop.pluginhost.PluginAudit
import dev.droidtop.pluginhost.PluginGrants
import dev.droidtop.pluginhost.PluginPermissions
import dev.droidtop.pluginhost.PluginProviderChoices
import dev.droidtop.pluginhost.PluginKind
import dev.droidtop.pluginhost.PluginTrustState
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginConsent
import dev.droidtop.pluginhost.PermissionTier
import dev.droidtop.pluginhost.ExtensionPoints
import dev.droidtop.pluginhost.PluginCrashPolicy
import dev.droidtop.pluginhost.PluginOriginKeys
import dev.droidtop.pluginhost.PluginSourceKeys
import dev.droidtop.pluginhost.PythonRuntimeManager
import dev.droidtop.pluginhost.FlutterRuntimeManager
import dev.droidtop.pluginhost.UserOriginKey
import dev.droidtop.pluginhost.UserOriginKeys
import dev.droidtop.pluginhost.AddKeyOutcome
import dev.droidtop.library.consoles.resolvePlayer
import dev.droidtop.library.scraper.ScraperPrefs
import dev.droidtop.library.scraper.ScraperSource
import dev.droidtop.library.scraper.ScraperSourcePrefs
import dev.droidtop.library.scraper.ScreenScraperPrefs
import dev.droidtop.library.scraper.TheGamesDbPrefs
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
    const val SCREEN_INTEGRATIONS = "integrations"
    const val SCREEN_PLUGINS = "plugins"
    const val SCREEN_PLUGIN_KEYS = "plugin_keys"
    const val SCREEN_JOBS = "plugin_jobs"
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
        SettingsScreenRegistry.register(romFoldersScreen())
        SettingsScreenRegistry.register(scraperScreen())
        SettingsScreenRegistry.register(platformsScreen())
        SettingsScreenRegistry.register(integrationsScreen())
        SettingsScreenRegistry.register(pluginsScreen())
        SettingsScreenRegistry.register(pluginKeysScreen())
        SettingsScreenRegistry.register(PluginJobsScreen.screen())
        SettingsScreenRegistry.register(windowsGamesScreen())
        SettingsScreenRegistry.register(pcStoresScreen())
        SettingsScreenRegistry.register(accountsAndSourcesScreen())
        SettingsScreenRegistry.register(androidSettingsScreen())
        SettingsScreenRegistry.register(enginehostScreen())
        SettingsScreenRegistry.register(updatesScreen())
        SettingsScreenRegistry.register(F95ImportCatalog.screen())
        SettingsScreenRegistry.register(DroidtopWideSettings.globalScreen())
        SettingsScreenRegistry.register(DroidtopWideSettings.desktopScreen())
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
        // The per-system deep link the gamelist options menu's "System
        // settings" row opens (docs/SPEC.md "One consistent way into
        // Settings"): the SAME builder re-opened with the system id the
        // menu was opened from -- one screen, parameterized, never a
        // second folder/emulator picker.
        forDeepLink = { deepLinkedSystemId -> consoleSystemsScreen(deepLinkedSystemId) },
    )

    private suspend fun consoleSystemsGroups(context: Context, systemId: String? = null): List<CatalogGroup> = withContext(Dispatchers.IO) {
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        // The library's own answer (SystemFolders), not a second walk:
        // the folders it scans as console systems, plus the ones the person
        // picked to choose a system for. A folder the library reads as PC
        // or engine games is not a console system folder and is not here.
        val rawFolders = SystemFolders.all(context, systemsById).map { it.first }
            .plus(SystemFolders.awaitingSystem(context))
            .distinctBy { it.absolutePath }
            .sortedBy { it.name.lowercase() }
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
                        subtitle = "Refresh players, platforms, engine routing, and BIOS registry from " +
                            "droidtop-platforms on GitHub. This build was seeded from " +
                            (PlatformDatabaseSnapshot.shortCommit(context)
                                ?.let { "snapshot $it" } ?: "an unrecorded snapshot") +
                            "; the same refresh also runs on the update schedule",
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
                        subtitle = "Base URL the database index and its files are fetched from; blank restores the default",
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
                } else if (classifiedFolders.isEmpty()) {
                    listOf<CatalogItem>(
                        ActionItem(
                            id = "console_systems_no_folders",
                            title = "No console system folders found",
                            subtitle = "Name a folder after its system (snes, psx, ...) inside a games folder, or choose one below",
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
                                    subtitle = "${kind.storeName} (PC games, detected per game: $gameText)",
                                    inline = folderScreen(folder, kind),
                                    valueLabel = { gameText },
                                )
                            }
                            is FolderKind.Engine -> {
                                val gameText = if (kind.gameCount == 1) "1 game" else "${kind.gameCount} games"
                                NestedScreenItem(
                                    id = "console_folder_${folder.absolutePath}",
                                    title = folder.name,
                                    subtitle = "Engine games (detected per game: $gameText)",
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
                                    picked == null -> "Couldn't resolve that folder to a real path on this device"
                                    roots.none { picked.absolutePath.startsWith(it) } -> "That folder is not inside one of your game folders"
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
                                            subtitle = "PC games from ${kind.storeName} are detected per game ($gameText). " +
                                                "They appear in the PC games list (Gaming shell > PC tab).",
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
                                            subtitle = "Engine games (Ren'Py, RPG Maker, etc.) are detected per game ($gameText). " +
                                                "They appear in the PC games list (Gaming shell > PC tab) and launch via Enginehost.",
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
                                            add(playerChoiceItem(context, resolved))
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
                                                    id = "folder_add_player_${resolved.id}",
                                                    title = "Add a custom player",
                                                    subtitle = "Point ${resolved.displayName} at any installed app via am start arguments",
                                                    inline = addCustomPlayerScreen(resolved),
                                                ),
                                            )
                                            add(
                                                AsyncActionItem(
                                                    id = "folder_scrape_${folder.absolutePath}",
                                                    title = "Scrape missing artwork & metadata",
                                                    subtitle = "Fills box art, descriptions, ratings and more for games that lack them",
                                                    run = { ctx, onStatus ->
                                                        scrapeSystemArtwork(ctx, folder, resolved) { done, total ->
                                                            onStatus("Scraping ${resolved.displayName}: $done/$total")
                                                        }
                                                    },
                                                ),
                                            )
                                            add(
                                                AsyncActionItem(
                                                    id = "folder_gamelist_${folder.absolutePath}",
                                                    title = "Import gamelist.xml",
                                                    subtitle = "Ingests an external scraper's output (Skraper, Skyscraper, ARRM, ES-DE) " +
                                                        "for this folder: metadata into droidtop, media referenced where it sits",
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
                                                    subtitle = "Search an installed acquire_content plugin or integration for ${resolved.displayName}",
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
                    CatalogGroup(
                        id = "bios_tools",
                        title = null,
                        items = listOf(
                            // The one refresh (SPEC 7e2): the BIOS registry is
                            // one of the four databases it brings up to date.
                            AsyncActionItem(
                                id = "bios_update_db",
                                title = "Update platform databases",
                                subtitle = "Refresh the BIOS registry, with players, platforms and engine routing, from droidtop-platforms on GitHub",
                                run = { ctx, onStatus -> PlatformDatabases.refresh(ctx, onStatus) },
                            ),
                        ),
                    ),
                )
            }
        },
    )

    private fun installPackageAction(pkg: String): (Context) -> Unit = { ctx ->
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

    private fun playerChoiceItem(context: Context, system: ConsoleSystemDef): ChoiceItem {
        val players = availablePlayers(context, system)
        return ChoiceItem(
            id = "system_player_${system.id}",
            title = "Player",
            subtitle = if (players.isEmpty()) {
                "No installed emulator can run ${system.displayName} yet — add a custom player below, or install one"
            } else {
                "Which installed emulator launches ${system.displayName}"
            },
            options = listOf(ChoiceOption("", "(first installed)")) + players.map { ChoiceOption(it.id, it.name) },
            current = PlayerOverridePrefs.get(context, system.id) ?: "",
            onSelect = { ctx, value ->
                PlayerOverridePrefs.set(ctx, system.id, value.ifEmpty { null })
                // docs/SPEC.md 12a "Event hooks": this is THE write path
                // that changes a system's default player, so it is the
                // one place that fires PluginEvent.DEFAULT_PLAYER_CHANGED
                // -- a resolved player (the one actually chosen, "first
                // installed" included, not just an explicit override) so
                // a subscribed plugin sees the real effective choice, and
                // a resolved core: the chosen entry's own LIBRETRO core
                // when its template names one, else the system's
                // configured core.
                val chosenPlayer = players.firstOrNull { it.id == value } ?: players.firstOrNull()
                if (chosenPlayer != null) {
                    PluginEventBus.notifyDefaultPlayerChangedAsync(
                        context = ctx,
                        systemId = system.id,
                        systemName = system.displayName,
                        playerId = chosenPlayer.id,
                        playerName = chosenPlayer.name,
                        playerPackage = (chosenPlayer as? dev.droidtop.library.consoles.Player.AmStart)?.packageName,
                        core = libretroCoreId(chosenPlayer, system.retroArchCore),
                    )
                }
            },
        )
    }

    // Pending-buffer form: fields buffer here, Save commits atomically.
    private fun addCustomPlayerScreen(system: ConsoleSystemDef): CatalogScreen {
        var name = ""
        var pkg = ""
        var args = "-a android.intent.action.VIEW\n-n org.example.app/.MainActivity\n-d {file.uri}"
        var kill = false
        return CatalogScreen(
            id = "add_player_${system.id}",
            title = "Add a player for ${system.displayName}",
            subtitle = "Use {file.path} and {file.uri} in the arguments for the file being played",
            groups = { _ ->
                listOf(
                    CatalogGroup(
                        id = "add_player_form",
                        title = null,
                        items = listOf(
                            TextInputItem(
                                id = "add_player_name",
                                title = "Player name",
                                value = name,
                                onChange = { _, v -> name = v },
                            ),
                            TextInputItem(
                                id = "add_player_pkg",
                                title = "Package name",
                                subtitle = "e.g. org.example.app",
                                value = pkg,
                                onChange = { _, v -> pkg = v },
                            ),
                            TextInputItem(
                                id = "add_player_args",
                                title = "am start arguments",
                                value = args,
                                multiline = true,
                                onChange = { _, v -> args = v },
                            ),
                            ToggleItem(
                                id = "add_player_kill",
                                title = "Kill package processes before launch",
                                current = kill,
                                onToggle = { _, v -> kill = v },
                            ),
                            ActionItem(
                                id = "add_player_save",
                                title = "Save player",
                                subtitle = "Needs a name, a package, and arguments",
                                run = { ctx ->
                                    if (pkg.isNotBlank() && args.isNotBlank()) {
                                        CustomPlayerPrefs.add(ctx, system.id, name.ifBlank { pkg }, args, pkg, kill)
                                        name = ""
                                        pkg = ""
                                        kill = false
                                    }
                                },
                            ),
                        ),
                    ),
                )
            },
        )
    }

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
        subtitle = "The native VN/RPG engine runtime droidtop launches engine games through",
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
                                    subtitle = "Engine games fall back to Wine/Linux strategies until it is",
                                    run = {},
                                ),
                            )
                            return@buildList
                        }
                        add(
                            ActionItem(
                                id = "enginehost_settings",
                                title = "Enginehost settings",
                                subtitle = "Opens Enginehost's own global configuration",
                                run = { ctx ->
                                    ctx.startActivity(dev.droidtop.library.EngineHost.settingsIntent())
                                },
                            ),
                        )
                        add(
                            ActionItem(
                                id = "enginehost_saves",
                                title = "Save storage",
                                subtitle = "Shared save root and migration, in Enginehost's own screen",
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
                                subtitle = "Launching an engine game offers the matching bundle; auto-install is used when droidtop's detection is confident",
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
        title = "Software updates",
        subtitle = "Check for and install newer droidtop builds from the project's own releases",
        groups = { context ->
            val update = dev.droidtop.app.update.AppSelfUpdate
            listOf(
                CatalogGroup(
                    id = "updates_droidtop",
                    title = null,
                    items = listOf(
                        ChoiceItem(
                            id = "updates_frequency",
                            title = "Check for updates",
                            subtitle = "Fetches one small file describing the latest build; nothing about this " +
                                "device or your library is sent. Never stops automatic checks; Check now still works",
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
                            run = { ctx -> ctx.startActivity(controls.appDetailsIntent(ctx)) },
                        ),
                        ActionItem(
                            id = "grant_write_settings",
                            title = "Modify system settings",
                            // State in the value column, what it is for in
                            // the subtitle (UI pass 2026-09-24, M14).
                            subtitle = "Lets droidtop change brightness, screen timeout and auto-rotate",
                            value = if (controls.canWriteBrightness(context)) "Granted" else "Not granted",
                            run = { ctx -> ctx.startActivity(controls.brightnessGrantIntent(ctx)) },
                        ),
                        ActionItem(
                            id = "grant_dnd",
                            title = "Do Not Disturb access",
                            subtitle = "Lets droidtop turn Do Not Disturb on and off",
                            value = if (controls.hasDndAccess(context)) "Granted" else "Not granted",
                            run = { ctx -> ctx.startActivity(controls.dndGrantIntent()) },
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
                            run = { ctx -> ctx.startActivity(link.intent) },
                        )
                    },
                ),
            )
        },
    )

    private fun windowsGamesScreen() = CatalogScreen(
        id = SCREEN_WINDOWS_GAMES,
        title = "Windows games",
        subtitle = "The Wine environment Windows games run inside, and the folders it can reach",
        groups = { context -> windowsGamesGroups(context) },
    )

    private suspend fun windowsGamesGroups(context: Context): List<CatalogGroup> {
        val runtime = dev.droidtop.library.PcGameRuntimeRegistry.runtime
        val roots = withContext(Dispatchers.IO) { GamesRootPrefs.gamesRootPaths(context).sorted() }
        val provisioned = withContext(Dispatchers.IO) { runtime?.isProvisioned == true }

        return listOf(
            CatalogGroup(
                id = "windows_steam",
                title = "Steam",
                items = listOf(
                    ActionItem(
                        id = "windows_steam_account",
                        title = "Steam account and library",
                        subtitle = "Sign in (QR or password), browse your games, and download them here",
                        run = { ctx ->
                            ctx.startActivity(
                                android.content.Intent(ctx, dev.droidtop.app.SteamLoginActivity::class.java)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                    ),
                ),
            ),
            CatalogGroup(
                id = "windows_setup",
                title = null,
                items = buildList {
                    if (runtime == null) {
                        add(
                            ActionItem(
                                id = "windows_unavailable",
                                title = "Windows support isn't loaded",
                                subtitle = "This build has no PC runtime registered, so there is nothing to set up",
                                run = {},
                            ),
                        )
                        return@buildList
                    }
                    add(
                        AsyncActionItem(
                            id = "windows_provision",
                            title = if (provisioned) "Reinstall the Windows environment" else "Set up Windows games",
                            subtitle = if (provisioned) {
                                "Already set up. Running this again only reinstalls what is missing or out of date."
                            } else {
                                "Downloads and installs Wine's system files once, then maps your game folders into it. Several hundred megabytes."
                            },
                            run = { ctx, onStatus ->
                                val gamesRoots = GamesRootPrefs.gamesRootPaths(ctx).map { File(it) }
                                val result = dev.droidtop.library.PcGameRuntimeRegistry.runtime
                                    ?.provision(gamesRoots, onStatus)
                                when {
                                    result == null -> "Windows support isn't loaded in this build"
                                    result.succeeded -> result.detail
                                    else -> "Setup failed: ${result.detail}"
                                }
                            },
                        ),
                    )
                },
            ),
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
        val signedInCount = withContext(Dispatchers.IO) { signedInStoreCount(context) }

        return listOf(
            CatalogGroup(
                id = "pc_stores_accounts",
                title = null,
                items = listOf(
                    NestedScreenItem(
                        id = "pc_stores_accounts_link",
                        title = "Accounts and sources",
                        subtitle = "Sign in to Steam, GOG, Epic, Amazon Games or itch.io to download your library",
                        registryId = SCREEN_ACCOUNTS_AND_SOURCES,
                        valueLabel = { "$signedInCount of 5 stores signed in" },
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
                    ActionItem(
                        id = "pc_stores_downloads",
                        title = "Downloads",
                        subtitle = "What is downloading or waiting, and the storage it is going into",
                        run = { ctx -> ctx.startActivity(dev.droidtop.app.PcStoreActivity.intent(ctx, entryId = null)) },
                    ),
                ),
            ),
        )
    }

    private suspend fun signedInStoreCount(context: Context): Int {
        val steam = runCatching { app.gamenative.utils.SteamUtils.hasStoredCredentials() }.getOrDefault(false)
        val gog = runCatching { app.gamenative.service.gog.GOGService.hasStoredCredentials(context) }.getOrDefault(false)
        val epic = runCatching { app.gamenative.service.epic.EpicService.hasStoredCredentials(context) }.getOrDefault(false)
        val amazon = runCatching { app.gamenative.service.amazon.AmazonService.hasStoredCredentials(context) }.getOrDefault(false)
        val itch = runCatching { app.gamenative.service.itch.ItchService.hasStoredCredentials(context) }.getOrDefault(false)
        return listOf(steam, gog, epic, amazon, itch).count { it }
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
        fun signedIn(yes: Boolean): String = if (yes) "Signed in" else "Not signed in yet"
        val steamSignedIn = runCatching { app.gamenative.utils.SteamUtils.hasStoredCredentials() }.getOrDefault(false)
        val gogSignedIn = runCatching { app.gamenative.service.gog.GOGService.hasStoredCredentials(context) }.getOrDefault(false)
        val epicSignedIn = runCatching { app.gamenative.service.epic.EpicService.hasStoredCredentials(context) }.getOrDefault(false)
        val amazonSignedIn = runCatching { app.gamenative.service.amazon.AmazonService.hasStoredCredentials(context) }.getOrDefault(false)
        val itchSignedIn = runCatching { app.gamenative.service.itch.ItchService.hasStoredCredentials(context) }.getOrDefault(false)

        val activeIntegrations = IntegrationStore.available(context).size
        val installedPlugins = PluginStore.installed(context)
        val githubTokenLabel = if (dev.droidtop.pluginhost.GitHubTokenStore.isSet(context)) "Set" else "Not set"
        val pluginsValueLabel = when {
            installedPlugins.isEmpty() -> "none"
            installedPlugins.any { it.trust == PluginTrustState.PENDING } ->
                "${installedPlugins.count { it.trust == PluginTrustState.PENDING }} awaiting approval"
            else -> "${installedPlugins.count { it.runnable() }} active"
        }

        listOf(
            CatalogGroup(
                id = "accounts_stores",
                title = "Store accounts",
                items = listOf(
                    ActionItem(
                        id = "pc_store_steam",
                        title = "Steam",
                        subtitle = "${signedIn(steamSignedIn)} - sign in with a QR code or a password, and download your games",
                        run = { ctx ->
                            ctx.startActivity(
                                android.content.Intent(ctx, dev.droidtop.app.SteamLoginActivity::class.java)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                    ),
                    ActionItem(
                        id = "pc_store_gog",
                        title = "GOG",
                        subtitle = "${signedIn(gogSignedIn)} - signs in on GOG's own page",
                        run = { ctx ->
                            ctx.startActivity(
                                dev.droidtop.app.PcStoreSignInActivity.intent(
                                    ctx,
                                    dev.droidtop.app.PcStoreSignInActivity.Store.GOG,
                                ),
                            )
                        },
                    ),
                    ActionItem(
                        id = "pc_store_epic",
                        title = "Epic Games",
                        subtitle = "${signedIn(epicSignedIn)} - signs in on Epic's own page",
                        run = { ctx ->
                            ctx.startActivity(
                                dev.droidtop.app.PcStoreSignInActivity.intent(
                                    ctx,
                                    dev.droidtop.app.PcStoreSignInActivity.Store.EPIC,
                                ),
                            )
                        },
                    ),
                    ActionItem(
                        id = "pc_store_amazon",
                        title = "Amazon Games",
                        subtitle = "${signedIn(amazonSignedIn)} - signs in on Amazon's own page",
                        run = { ctx ->
                            ctx.startActivity(
                                dev.droidtop.app.PcStoreSignInActivity.intent(
                                    ctx,
                                    dev.droidtop.app.PcStoreSignInActivity.Store.AMAZON,
                                ),
                            )
                        },
                    ),
                    ActionItem(
                        id = "pc_store_itch",
                        title = "itch.io",
                        subtitle = "${signedIn(itchSignedIn)} - paste your personal API key from itch.io settings",
                        run = { ctx ->
                            ctx.startActivity(
                                dev.droidtop.app.ItchSignInActivity.intent(ctx),
                            )
                        },
                    ),
                ),
            ),
            CatalogGroup(
                id = "accounts_scrapers",
                title = "Scraper sources",
                items = listOf(
                    NestedScreenItem(
                        id = "accounts_screenscraper",
                        title = "ScreenScraper",
                        subtitle = "Works without an account; your own login raises how much you can scrape per day",
                        inline = screenScraperAccountScreen(),
                        valueLabel = {
                            if (ScreenScraperPrefs.userId(context).isBlank()) "No account (still works)" else "Signed in as ${ScreenScraperPrefs.userId(context)}"
                        },
                    ),
                    NestedScreenItem(
                        id = "accounts_thegamesdb",
                        title = "TheGamesDB",
                        subtitle = "Free API key from thegamesdb.net -- required before it can scrape at all",
                        inline = theGamesDbAccountScreen(),
                        valueLabel = { if (TheGamesDbPrefs.apiKey(context).isBlank()) "Not set" else "Configured" },
                    ),
                    NestedScreenItem(
                        id = "accounts_igdb",
                        title = "IGDB (PC & engine games)",
                        subtitle = "Your own free Twitch developer application credentials",
                        inline = igdbAccountScreen(),
                        valueLabel = { if (ScraperPrefs.clientId(context).isBlank()) "Not set" else "Configured" },
                    ),
                    NestedScreenItem(
                        id = "accounts_steamgriddb",
                        title = "SteamGridDB (PC & engine games)",
                        subtitle = "Covers, hero art, logos and icons for PC and engine games",
                        inline = steamGridDbAccountScreen(),
                        valueLabel = {
                            if (dev.droidtop.library.scraper.SteamGridDbPrefs.apiKey(context).isBlank()) "Not set" else "Configured"
                        },
                    ),
                ),
            ),
            CatalogGroup(
                id = "accounts_plugins",
                title = "Plugins and integrations",
                items = listOf(
                    NestedScreenItem(
                        id = "accounts_plugins_screen",
                        title = "Plugins",
                        subtitle = "Installed plugin code -- searched, approved and run in its own process, never droidtop's databases",
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
                    NestedScreenItem(
                        id = "accounts_github_token",
                        title = "GitHub token",
                        subtitle = "Your own token: lifts GitHub's request limit for plugin updates and reaches plugin sources in private repositories",
                        inline = githubTokenScreen(),
                        valueLabel = { githubTokenLabel },
                    ),
                    NestedScreenItem(
                        id = "accounts_jobs_screen",
                        title = "Jobs",
                        subtitle = "Plugin downloads and long-running actions, with progress and cancel",
                        registryId = SCREEN_JOBS,
                    ),
                ),
            ),
        )
    }

    private fun screenScraperAccountScreen() = CatalogScreen(
        id = "accounts_screenscraper_edit",
        title = "ScreenScraper account",
        subtitle = "Account fields only -- the developer ID droidtop registers with ScreenScraper is compiled in, never a user-facing field",
        groups = { context ->
            listOf(
                CatalogGroup(
                    id = "accounts_screenscraper_fields",
                    title = null,
                    items = listOf(
                        screenScraperField(context, "ss_user_id", "Username", ScreenScraperPrefs.userId(context)) { c, v ->
                            ScreenScraperPrefs.set(c, ScreenScraperPrefs.devId(c), ScreenScraperPrefs.devPassword(c), v, ScreenScraperPrefs.userPassword(c))
                        },
                        screenScraperField(context, "ss_user_password", "Password", ScreenScraperPrefs.userPassword(context), secret = true) { c, v ->
                            ScreenScraperPrefs.set(c, ScreenScraperPrefs.devId(c), ScreenScraperPrefs.devPassword(c), ScreenScraperPrefs.userId(c), v)
                        },
                    ),
                ),
            )
        },
    )

    private fun theGamesDbAccountScreen() = CatalogScreen(
        id = "accounts_thegamesdb_edit",
        title = "TheGamesDB",
        subtitle = "Free at thegamesdb.net",
        groups = { context ->
            listOf(
                CatalogGroup(
                    id = "accounts_thegamesdb_fields",
                    title = null,
                    items = listOf(
                        TextInputItem(
                            id = "tgdb_api_key",
                            title = "API key",
                            subtitle = "Free at thegamesdb.net -- required before TheGamesDB can scrape at all",
                            value = TheGamesDbPrefs.apiKey(context),
                            onChange = { c, v -> TheGamesDbPrefs.set(c, v.trim()) },
                        ),
                    ),
                ),
            )
        },
    )

    private fun igdbAccountScreen() = CatalogScreen(
        id = "accounts_igdb_edit",
        title = "IGDB",
        subtitle = "Your own free Twitch developer application, never droidtop's",
        groups = { context ->
            listOf(
                CatalogGroup(
                    id = "accounts_igdb_fields",
                    title = null,
                    items = listOf(
                        // The user's own credentials, never droidtop's:
                        // IGDB authenticates through a free, self-service
                        // Twitch developer application, and the ID and
                        // secret belong to whoever created it.
                        TextInputItem(
                            id = "igdb_client_id",
                            title = "Client ID",
                            subtitle = "Create an application at dev.twitch.tv/console -- free, instant, no approval queue",
                            value = ScraperPrefs.clientId(context),
                            onChange = { c, v -> ScraperPrefs.set(c, v.trim(), ScraperPrefs.clientSecret(c)) },
                        ),
                        TextInputItem(
                            id = "igdb_client_secret",
                            title = "Client Secret",
                            subtitle = "From the same Twitch application; stays on this device",
                            value = ScraperPrefs.clientSecret(context),
                            secret = true,
                            onChange = { c, v -> ScraperPrefs.set(c, ScraperPrefs.clientId(c), v.trim()) },
                        ),
                    ),
                ),
            )
        },
    )

    /**
     * The user's own GitHub token for plugin sources (docs/SPEC.md 12a
     * "GitHub token"). Pasted by the person, never created or filled by
     * droidtop; stored Keystore-encrypted, never in the settings backup,
     * shown masked, removable, and testable against api.github.com.
     */
    private fun githubTokenScreen() = CatalogScreen(
        id = "accounts_github_token_edit",
        title = "GitHub token",
        subtitle = "Sent only to api.github.com, github.com and raw.githubusercontent.com, only for plugin sources",
        groups = { context ->
            val (token, stored) = withContext(Dispatchers.IO) {
                dev.droidtop.pluginhost.GitHubTokenStore.get(context) to dev.droidtop.pluginhost.GitHubTokenStore.isSet(context)
            }
            listOf(
                CatalogGroup(
                    id = "accounts_github_token_fields",
                    title = null,
                    items = buildList {
                        add(
                            TextInputItem(
                                id = "github_token_value",
                                title = "Token",
                                subtitle = when {
                                    token != null -> "Stored encrypted on this device: ${dev.droidtop.pluginhost.GitHubTokenStore.masked(token)}"
                                    stored -> "A token is stored but can no longer be read on this device; paste it again"
                                    else -> "A fine-grained token with read access to the plugin repositories, or a classic token with repo scope for private ones"
                                },
                                // Never shows the stored value back: the field is for pasting a new one.
                                value = "",
                                secret = true,
                                onChange = { c, v ->
                                    if (v.isNotBlank()) {
                                        withContext(Dispatchers.IO) { dev.droidtop.pluginhost.GitHubTokenStore.set(c, v) }
                                    }
                                },
                            ),
                        )
                        if (token != null) {
                            add(
                                AsyncActionItem(
                                    id = "github_token_test",
                                    title = "Test",
                                    subtitle = "Asks api.github.com who this token is and how many requests it allows",
                                    run = { _, onStatus ->
                                        onStatus("Asking GitHub...")
                                        dev.droidtop.pluginhost.GitHubTokenStore.test(token)
                                    },
                                ),
                            )
                        }
                        if (stored) {
                            add(
                                ActionItem(
                                    id = "github_token_remove",
                                    title = "Remove token",
                                    subtitle = "Plugin sources go back to unauthenticated requests",
                                    confirmTitle = "Remove the GitHub token from this device?",
                                    run = { ctx -> dev.droidtop.pluginhost.GitHubTokenStore.clear(ctx) },
                                ),
                            )
                        }
                    },
                ),
            )
        },
    )

    private fun steamGridDbAccountScreen() = CatalogScreen(
        id = "accounts_steamgriddb_edit",
        title = "SteamGridDB",
        subtitle = "Free: sign in at steamgriddb.com, then Preferences > API",
        groups = { context ->
            listOf(
                CatalogGroup(
                    id = "accounts_steamgriddb_fields",
                    title = null,
                    items = listOf(
                        // The user's own key, stored like every credential
                        // here: droidtop ships none, and a key belongs to
                        // the steamgriddb.com account that made it.
                        TextInputItem(
                            id = "steamgriddb_api_key",
                            title = "API key",
                            subtitle = "Free: sign in at steamgriddb.com, then Preferences > API. " +
                                "Covers, hero art, logos and icons for PC and engine games",
                            value = dev.droidtop.library.scraper.SteamGridDbPrefs.apiKey(context),
                            secret = true,
                            onChange = { c, v -> dev.droidtop.library.scraper.SteamGridDbPrefs.set(c, v.trim()) },
                        ),
                    ),
                ),
            )
        },
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
                                        append(if (installed) integration.packageName else "${integration.packageName} is NOT installed, so this is hidden elsewhere")
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
    /** True on a debuggable build -- avoids needing android.buildFeatures.buildConfig just for this one debug-only plugin test action. */
    private fun ctxIsDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun pluginsScreen() = CatalogScreen(
        id = SCREEN_PLUGINS,
        title = "Plugins",
        subtitle = "Real code, run in its own process and approved by you — for crash containment, not as a security sandbox",
        groups = { context -> pluginsGroups(context) },
    )

    private suspend fun pluginsGroups(context: Context): List<CatalogGroup> = withContext(Dispatchers.IO) {
        val installed = PluginStore.installed(context)
        val cachedIndex = PluginCatalog.lastGoodIndex(context)
        val updates = cachedIndex?.let { PluginCatalog.updatesFor(installed, it) }.orEmpty()
        // Read once here, off the main thread; valueLabel below is a
        // plain (non-suspend) closure the renderer may call on the main
        // thread, so it must not touch the key store itself.
        val userKeys = UserOriginKeys.loadBase64(UserOriginKeys.storeFile(context))
        val resolution = PluginApiResolver.current(context)
        val grantStore = PluginGrants.forContext(context)
        val providerChoices = PluginProviderChoices.forContext(context).all()

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
                                subtitle = "Add one below -- a plugin never runs until you approve it on its own page",
                                run = {},
                            ),
                        )
                    }
                    installed.forEach { record ->
                        add(pluginCard(record, userKeys, resolution.waiting[record.manifest.id], grantStore.read(record.manifest.id).wantsNewAccess))
                    }
                },
            ),
        ) + providedByGroups(resolution, providerChoices) + (
            if (installed.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    CatalogGroup(
                        id = "plugins_updates",
                        title = "Updates",
                        items = buildList {
                            add(
                                ActionItem(
                                    id = "plugins_updates_status",
                                    title = when {
                                        cachedIndex == null -> "Catalog not fetched yet"
                                        updates.isEmpty() -> "Up to date"
                                        else -> "${updates.size} update${if (updates.size == 1) "" else "s"} available"
                                    },
                                    subtitle = if (cachedIndex == null) {
                                        "Open Add > Browse catalog to check"
                                    } else {
                                        updates.joinToString { (record, release) -> "${record.manifest.label} -> ${release.version}" }
                                            .ifEmpty { "Every installed plugin matches the catalog's latest stable release" }
                                    },
                                    run = {},
                                ),
                            )
                            if (updates.isNotEmpty()) {
                                add(
                                    AsyncActionItem(
                                        id = "plugins_update_all",
                                        title = "Update all",
                                        subtitle = "Downloads and verifies each update, then installs it the same way a single update would",
                                        run = { ctx, onStatus -> PluginCatalog.updateAll(ctx, onStatus) },
                                    ),
                                )
                            }
                        },
                    ),
                )
            }
        ) + listOf(
            CatalogGroup(
                id = "plugins_add",
                title = "Add",
                items = listOf(
                    NestedScreenItem(
                        id = "plugins_add_catalog",
                        title = "Browse catalog",
                        subtitle = "Plugins published by droidtop-platforms and any third-party origin you've trusted",
                        inline = PluginCatalogScreen.screen(),
                    ),
                    DocumentPickItem(
                        id = "plugins_add_file",
                        title = "Install plugin file",
                        subtitle = "Pick a signed .droidplugin.tar.xz bundle -- validated before anything runs, never run until approved",
                        mimeType = "*/*",
                        onPicked = { ctx, uri -> PluginStore.importFromPicker(ctx, uri) },
                    ),
                ),
            ),
            CatalogGroup(
                id = "plugins_advanced",
                title = "Advanced",
                items = listOf(
                    NestedScreenItem(
                        id = "plugins_keys_you_trust",
                        title = "Keys you trust",
                        subtitle = "Origins whose plugin signatures droidtop verifies: the official one, and any you add",
                        registryId = SCREEN_PLUGIN_KEYS,
                        valueLabel = {
                            if (userKeys.isEmpty()) "Official only" else "Official + ${userKeys.size} added by you"
                        },
                    ),
                ),
            ),
        )
    }

    private fun pluginTrustBadge(origin: String, userKeys: Map<String, String>): String = when {
        PluginOriginKeys.isOfficial(origin) -> "Official"
        userKeys.containsKey(origin) -> "Added by you"
        else -> "NOT TRUSTED"
    }

    /** The installed-plugins list row: what it's called, what it adds in plain words, its trust badge and its state -- the whole card, one tap into [pluginDetailScreen]. */
    private fun pluginCard(
        record: dev.droidtop.pluginhost.PluginRecord,
        userKeys: Map<String, String>,
        waiting: List<dev.droidtop.pluginhost.RequiredApi>?,
        wantsNewAccess: Boolean,
    ): NestedScreenItem {
        val m = record.manifest
        val state = when {
            record.trust == PluginTrustState.PENDING -> "Needs approval"
            record.trust == PluginTrustState.DENIED -> "Denied"
            record.disabledReason != null -> "Crashed"
            record.trust == PluginTrustState.APPROVED && !record.enabled -> "Disabled"
            // Waiting is not disabled: the plugin resumes by itself when a provider returns (docs/plugin-api.md 2.3).
            waiting != null -> "Waiting"
            record.trust == PluginTrustState.APPROVED && wantsNewAccess -> "Wants new access"
            record.trust == PluginTrustState.APPROVED -> "Running"
            else -> "Unknown"
        }
        val trustBadge = pluginTrustBadge(m.origin, userKeys)
        return NestedScreenItem(
            id = "plugin_${m.id}",
            title = m.label,
            subtitle = pluginSummary(m) + " - " + trustBadge,
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
                    val userKeys = UserOriginKeys.loadBase64(UserOriginKeys.storeFile(context))
                    pluginDetailGroups(context, record, userKeys)
                }
            }
        },
    )

    private fun pluginDetailGroups(
        context: Context,
        record: dev.droidtop.pluginhost.PluginRecord,
        userKeys: Map<String, String>,
    ): List<CatalogGroup> {
        val m = record.manifest
        val resolution = PluginApiResolver.current(context)
        val grantSnapshot = PluginGrants.forContext(context).read(m.id)
        val statusGroup = buildList<CatalogItem> {
            val statusLine = when {
                record.trust == PluginTrustState.PENDING -> "Awaiting approval"
                record.trust == PluginTrustState.DENIED -> "Denied"
                record.disabledReason != null -> "Disabled: ${record.disabledReason}"
                record.trust == PluginTrustState.APPROVED && record.enabled -> "Running"
                else -> "Disabled"
            }
            val trustLine = when {
                PluginOriginKeys.isOfficial(m.origin) -> "Official"
                userKeys.containsKey(m.origin) -> "Added by you"
                else -> "NOT TRUSTED: no trusted key for this origin anymore (see Keys you trust)"
            }
            add(ActionItem(id = "plugin_${m.id}_status", title = statusLine, subtitle = trustLine, run = {}))
            resolution.waiting[m.id]?.let { missing ->
                add(
                    ActionItem(
                        id = "plugin_${m.id}_waiting",
                        title = "Needs " + missing.joinToString { it.api },
                        subtitle = "Waiting: no running plugin provides this. It is not disabled and resumes by itself when one does.",
                        run = {},
                    ),
                )
            }
            if (grantSnapshot.wantsNewAccess) {
                add(
                    ActionItem(
                        id = "plugin_${m.id}_new_access",
                        title = "Wants new access",
                        subtitle = "An update asks for more than you allowed. Nothing new is on until you say so under Permissions.",
                        run = {},
                    ),
                )
            }
            when (record.trust) {
                PluginTrustState.PENDING -> {
                    add(
                        ActionItem(
                            id = "plugin_${m.id}_approve",
                            title = "Approve",
                            subtitle = if (m.requestsRoot) {
                                "This plugin can also use root as an optional enhancement when your device has it -- its core function must still work without it. Approving here does NOT grant root; use \"Approve and allow root\" for that."
                            } else {
                                "Runs in its own process from now on"
                            },
                            run = { ctx -> PluginStore.setApproval(ctx, m.id, approved = true, grantRoot = false); PluginStatusWidgetProvider.requestUpdate(ctx) },
                        ),
                    )
                    if (m.requestsRoot) {
                        add(
                            ActionItem(
                                id = "plugin_${m.id}_approve_root",
                                title = "Approve and allow root",
                                subtitle = "Only takes effect if this device actually has root; root stays an enhancement, never a requirement",
                                confirmTitle = "Let \"${m.label}\" use root on this device?",
                                run = { ctx -> PluginStore.setApproval(ctx, m.id, approved = true, grantRoot = true); PluginStatusWidgetProvider.requestUpdate(ctx) },
                            ),
                        )
                    }
                    add(
                        ActionItem(
                            id = "plugin_${m.id}_deny",
                            title = "Deny",
                            subtitle = "Stays installed but never runs. A future update (a new signed archive) can be approved again.",
                            run = { ctx -> PluginStore.setApproval(ctx, m.id, approved = false, grantRoot = false); PluginStatusWidgetProvider.requestUpdate(ctx) },
                        ),
                    )
                }
                PluginTrustState.APPROVED -> {
                    add(
                        ToggleItem(
                            id = "plugin_${m.id}_enabled",
                            title = "Enabled",
                            current = record.enabled,
                            onToggle = { ctx, on -> PluginStore.setEnabled(ctx, m.id, on); PluginStatusWidgetProvider.requestUpdate(ctx) },
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
                        subtitle = if (record.rootApproved) "Approved" else "Not granted -- approve with root above to allow it",
                        run = {},
                    ),
                )
            }
        }

        val consentGroups = pluginConsentGroups(context, record, userKeys)

        // Grants exist once the plugin is approved (docs/plugin-api.md 4.4): one screen per plugin, no second place.
        val permissionsGroup: CatalogGroup? = if (record.trust != PluginTrustState.APPROVED) {
            null
        } else {
            val rows = permissionRows(record, grantSnapshot)
            val summary = "${rows.count { it.state == GrantState.GRANTED }} allowed, ${rows.count { it.state == GrantState.ASK }} ask, ${rows.count { it.state == GrantState.DENIED }} blocked"
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
                ),
            )
        }

        val providesGroup = buildList<CatalogItem> {
            add(
                ActionItem(
                    id = "plugin_${m.id}_provides",
                    title = pluginSummary(m),
                    run = {},
                ),
            )
            if (record.runnable() && PluginCapability.STATUS_TILE in m.capabilities) {
                add(
                    AsyncActionItem(
                        id = "plugin_${m.id}_call_status_tile",
                        title = "Call its status tile",
                        subtitle = "Runs a real invoke() through the isolated plugin process and shows what it returns",
                        run = { ctx, _ ->
                            val policy = PluginCrashPolicy(ctx.applicationContext)
                            try {
                                val result = policy.invoke(record, PluginCapability.STATUS_TILE, emptyMap())
                                if (result.ok) {
                                    result.values.entries.joinToString(", ") { (k, v) -> "$k=$v" }.ifEmpty { "OK, no values" }
                                } else {
                                    "Failed: ${result.error}"
                                }
                            } finally {
                                policy.shutdown()
                            }
                        },
                    ),
                )
                if (ctxIsDebuggable(context)) {
                    add(
                        AsyncActionItem(
                            id = "plugin_${m.id}_force_crash",
                            title = "Debug: force a crash",
                            subtitle = "Confirms crash containment -- droidtop should survive and disable this plugin",
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
        val runtimeGroup: List<CatalogItem> = when (m.kind) {
            PluginKind.PYTHON -> listOf(pythonRuntimeItem(context, forPluginLabel = m.label))
            PluginKind.FLUTTER_EMBED -> listOf(flutterRuntimeItem(context, forPluginLabel = m.label))
            else -> emptyList()
        }

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
                        subtitle = if (index == null) "Catalog not fetched yet -- open Add > Browse catalog to check" else "Matches the catalog's latest stable release",
                        run = {},
                    )
                },
            )
        }

        val advancedGroup = listOf(
            ActionItem(id = "plugin_${m.id}_id", title = "Plugin id", subtitle = m.id, run = {}),
            ActionItem(id = "plugin_${m.id}_origin", title = "Origin", subtitle = m.origin, run = {}),
            ActionItem(id = "plugin_${m.id}_digest", title = "Archive digest", subtitle = record.archiveDigest, run = {}),
        )

        return listOfNotNull(
            CatalogGroup(id = "plugin_${m.id}_status_group", title = null, items = statusGroup),
        ) + consentGroups + listOfNotNull(
            permissionsGroup,
            CatalogGroup(id = "plugin_${m.id}_provides_group", title = "What it provides", items = providesGroup),
            if (runtimeGroup.isEmpty()) null else CatalogGroup(id = "plugin_${m.id}_runtime_group", title = "Runtime", items = runtimeGroup),
            CatalogGroup(id = "plugin_${m.id}_update_group", title = "Version", items = updateGroup),
            CatalogGroup(
                id = "plugin_${m.id}_uninstall_group",
                title = null,
                items = listOf(
                    ActionItem(
                        id = "plugin_${m.id}_uninstall",
                        title = "Uninstall",
                        confirmTitle = "Remove ${m.label} and its data?",
                        run = { ctx -> PluginStore.uninstall(ctx, m.id); PluginStatusWidgetProvider.requestUpdate(ctx) },
                    ),
                ),
            ),
            CatalogGroup(id = "plugin_${m.id}_advanced_group", title = "Advanced", items = advancedGroup),
        )
    }

    private fun pythonRuntimeItem(context: Context, forPluginLabel: String): CatalogItem {
        val installed = PythonRuntimeManager.isInstalled(context)
        val version = PythonRuntimeManager.installedVersion(context) ?: PythonRuntimeManager.pinnedVersion(context)
        return if (installed) {
            ActionItem(
                id = "plugins_python_runtime_remove",
                title = "Python runtime",
                subtitle = "Installed: CPython $version (${PythonRuntimeManager.currentAbi()}). Removing it stops $forPluginLabel (and any other python-kind plugin) until it's downloaded again.",
                confirmTitle = "Remove the downloaded Python runtime?",
                run = { ctx -> PythonRuntimeManager.remove(ctx) },
            )
        } else {
            AsyncActionItem(
                id = "plugins_python_runtime_download",
                title = "Python runtime",
                subtitle = "Not installed -- $forPluginLabel needs it to run. CPython $version (${PythonRuntimeManager.currentAbi()}), ~22 MB from python.org, SHA-256 verified.",
                run = { ctx, onStatus ->
                    val error = PythonRuntimeManager.ensureInstalled(ctx) { progress -> onStatus(runtimeProgressText(progress)) }
                    error ?: "Python runtime installed"
                },
            )
        }
    }

    private fun flutterRuntimeItem(context: Context, forPluginLabel: String): CatalogItem {
        val installed = FlutterRuntimeManager.isInstalled(context)
        val version = FlutterRuntimeManager.pinnedVersion(context)
        return if (installed) {
            ActionItem(
                id = "plugins_flutter_runtime_remove",
                title = "Flutter runtime",
                subtitle = "Installed: Flutter engine $version (${FlutterRuntimeManager.currentAbi()}). Removing it stops $forPluginLabel (and any other flutter_embed-kind plugin) until it's downloaded again.",
                confirmTitle = "Remove the downloaded Flutter runtime?",
                run = { ctx -> FlutterRuntimeManager.remove(ctx) },
            )
        } else {
            AsyncActionItem(
                id = "plugins_flutter_runtime_download",
                title = "Flutter runtime",
                subtitle = "Not installed -- $forPluginLabel needs it to run. Flutter engine $version (${FlutterRuntimeManager.currentAbi()}), ~40 MB from Flutter's own release CDN, SHA-256 verified.",
                run = { ctx, onStatus ->
                    val error = FlutterRuntimeManager.ensureInstalled(ctx) { progress -> onStatus(runtimeProgressText(progress)) }
                    error ?: "Flutter runtime installed"
                },
            )
        }
    }

    private fun runtimeProgressText(progress: PythonRuntimeManager.Progress): String = when (progress) {
        is PythonRuntimeManager.Progress.Downloading -> downloadProgressText(progress.bytesRead, progress.totalBytes)
        PythonRuntimeManager.Progress.Verifying -> "Verifying SHA-256..."
        PythonRuntimeManager.Progress.Extracting -> "Extracting..."
        PythonRuntimeManager.Progress.Done -> "Done"
    }

    private fun runtimeProgressText(progress: FlutterRuntimeManager.Progress): String = when (progress) {
        is FlutterRuntimeManager.Progress.Downloading -> downloadProgressText(progress.bytesRead, progress.totalBytes)
        FlutterRuntimeManager.Progress.Verifying -> "Verifying SHA-256..."
        FlutterRuntimeManager.Progress.Extracting -> "Extracting..."
        FlutterRuntimeManager.Progress.Done -> "Done"
    }

    private fun downloadProgressText(bytesRead: Long, totalBytes: Long): String = if (totalBytes > 0) {
        val pct = (bytesRead * 100 / totalBytes).toInt()
        "Downloading... $pct% (${bytesRead / 1024 / 1024} MB / ${totalBytes / 1024 / 1024} MB)"
    } else {
        "Downloading... ${bytesRead / 1024 / 1024} MB"
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
            if (!point.risk.needsConsent) continue
            val id = PluginPermissions.PROVIDE_PREFIX + entry.point
            val tier = if (point.risk == dev.droidtop.pluginhost.PointRisk.CRITICAL) PermissionTier.CRITICAL else PermissionTier.DANGEROUS
            rows += PermissionRow(id, PluginPermissions.labelFor(id) ?: id, null, tier, PluginGrants.provideState(record, snap, entry.point))
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
            val wanted = if (row.id in snap.wanted) "It tried to use this while you were not being asked" else null
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
                onSelect = { ctx, value -> GrantState.fromId(value)?.let { PluginGrants.forContext(ctx).set(pluginId, row.id, it) } },
            )
        }
        val fresh = rows.filter { it.id in snap.fresh && it.state == GrantState.ASK }
        val rest = rows - fresh.toSet()
        return listOfNotNull(
            if (record.manifest.contractVersion < 2) {
                CatalogGroup(
                    id = "plugin_permissions_older",
                    title = "Older plugin",
                    items = listOf(
                        ActionItem(
                            id = "plugin_permissions_older_row",
                            title = "Full access (older plugin)",
                            subtitle = "Written before permissions existed: it holds what it could always do. You can still turn any of it off below.",
                            run = {},
                        ),
                    ),
                )
            } else {
                null
            },
            if (fresh.isEmpty()) null else CatalogGroup("plugin_permissions_new", "Wants new access", fresh.map(::item)),
            rest.filter { it.tier == PermissionTier.CRITICAL }.takeIf { it.isNotEmpty() }?.let { CatalogGroup("plugin_permissions_critical", "Critical", it.map(::item)) },
            rest.filter { it.tier == PermissionTier.DANGEROUS }.takeIf { it.isNotEmpty() }?.let { CatalogGroup("plugin_permissions_dangerous", "Asks first", it.map(::item)) },
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
    private fun pluginSummary(m: dev.droidtop.pluginhost.PluginManifest): String =
        m.v2.provides.mapNotNull { ExtensionPoints.find(it.point)?.label }.distinct().joinToString()
            .ifEmpty { m.capabilities.joinToString { it.display } }
            .ifEmpty { "No capabilities declared" }

    /**
     * The approval view of one plugin (docs/plugin-api.md 4.3), read-only:
     * what it adds and where, what it can do, what it asks for, what it
     * uses from other plugins, and what this droidtop does not support.
     * Built from the registries by [PluginConsent]; there is no grant
     * storage yet, so nothing here is a switch. The existing root tick on
     * the Approve rows is unchanged.
     */
    private fun pluginConsentGroups(
        context: Context,
        record: dev.droidtop.pluginhost.PluginRecord,
        userKeys: Map<String, String>,
    ): List<CatalogGroup> {
        val m = record.manifest
        val view = PluginConsent.of(m, PluginStore.installed(context)) { pluginTrustBadge(it, userKeys) }
        fun info(key: String, title: String, subtitle: String? = null, value: String? = null) =
            ActionItem(id = "plugin_${m.id}_$key", title = title, subtitle = subtitle, value = value, run = {})
        fun group(key: String, title: String, items: List<CatalogItem>) =
            if (items.isEmpty()) null else CatalogGroup(id = "plugin_${m.id}_consent_$key", title = title, items = items)
        return listOfNotNull(
            if (view.olderPluginFullAccess) {
                group("older", "Older plugin", listOf(info("older", "Full access (older plugin)", "Written before permissions existed: it can do everything it could before, and droidtop does not contain it")))
            } else {
                null
            },
            group(
                "adds", "Adds",
                view.adds.flatMap { (mode, lines) -> lines.mapIndexed { i, l -> info("adds_${mode}_$i", l.title, l.detail, mode) } },
            ),
            group("can", "Can", view.can.mapIndexed { i, l -> info("can_$i", l.title, l.detail) }),
            group(
                "asks", "Asks for",
                view.asks.mapIndexed { i, a ->
                    info(
                        "asks_$i", a.line.title,
                        listOfNotNull(a.line.detail, if (a.needed) "Needed" else null).joinToString(" - ").ifEmpty { null },
                        if (a.tier == PermissionTier.CRITICAL) "Critical" else "Asks first",
                    )
                },
            ),
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
            "any other is one YOU chose to trust -- third-party, not official, and droidtop has not vetted it",
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
                                subtitle = "Official -- certified inside droidtop itself. Key fingerprint " +
                                    (UserOriginKeys.fingerprint(PluginOriginKeys.officialKeyBase64()) ?: "unavailable"),
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
                                        append("Added by you -- third-party, NOT official. Key fingerprint ")
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
                                            PluginStatusWidgetProvider.requestUpdate(ctx)
                                            "No longer trusting \"${entry.origin}\" -- its plugins are flagged on the Plugins screen"
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
                    title = "Add a plugin source -- fetches its key",
                    items = listOf(
                        TextInputItem(
                            id = "plugin_keys_source_url",
                            title = "Source address",
                            subtitle = "A GitHub plugin repo (https://github.com/<owner>/<repo>), a catalog index ending in .json, " +
                                "or a plain https address. droidtop fetches the key it publishes and asks you to trust it below -- " +
                                "plaintext http is refused",
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
                    title = "Add a key by hand -- for sources that publish no key",
                    items = listOf(
                        TextInputItem(
                            id = "plugin_keys_manual_origin",
                            title = "Origin id",
                            subtitle = "The origin's own id, e.g. acme -- it prefixes every plugin id it signs (<origin>.<name>). " +
                                "It can never be \"${PluginOriginKeys.OFFICIAL_ORIGIN}\": that one is official",
                            value = pendingKeyManualOrigin,
                            onChange = { _, v -> pendingKeyManualOrigin = v.trim() },
                        ),
                        TextInputItem(
                            id = "plugin_keys_manual_key",
                            title = "Public key",
                            subtitle = "Base64 of the P-256 public key (X.509 SubjectPublicKeyInfo), as its author publishes it",
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
                            subtitle = "A droidtop-plugin-key.json (origin + key), or a raw base64 key file with the Origin id filled in above",
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
                        "Key fingerprint $fingerprint. Third-party source, not official: droidtop has not vetted this key " +
                            "or anything it signs -- trusting it is your call. Plugins from it will show as \"Added by you\"."
                    } else {
                        "You trusted fingerprint $existingFingerprint; this fetch publishes $fingerprint. A changed key can " +
                            "mean the source rotated it, or that the source or this fetch is compromised -- droidtop cannot " +
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
        return when (val fetched = PluginSourceKeys.fetchKey(url, dev.droidtop.pluginhost.GitHubTokenStore.get(context))) {
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
                "and the Origin id field above is empty -- fill it in to add a raw base64 key file"
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
        subtitle = "One source at a time, like real ES-DE. ScreenScraper works without an account; " +
            "your own ScreenScraper login raises how much you can scrape per day. TheGamesDB needs " +
            "its own free API key. The libretro database needs no account at all",
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
                            subtitle = "Cover images, including the keyless libretro fallback",
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

    private fun screenScraperField(
        context: Context,
        id: String,
        title: String,
        value: String,
        secret: Boolean = false,
        write: (Context, String) -> Unit,
    ) = TextInputItem(
        id = id,
        title = title,
        value = value,
        secret = secret,
        onChange = { c, v -> write(c, v.trim()) },
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
