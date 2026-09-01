package dev.droidtop.app.settings

import android.content.Context
import android.net.Uri
import dev.droidtop.app.GamesRootPrefs
import dev.droidtop.app.importGamelistXml
import dev.droidtop.app.scrapeSystemArtwork
import dev.droidtop.library.consoles.ConsoleSystemDef
import dev.droidtop.library.consoles.ConsoleSystemEntity
import dev.droidtop.library.consoles.ConsoleSystemsDatabase
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.consoles.CustomPlayerPrefs
import dev.droidtop.library.consoles.PlayerOverridePrefs
import dev.droidtop.library.consoles.PlatformDatabaseSource
import dev.droidtop.library.consoles.PlayersDatabaseUpdater
import dev.droidtop.library.consoles.SystemOverridePrefs
import dev.droidtop.library.consoles.BiosDatabase
import dev.droidtop.library.consoles.KnownPlayers
import dev.droidtop.library.consoles.SystemBiosSpec
import dev.droidtop.library.consoles.availablePlayers
import dev.droidtop.library.integrations.IntegrationCapability
import dev.droidtop.library.integrations.IntegrationPlaceholders
import dev.droidtop.library.integrations.IntegrationStore
import dev.droidtop.library.consoles.resolvePlayer
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
import dev.droidtop.library.settings.FolderPickItem
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.library.theme.SystemThemeColors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * :app's management screens as settings-catalog data (docs/SPEC.md
 * settings architecture -- per direction, EVERYTHING that is a droidtop
 * setting lives in the catalog model and is chromed by the shared
 * renderers; these used to be a hand-rolled Compose activity with its
 * own one-off look). Registered into [SettingsScreenRegistry] at process
 * start by [SettingsCatalogInitProvider], so lower modules
 * (HandheldSettingsCatalog in :runtime-common, the Preference surface in
 * :shell-default) can open them by id without depending on :app.
 */
object AppSettingsCatalogs {

    const val SCREEN_CONSOLE_SYSTEMS = "console_systems"
    const val SCREEN_ROM_FOLDERS = "rom_folders"
    const val SCREEN_SCRAPER = "rom_scraper"
    const val SCREEN_PLATFORMS = "manage_platforms"
    const val SCREEN_INTEGRATIONS = "integrations"
    const val SCREEN_WINDOWS_GAMES = "windows_games"
    const val SCREEN_ANDROID_SETTINGS = "android_settings"
    const val SCREEN_ENGINEHOST = "enginehost"

    // How deep folderLooksRomLike is willing to walk -- see the doc
    // comment at the original ConsoleSystemsActivity site this moved from.
    private const val ROM_LOOKALIKE_MAX_DEPTH = 4

    @Volatile private var registered = false

    fun ensureRegistered() {
        if (registered) return
        registered = true
        SettingsScreenRegistry.register(consoleSystemsScreen())
        SettingsScreenRegistry.register(romFoldersScreen())
        SettingsScreenRegistry.register(scraperScreen())
        SettingsScreenRegistry.register(platformsScreen())
        SettingsScreenRegistry.register(integrationsScreen())
        SettingsScreenRegistry.register(windowsGamesScreen())
        SettingsScreenRegistry.register(androidSettingsScreen())
        SettingsScreenRegistry.register(enginehostScreen())
    }

    // ------------------------------------------------------------------
    // Console systems: per-folder system/player/scrape management.
    // ------------------------------------------------------------------

    private fun consoleSystemsScreen() = CatalogScreen(
        id = SCREEN_CONSOLE_SYSTEMS,
        title = "Console systems",
        subtitle = "Each folder's system is guessed from its name; open a folder to change its system, pick its emulator, or scrape artwork",
        groups = { context -> consoleSystemsGroups(context) },
    )

    private suspend fun consoleSystemsGroups(context: Context): List<CatalogGroup> = withContext(Dispatchers.IO) {
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        val knownExtensions = systemsById.values.flatMap { it.extensions }.toSet()
        // Same real folder discovery the old screen used (kept 1:1): every
        // immediate subfolder of every games root that either resolves to a
        // system by name/override or genuinely contains ROM-like files.
        val folders = GamesRootPrefs.gamesRootPaths(context)
            .map(::File)
            .flatMap { root -> (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.toList() }
            .filter { folder ->
                SystemOverridePrefs.resolveForFolder(context, folder.absolutePath, folder.name, systemsById) != null ||
                    folder.walkTopDown().maxDepth(ROM_LOOKALIKE_MAX_DEPTH).any { it.isFile && it.extension.lowercase() in knownExtensions }
            }
            .sortedBy { it.name.lowercase() }

        listOf(
            CatalogGroup(
                id = "console_systems_tools",
                title = null,
                items = listOf(
                    NestedScreenItem(
                        id = "console_systems_platforms",
                        title = "Manage platforms",
                        subtitle = "Add, edit, or delete the platforms droidtop recognizes",
                        registryId = SCREEN_PLATFORMS,
                    ),
                    NestedScreenItem(
                        id = "console_systems_rom_folders",
                        title = "ROM folders",
                        subtitle = "Add or remove the folders droidtop scans for ROMs",
                        registryId = SCREEN_ROM_FOLDERS,
                    ),
                    NestedScreenItem(
                        id = "console_systems_integrations",
                        title = "App integrations",
                        subtitle = "Hook other installed apps into droidtop, e.g. a downloader for a system's games",
                        registryId = SCREEN_INTEGRATIONS,
                        valueLabel = { ctx ->
                            val n = IntegrationStore.available(ctx).size
                            if (n == 0) "none" else "$n active"
                        },
                    ),
                    NestedScreenItem(
                        id = "console_systems_scraper",
                        title = "Artwork & metadata scraper",
                        subtitle = "Source and credentials for ROM scraping",
                        registryId = SCREEN_SCRAPER,
                        valueLabel = { ctx -> if (ScraperSourcePrefs.get(ctx) == ScraperSource.THEGAMESDB) "TheGamesDB" else "ScreenScraper" },
                    ),
                    NestedScreenItem(
                        id = "console_systems_enginehost",
                        title = "Enginehost",
                        subtitle = "Engine-game runtimes, like an emulator's core list; its own settings and save storage",
                        registryId = SCREEN_ENGINEHOST,
                    ),
                    AsyncActionItem(
                        id = "console_systems_scrape_all",
                        title = "Scrape all systems",
                        subtitle = "Runs the artwork & metadata scrape for every game folder, one system at a time",
                        run = { ctx, onStatus ->
                            val systemsById = ConsoleSystemsRepository.allSystems(ctx).associateBy { it.id }
                            val folders = scrapeTargets(ctx, systemsById)
                            if (folders.isEmpty()) {
                                "No game folders found to scrape."
                            } else {
                                val summaries = mutableListOf<String>()
                                folders.forEachIndexed { index, (folder, system) ->
                                    onStatus("[${index + 1}/${folders.size}] ${system.displayName}\u2026")
                                    val summary = runCatching {
                                        scrapeSystemArtwork(ctx, folder, system) { done, total ->
                                            onStatus("[${index + 1}/${folders.size}] ${system.displayName}: $done/$total")
                                        }
                                    }.getOrElse { "${system.displayName}: failed (${it.message})" }
                                    summaries += summary
                                }
                                summaries.joinToString("\n")
                            }
                        },
                    ),
                    AsyncActionItem(
                        id = "console_systems_update_players",
                        title = "Update platform databases",
                        subtitle = "Refresh players, platforms, engine routing, and BIOS registry from droidtop-platforms on GitHub",
                        run = { ctx, onStatus ->
                            onStatus("Updating players...")
                            val players = PlayersDatabaseUpdater.update(ctx)
                            onStatus("Updating platforms...")
                            val platforms = dev.droidtop.library.consoles.PlatformsDatabase.update(ctx)
                            onStatus("Updating engine routing...")
                            val engines = dev.droidtop.library.EnginesDatabase.update(ctx)
                            onStatus("Updating BIOS registry...")
                            val bios = BiosDatabase.update(ctx)
                            "Updated: $players players, $platforms platforms, $engines engines, $bios BIOS systems"
                        },
                    ),
                    // One source for all four databases (see
                    // PlatformDatabaseSource). Editable because these URLs
                    // ship compiled into the app and raw.githubusercontent
                    // does not reliably redirect after a repository move --
                    // without this, relocating the repo would silently
                    // break updates on every already-installed build.
                    TextInputItem(
                        id = "console_systems_db_source",
                        title = "Platform database source",
                        subtitle = "Base URL the four databases are fetched from; blank restores the default",
                        value = PlatformDatabaseSource.baseUrl(context)
                            .takeIf { it != PlatformDatabaseSource.DEFAULT_BASE_URL }
                            .orEmpty(),
                        onChange = { ctx, value -> PlatformDatabaseSource.setBaseUrl(ctx, value) },
                    ),
                ),
            ),
            CatalogGroup(
                id = "console_systems_folders",
                title = "Game folders",
                items = if (folders.isEmpty()) {
                    listOf(
                        ActionItem(
                            id = "console_systems_no_folders",
                            title = "No game folders found",
                            subtitle = "Add a ROM folder above, then put <system>/<romFile> folders inside it",
                            run = {},
                        ),
                    )
                } else {
                    folders.map { folder ->
                        val resolved = SystemOverridePrefs.resolveForFolder(context, folder.absolutePath, folder.name, systemsById)
                        NestedScreenItem(
                            id = "console_folder_${folder.absolutePath}",
                            title = folder.name,
                            subtitle = when {
                                resolved == null -> "Unrecognized -- open to assign a system"
                                resolvePlayer(context, resolved) == null -> "${resolved.displayName} -- no installed emulator yet"
                                else -> resolved.displayName
                            },
                            inline = folderScreen(folder),
                            valueLabel = { ctx ->
                                resolved?.let { resolvePlayer(ctx, it)?.name } ?: ""
                            },
                            accent = resolved?.let { SystemThemeColors.forSystem(context, it.id) },
                        )
                    }
                },
            ),
        )
    }

    /**
     * Every game folder that resolves to a real system, paired with it --
     * the same roots-then-subfolders walk the console-systems screen
     * lists, narrowed to the resolvable ones because a scrape needs a
     * system to scrape AS. Used by "Scrape all systems".
     */
    private fun scrapeTargets(
        context: Context,
        systemsById: Map<String, ConsoleSystemDef>,
    ): List<Pair<File, ConsoleSystemDef>> =
        GamesRootPrefs.gamesRootPaths(context)
            .map(::File)
            .flatMap { root -> (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.toList() }
            .mapNotNull { folder ->
                SystemOverridePrefs.resolveForFolder(context, folder.absolutePath, folder.name, systemsById)
                    ?.let { folder to it }
            }
            .sortedBy { it.first.name.lowercase() }

    private fun folderScreen(folder: File) = CatalogScreen(
        id = "console_folder_${folder.absolutePath}",
        title = folder.name,
        groups = { context ->
            withContext(Dispatchers.IO) {
                val systems = ConsoleSystemsRepository.allSystems(context)
                val systemsById = systems.associateBy { it.id }
                val resolved = SystemOverridePrefs.resolveForFolder(context, folder.absolutePath, folder.name, systemsById)
                buildList {
                    add(
                        CatalogGroup(
                            id = "folder_system",
                            title = null,
                            items = buildList {
                                add(systemChoiceItem(context, folder, systems))
                                if (resolved != null) {
                                    add(playerChoiceItem(context, resolved))
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
                                    // section 12). The system and its real
                                    // destination folder are both known
                                    // here, which is exactly what an
                                    // acquire-content integration needs.
                                    IntegrationStore.available(context, IntegrationCapability.ACQUIRE_CONTENT)
                                        .forEach { integration ->
                                            add(
                                                ActionItem(
                                                    id = "folder_integration_${integration.id}_${resolved.id}",
                                                    title = integration.label,
                                                    subtitle = integration.description
                                                        ?: "Opens ${integration.packageName} for ${resolved.displayName}",
                                                    run = { ctx ->
                                                        IntegrationStore.run(
                                                            context = ctx,
                                                            integration = integration,
                                                            systemId = resolved.id,
                                                            systemName = resolved.displayName,
                                                            systemFolder = folder,
                                                        )
                                                    },
                                                ),
                                            )
                                        }
                                    // EmuDeck-style setup helper: firmware
                                    // check against the real Batocera BIOS
                                    // registry, when this system needs any.
                                    val bios = BiosDatabase.forSystem(context, resolved.id)
                                    if (bios != null) {
                                        val gamesRoot = folder.parentFile ?: folder
                                        add(
                                            NestedScreenItem(
                                                id = "folder_bios_${resolved.id}",
                                                title = "BIOS files",
                                                subtitle = "Firmware ${resolved.displayName} emulators may need, looked for in ${gamesRoot.name}/bios",
                                                inline = biosScreen(gamesRoot, bios),
                                                valueLabel = { _ ->
                                                    // Presence only here -- md5 hashing happens
                                                    // inside the screen, off the main thread.
                                                    val present = bios.files.count { File(gamesRoot, it.file).isFile }
                                                    "$present/${bios.files.size}"
                                                },
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
        },
    )

    // One screen per system's firmware set: every registry file with its
    // real on-disk presence AND md5 verification (catches the classic
    // "right name, wrong dump"), plus the database refresh action.
    private fun biosScreen(gamesRoot: File, spec: SystemBiosSpec) = CatalogScreen(
        id = "bios_${spec.systemId}",
        title = "${spec.name} BIOS files",
        subtitle = "Checked under ${gamesRoot.absolutePath}/bios -- md5-verified against Batocera's real registry",
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
                                    !status.present -> "Missing -- place it at ${File(gamesRoot, status.spec.file).absolutePath}"
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
                            AsyncActionItem(
                                id = "bios_update_db",
                                title = "Update BIOS database",
                                subtitle = "Refresh the registry from droidtop-platforms on GitHub",
                                run = { ctx, _ ->
                                    val count = BiosDatabase.update(ctx)
                                    "BIOS database updated ($count systems)"
                                },
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
        options = listOf(ChoiceOption("", "(automatic, from the folder name)")) +
            systems.sortedBy { it.displayName.lowercase() }.map { ChoiceOption(it.id, "${it.displayName} (${it.id})") },
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
                "No installed emulator can run ${system.displayName} yet -- add a custom player below, or install one"
            } else {
                "Which installed emulator launches ${system.displayName}"
            },
            options = listOf(ChoiceOption("", "(first installed)")) + players.map { ChoiceOption(it.id, it.name) },
            current = PlayerOverridePrefs.get(context, system.id) ?: "",
            onSelect = { ctx, value ->
                PlayerOverridePrefs.set(ctx, system.id, value.ifEmpty { null })
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
                                subtitle = "Opens enginehost's own global configuration",
                                run = { ctx ->
                                    ctx.startActivity(dev.droidtop.library.EngineHost.settingsIntent())
                                },
                            ),
                        )
                        add(
                            ActionItem(
                                id = "enginehost_saves",
                                title = "Save storage",
                                subtitle = "Shared save root and migration, in enginehost's own screen",
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
                        bundles.map { bundle ->
                            ActionItem(
                                id = "enginehost_bundle_${bundle.bundleId}",
                                title = bundle.engine +
                                    (bundle.engineContext?.let { " ($it)" } ?: "") +
                                    (bundle.runtimeVersion?.let { "  $it" } ?: ""),
                                subtitle = buildString {
                                    append(bundle.bundleId)
                                    if (bundle.supportedSeries.isNotEmpty()) {
                                        append("  |  covers ")
                                        append(bundle.supportedSeries.joinToString(", ") { "$it.*" })
                                    }
                                    bundle.origin?.let { append("  |  ").append(it) }
                                },
                                run = {},
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
                            run = { ctx -> ctx.startActivity(controls.appDetailsIntent(ctx)) },
                        ),
                        ActionItem(
                            id = "grant_write_settings",
                            title = "Modify system settings",
                            subtitle = if (controls.canWriteBrightness(context)) {
                                "Granted -- brightness, timeout, and rotation are controlled in droidtop"
                            } else {
                                "Not granted -- needed for brightness, screen timeout, and auto-rotate"
                            },
                            run = { ctx -> ctx.startActivity(controls.brightnessGrantIntent(ctx)) },
                        ),
                        ActionItem(
                            id = "grant_dnd",
                            title = "Do Not Disturb access",
                            subtitle = if (controls.hasDndAccess(context)) {
                                "Granted -- DND is a toggle in every droidtop mode"
                            } else {
                                "Not granted -- needed for the DND toggle"
                            },
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
                            subtitle = "Add one under Game folders first -- a Windows environment that cannot see your games is not much use",
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

    private fun integrationsScreen() = CatalogScreen(
        id = SCREEN_INTEGRATIONS,
        title = "App integrations",
        subtitle = "Declared as .json files in droidtop's own storage, never bundled or synced -- which apps you hook in is yours alone",
        groups = { context ->
            withContext(Dispatchers.IO) { IntegrationStore.seedExampleIfEmpty(context) }
            val declared = IntegrationStore.all(context)
            listOf(
                CatalogGroup(
                    id = "integrations_list",
                    title = null,
                    items = if (declared.isEmpty()) {
                        listOf(
                            ActionItem(
                                id = "integrations_none",
                                title = "No integrations declared",
                                subtitle = "Drop a .json file in ${IntegrationStore.userDir(context).absolutePath} -- example.json.txt there shows the format",
                                run = {},
                            ),
                        )
                    } else {
                        declared.map { integration ->
                            val installed = IntegrationStore.isInstalled(context, integration.packageName)
                            ActionItem(
                                id = "integration_${integration.id}",
                                title = integration.label,
                                subtitle = buildString {
                                    append(integration.capability.display)
                                    append(" - ")
                                    append(if (installed) integration.packageName else "${integration.packageName} is NOT installed, so this is hidden elsewhere")
                                    IntegrationPlaceholders.usedIn(integration.argumentsTemplate)
                                        .takeIf { it.isNotEmpty() }
                                        ?.let { append("  |  uses ").append(it.joinToString(" ")) }
                                },
                                run = {},
                            )
                        }
                    },
                ),
            )
        },
    )

    // ------------------------------------------------------------------
    // ROM folders (games roots).
    // ------------------------------------------------------------------

    private fun romFoldersScreen() = CatalogScreen(
        id = SCREEN_ROM_FOLDERS,
        title = "ROM folders",
        subtitle = "droidtop scans <folder>/<system>/<romFile> under each of these; changes apply on the next library rescan",
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
                                    "Couldn't resolve that folder to a real path on this device -- not added"
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
                            subtitle = "Activate to remove this folder from scanning",
                            confirmTitle = "Remove $path?",
                            run = { ctx -> GamesRootPrefs.removeGamesRoot(ctx, path) },
                        )
                    }.ifEmpty {
                        listOf(ActionItem(id = "rom_folders_none", title = "No ROM folders configured", run = {}))
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
        title = "Artwork & metadata scraper",
        subtitle = "One source at a time, exactly like real ES-DE. ScreenScraper needs at least a dev ID " +
            "(or the debug credentials file); TheGamesDB needs its own free API key. Without either, " +
            "only the keyless libretro boxart fallback fills anything",
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
                    ),
                ),
                CatalogGroup(
                    id = "scraper_screenscraper",
                    // Account fields ONLY: the dev ID/password pair is an
                    // APPLICATION credential (real ES-DE embeds its own and
                    // never surfaces it) -- it arrives via the debug
                    // credentials file below, or a compiled-in registered
                    // pair once droidtop has one, never a user-facing field.
                    title = "ScreenScraper account (optional)",
                    items = listOf(
                        screenScraperField(context, "ss_user_id", "Username", ScreenScraperPrefs.userId(context)) { c, v ->
                            ScreenScraperPrefs.set(c, ScreenScraperPrefs.devId(c), ScreenScraperPrefs.devPassword(c), v, ScreenScraperPrefs.userPassword(c))
                        },
                        screenScraperField(context, "ss_user_password", "Password", ScreenScraperPrefs.userPassword(context), secret = true) { c, v ->
                            ScreenScraperPrefs.set(c, ScreenScraperPrefs.devId(c), ScreenScraperPrefs.devPassword(c), ScreenScraperPrefs.userId(c), v)
                        },
                    ),
                ),
                CatalogGroup(
                    id = "scraper_thegamesdb",
                    title = "TheGamesDB",
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
                CatalogGroup(
                    id = "scraper_config_transfer",
                    title = "Moving credentials between devices",
                    items = listOf(
                        ActionItem(
                            id = "scraper_backup_pointer",
                            title = "Back up / restore settings",
                            subtitle = "The settings backup in Global settings includes everything here, " +
                                "credentials included -- one file restores a working configuration",
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
        subtitle = "Every platform droidtop recognizes -- open one to edit or delete it (built-ins included); Restore defaults resets built-ins without touching your own",
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
                        ActionItem(
                            id = "platforms_restore",
                            title = "Restore defaults",
                            subtitle = "Reset every built-in platform to its original values",
                            confirmTitle = "Restore built-in platforms?",
                            run = { ctx ->
                                kotlinx.coroutines.runBlocking { ConsoleSystemsRepository.restoreDefaults(ctx) }
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
                            title = "${entity.displayName} (${entity.id})",
                            subtitle = listOfNotNull(
                                entity.extensionsCsv.ifBlank { null }?.let { "extensions: $it" },
                                entity.retroArchCore?.let { "core: $it" },
                                if (entity.isBuiltIn) "built-in" else "custom",
                            ).joinToString("  ·  "),
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
                val entity = existing?.id?.let { id -> dao.getAll().firstOrNull { it.id == id } } ?: existing
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
                                            kotlinx.coroutines.runBlocking {
                                                ConsoleSystemsDatabase.get(ctx).consoleSystemDao().upsert(entity.copy(displayName = v.trim().ifBlank { entity.id }))
                                            }
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
                                            kotlinx.coroutines.runBlocking {
                                                ConsoleSystemsDatabase.get(ctx).consoleSystemDao().upsert(entity.copy(extensionsCsv = cleaned))
                                            }
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
                                            kotlinx.coroutines.runBlocking {
                                                ConsoleSystemsDatabase.get(ctx).consoleSystemDao().upsert(entity.copy(retroArchCore = v.trim().ifBlank { null }))
                                            }
                                        } else {
                                            newCore = v
                                        }
                                    },
                                ),
                            )
                            if (entity == null) {
                                add(
                                    ActionItem(
                                        id = "platform_create",
                                        title = "Create platform",
                                        subtitle = "Needs at least an id",
                                        run = { ctx ->
                                            if (newId.isNotBlank()) {
                                                kotlinx.coroutines.runBlocking {
                                                    ConsoleSystemsDatabase.get(ctx).consoleSystemDao().upsert(
                                                        ConsoleSystemEntity(
                                                            id = newId,
                                                            displayName = newName.ifBlank { newId },
                                                            extensionsCsv = newExtensions,
                                                            retroArchCore = newCore.ifBlank { null },
                                                            isBuiltIn = false,
                                                        ),
                                                    )
                                                }
                                                newId = ""
                                                newName = ""
                                                newExtensions = ""
                                                newCore = ""
                                            }
                                        },
                                    ),
                                )
                            } else {
                                add(
                                    ActionItem(
                                        id = "platform_delete_${entity.id}",
                                        title = "Delete platform",
                                        subtitle = if (entity.isBuiltIn) "Built-in -- Restore defaults can bring it back" else "Removes this custom platform",
                                        confirmTitle = "Delete ${entity.displayName}?",
                                        run = { ctx ->
                                            kotlinx.coroutines.runBlocking {
                                                ConsoleSystemsDatabase.get(ctx).consoleSystemDao().delete(entity.id)
                                            }
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
