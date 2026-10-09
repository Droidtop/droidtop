package dev.droidtop.app.settings

import android.content.Context
import dev.droidtop.library.LaunchDisplay
import dev.droidtop.library.consoles.BiosDatabase
import dev.droidtop.library.consoles.ConfigSetting
import dev.droidtop.library.consoles.ConfigSpec
import dev.droidtop.library.consoles.ConfigText
import dev.droidtop.library.consoles.ConsoleSystemDef
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.consoles.CustomPlayerPrefs
import dev.droidtop.library.consoles.EmulatorAccess
import dev.droidtop.library.consoles.EmulatorDefaults
import dev.droidtop.library.consoles.EmulatorResolution
import dev.droidtop.library.consoles.EmulatorSetup
import dev.droidtop.library.consoles.EmulatorSetupSpec
import dev.droidtop.library.consoles.KnownPlayers
import dev.droidtop.library.consoles.Player
import dev.droidtop.library.consoles.PlayerOverridePrefs
import dev.droidtop.library.consoles.PreparedLaunch
import dev.droidtop.library.consoles.RetroArchCores
import dev.droidtop.library.consoles.RomDatabase
import dev.droidtop.library.consoles.SystemFolders
import dev.droidtop.library.consoles.availablePlayers
import dev.droidtop.library.consoles.detectEmulatorApps
import dev.droidtop.library.consoles.emulatorReadsStoragePaths
import dev.droidtop.library.consoles.explainLaunchFailure
import dev.droidtop.library.consoles.libretroCoreId
import dev.droidtop.library.consoles.openAllFilesAccessSettings
import dev.droidtop.library.consoles.playerNeedsAllFilesAccess
import dev.droidtop.library.consoles.prepareLaunch
import dev.droidtop.library.consoles.resolveEmulator
import dev.droidtop.library.integrations.PluginEventBus
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.ToggleItem
import java.io.File
import java.nio.file.Files
import dev.droidtop.runtime.tasks.RiskyActions
import dev.droidtop.runtime.tasks.RiskyClass
import dev.droidtop.runtime.tasks.RiskyPrompts
import dev.droidtop.runtime.tasks.TaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings > Emulators (Droidtop/tracker#248): the three levels of the
 * emulator choice in one place. The global default lives on the page
 * itself, each system's choice on its own screen, and a game's own choice
 * in that game's Edit metadata screen; [dev.droidtop.library.consoles.EmulatorResolution]
 * is the one function that combines them, here as in the launcher.
 *
 * Everything here reads the PackageManager and the games folders, so the
 * screens build on IO and bake the strings they show; nothing is looked up
 * while a row is drawn.
 */
object EmulatorsCatalog {
    const val SCREEN_EMULATORS = "emulators"

    // How many of a system's games the launch test offers: enough to pick
    // from, small enough that a huge folder is never read through.
    private const val TEST_GAME_LIMIT = 30

    fun screen(): CatalogScreen = CatalogScreen(
        id = SCREEN_EMULATORS,
        title = "Emulators",
        subtitle = "Emulator per system",
        groups = { context -> groups(context, forIndex = false) },
        // The settings search reads the default-emulator row only; the
        // per-system rows are live library state, as for Console systems.
        indexGroups = { context -> groups(context, forIndex = true) },
    )

    // The row for an emulator that can launch from a plain path but lacks All files access (Droidtop/tracker#270).
    // With Risky actions > Give another app access on and a provider that can set an appop, droidtop gives it after an
    // explicit yes naming the app; otherwise it only opens Android's own screen for the package, as it always did.
    // Reads the helper's capabilities: build it off the main thread.
    private fun fileAccessRow(id: String, name: String, packageName: String): CatalogItem {
        if (EmulatorAccess.canGrantAllFiles()) {
            return AsyncActionItem(
                id = id,
                title = RiskyPrompts.allFilesTitle(name),
                subtitle = "$name cannot open your games by their path yet. droidtop can give it All files access for you; " +
                    "you can take it back in Android's All files access screen",
                value = "Give access",
                confirmTitle = RiskyPrompts.allFilesConfirm(name, packageName),
                run = { ctx, _ ->
                    withContext(Dispatchers.IO) {
                        EmulatorAccess.grantAllFiles(ctx, packageName, name) ?: "$name can now read your game folders"
                    }
                },
            )
        }
        val canOffer = TaskManager.privileges().appOps && !RiskyActions.allows(RiskyClass.GRANT_ACCESS)
        return ActionItem(
            id = id,
            title = "$name needs All files access",
            subtitle = "Opens Android's All files access screen for it." +
                if (canOffer) " " + RiskyPrompts.turnOnHint(RiskyClass.GRANT_ACCESS) else "",
            value = "Open settings",
            run = { ctx -> openAllFilesAccessSettings(ctx, packageName) },
        )
    }

    // One row per runtime permission the emulator asks for and does not hold, when droidtop may grant it
    // (Risky actions > Give another app access, and a provider that serves it). None otherwise: there is no
    // Android screen to fall back to that lists just these, so nothing is drawn.
    private fun permissionRows(context: Context, systemId: String, name: String, packageName: String): List<CatalogItem> {
        if (!EmulatorAccess.canGrantPermission()) return emptyList()
        return EmulatorAccess.missingRuntimePermissions(context, packageName).map { missing ->
            AsyncActionItem(
                id = "emulator_permission_${systemId}_${missing.permission}",
                title = RiskyPrompts.permissionTitle(name, missing.label),
                subtitle = "$name has asked for this and does not have it. droidtop can give it for you",
                value = "Give permission",
                confirmTitle = RiskyPrompts.permissionConfirm(name, packageName, missing.permission),
                run = { ctx, _ ->
                    withContext(Dispatchers.IO) {
                        EmulatorAccess.grantPermission(ctx, packageName, missing.permission, name) ?: "$name can now use ${missing.label}"
                    }
                },
            )
        }
    }

    // The RetroArch core a system launches with (Droidtop/tracker#271): installed, or installed
    // here without opening RetroArch when a root helper can place it.
    private fun coreRow(context: Context, id: String, need: RetroArchCores.Need): AsyncActionItem {
        val state = RetroArchCores.state(need)
        return AsyncActionItem(
            id = id,
            title = "RetroArch core",
            // Without root droidtop cannot see RetroArch's cores, and RetroArch itself only shows
            // a black screen when one is missing (console, build 1386), so the row says what to check.
            subtitle = if (state == RetroArchCores.State.UNKNOWN) {
                "${need.core}. If games stay black, it is not installed in RetroArch" +
                    if (TaskManager.privileges().shell && !RiskyActions.allows(RiskyClass.ROOT_COMMANDS)) ". " + RiskyPrompts.turnOnHint(RiskyClass.ROOT_COMMANDS) else ""
            } else {
                need.core
            },
            value = when (state) {
                RetroArchCores.State.INSTALLED -> "Installed"
                RetroArchCores.State.MISSING -> "Install core"
                RetroArchCores.State.PARTIAL -> "Incomplete: reinstall in RetroArch"
                RetroArchCores.State.UNKNOWN -> "Open RetroArch"
            },
            // Placing the core is a root-level command in RetroArch's private folder: only with Risky actions >
            // Root-level commands on (the state is UNKNOWN otherwise), and only after a yes naming the file.
            confirmTitle = if (state == RetroArchCores.State.MISSING) RiskyPrompts.placeCoreConfirm(need.core, appLabel(context, need.packageName), need.corePath) else null,
            run = { ctx, onStatus ->
                outcomeLine(RetroArchCores.ensure(ctx, need, confirmed = state == RetroArchCores.State.MISSING, asked = true, onStatus = onStatus), need)
            },
        )
    }

    private fun outcomeLine(outcome: RetroArchCores.Outcome, need: RetroArchCores.Need): String = when (outcome) {
        RetroArchCores.Outcome.Ready -> "${need.core} is installed"
        is RetroArchCores.Outcome.Manual -> outcome.line
        is RetroArchCores.Outcome.Failed -> outcome.line
    }

    private fun appLabel(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private suspend fun groups(context: Context, forIndex: Boolean): List<CatalogGroup> = withContext(Dispatchers.IO) {
        val systems = ConsoleSystemsRepository.allSystems(context)
        val systemNames = systems.associate { it.id to it.displayName }
        val installed = detectEmulatorApps(context, systems)
            .filter { it.installed }
            .map { it to appLabel(context, it.packageName) }
            .sortedBy { it.second.lowercase() }
        val globalPackage = EmulatorDefaults.globalPackage(context)
        val pathPackages = KnownPlayers.all(context).filter { it.player.storagePathTemplate != null }.map { it.pkg }.toSet()
        buildList {
            add(
                CatalogGroup(
                    id = "emulators_default",
                    title = "Default emulator",
                    items = listOf(
                        ChoiceItem(
                            id = "emulator_global_default",
                            title = "Default emulator",
                            subtitle = "Runs every system that has no emulator of its own, when it can run that system. " +
                                "A system's own choice and a game's own choice both come first.",
                            options = listOf(ChoiceOption("", "None: the first installed emulator for each system")) +
                                installed.map { (app, label) -> ChoiceOption(app.packageName, label) },
                            current = globalPackage ?: "",
                            onSelect = { ctx, value -> EmulatorDefaults.setGlobalPackage(ctx, value.ifEmpty { null }) },
                        ),
                    ),
                ),
            )
            add(
                CatalogGroup(
                    id = "emulators_installed",
                    title = "Found on this device",
                    items = if (installed.isEmpty()) {
                        listOf(
                            ActionItem(
                                id = "emulators_none_installed",
                                title = "No emulators found",
                                run = {},
                            ),
                        )
                    } else {
                        installed.flatMap { (app, label) ->
                            val names = app.systemIds.mapNotNull { systemNames[it] }.sorted()
                            val shown = names.take(4).joinToString(", ") + if (names.size > 4) " and more" else ""
                            val row = openEmulatorRow(
                                id = "emulator_app_${app.packageName}",
                                title = label,
                                subtitle = "Can run ${names.size} " + (if (names.size == 1) "system" else "systems") +
                                    (if (names.isEmpty()) "" else ": $shown") + ". Press A to open it for its own settings",
                                name = label,
                                packageName = app.packageName,
                            )
                            val needsAccess = app.packageName in pathPackages &&
                                !emulatorReadsStoragePaths(context, app.packageName)
                            if (needsAccess) {
                                listOf(row, fileAccessRow("emulator_access_${app.packageName}", label, app.packageName))
                            } else {
                                listOf(row)
                            }
                        }
                    },
                ),
            )
            if (!forIndex) add(systemsGroup(context, systems, globalPackage))
        }
    }

    // One row per system that has a games folder, the ones with nothing
    // that can run them first: that is the list a person came here to fix.
    private fun systemsGroup(context: Context, systems: List<ConsoleSystemDef>, globalPackage: String?): CatalogGroup {
        val systemsById = systems.associateBy { it.id }
        val withGames = SystemFolders.all(context, systemsById).map { it.second }.distinctBy { it.id }
        val resolvedById = withGames.associate { system ->
            system.id to EmulatorResolution.resolveWithoutGame(
                availablePlayers(context, system),
                PlayerOverridePrefs.get(context, system.id),
                globalPackage,
            )
        }
        val rows = withGames.map { system ->
            val resolved = resolvedById[system.id]
            val row = NestedScreenItem(
                id = "emulator_system_${system.id}",
                title = system.displayName,
                subtitle = resolved?.let { "Chosen: ${it.source.label}" }
                    ?: "No emulator installed. Open it to see what to install.",
                inline = systemScreen(system),
                valueLabel = { _ -> resolved?.player?.name ?: "Not set up" },
            )
            Triple(resolved == null, system.displayName.lowercase(), row)
        }.sortedWith(compareBy<Triple<Boolean, String, NestedScreenItem>>({ !it.first }, { it.second }))
        return CatalogGroup(
            id = "emulators_systems",
            title = "Systems with games",
            items = if (rows.isEmpty()) {
                listOf(
                    ActionItem(
                        id = "emulators_no_systems",
                        title = "No game folders yet",
                        subtitle = "Add a games folder under Console systems, then each system appears here.",
                        run = {},
                    ),
                )
            } else {
                val needs = resolvedById.values.mapNotNull { resolved ->
                    resolved?.player?.let { RetroArchCores.needFor(it.packageName, it.argumentsTemplate) }
                }.distinctBy { it.packageName to it.core }
                // The cores a yes would place, named in the confirmation (a root-level command, Risky actions); none
                // while the switch is off or no root provider is there, and then the row only opens RetroArch.
                val toPlace = if (RetroArchCores.canPlace()) needs.filter { RetroArchCores.state(it) == RetroArchCores.State.MISSING } else emptyList()
                val retroArchCores = AsyncActionItem(
                    id = "emulators_retroarch_cores",
                    title = "RetroArch cores",
                    subtitle = "Install the cores these systems use",
                    confirmTitle = if (toPlace.isEmpty()) null else RiskyPrompts.placeCoresConfirm(
                        toPlace.map { it.corePath },
                        appLabel(context, toPlace.first().packageName),
                    ),
                    run = { ctx, onStatus -> RetroArchCores.ensureForLibrary(ctx, withGames, onStatus) },
                )
                val usesRetroArch = needs.isNotEmpty()
                (if (usesRetroArch) listOf<CatalogItem>(retroArchCores) else emptyList()) + rows.map { it.third }
            },
        )
    }

    /**
     * One system's emulator screen: the choice, what is in use and why,
     * what droidtop knows for it (installed or to get), and the launch
     * test. Also the target of the folder screen's "Emulator setup" row.
     */
    fun systemScreen(system: ConsoleSystemDef): CatalogScreen = CatalogScreen(
        id = "emulator_system_screen_${system.id}",
        title = "${system.displayName} emulator",
        groups = { context ->
            withContext(Dispatchers.IO) {
                val candidates = availablePlayers(context, system)
                val resolved = EmulatorResolution.resolveWithoutGame(
                    candidates,
                    PlayerOverridePrefs.get(context, system.id),
                    EmulatorDefaults.globalPackage(context),
                )
                listOf(
                    CatalogGroup(
                        id = "emulator_system_choice",
                        title = null,
                        items = listOf(
                            systemPlayerChoiceItem(context, system),
                            ActionItem(
                                id = "emulator_system_in_use_${system.id}",
                                title = "In use",
                                subtitle = if (resolved == null) {
                                    "Nothing installed can run ${system.displayName} yet. Install one below."
                                } else {
                                    "${resolved.player.name}, ${resolved.source.label}. " +
                                        "A single game can use another emulator from its Edit metadata screen."
                                },
                                value = resolved?.player?.name ?: "None",
                                run = {},
                            ),
                            *listOfNotNull(
                                resolved?.player?.takeIf { playerNeedsAllFilesAccess(context, it) }?.let {
                                    fileAccessRow("emulator_access_${system.id}", it.name, it.packageName)
                                },
                            ).toTypedArray(),
                            *(resolved?.player?.let { permissionRows(context, system.id, appLabel(context, it.packageName), it.packageName) }.orEmpty()).toTypedArray(),
                            *listOfNotNull(
                                resolved?.player?.let { RetroArchCores.needFor(it.packageName, it.argumentsTemplate) }?.let { need ->
                                    coreRow(context, "emulator_core_${system.id}", need)
                                },
                            ).toTypedArray(),
                            *listOfNotNull(
                                resolved?.player?.let { player ->
                                    openEmulatorRow(
                                        id = "emulator_open_${system.id}",
                                        title = "${player.name} settings",
                                        subtitle = "Opens ${player.name} for its own settings: graphics, controls, where it keeps BIOS files. " +
                                            "droidtop does not change them",
                                        name = player.name,
                                        packageName = player.packageName,
                                    )
                                },
                            ).toTypedArray(),
                            NestedScreenItem(
                                id = "emulator_test_open_${system.id}",
                                title = "Launch test",
                                subtitle = "Start one of your games to check this emulator works, and say why if it does not",
                                inline = launchTestScreen(system),
                            ),
                        ),
                    ),
                ) + listOfNotNull(resolved?.player?.let { setupGroup(context, system, it) }) + listOf(
                    knownEmulatorsGroup(context, system),
                    customPlayersGroup(context, system),
                )
            }
        },
    )

    /**
     * The system's emulator choice. The one picker for it: Console systems'
     * folder screen shows this same item, and the event a manager plugin
     * listens for (docs/SPEC.md 12a "Event hooks") is fired from here, the
     * one write path of this choice.
     */
    fun systemPlayerChoiceItem(context: Context, system: ConsoleSystemDef): ChoiceItem {
        val players = availablePlayers(context, system)
        return ChoiceItem(
            id = "system_player_${system.id}",
            title = "Emulator",
            subtitle = if (players.isEmpty()) {
                "No installed emulator can run ${system.displayName} yet. Install one, or add a custom player."
            } else {
                "Which installed emulator launches ${system.displayName}. Automatic follows your default emulator, then the first one installed."
            },
            options = listOf(ChoiceOption("", "Automatic")) + players.map { ChoiceOption(it.id, optionLabel(it, system)) },
            current = PlayerOverridePrefs.get(context, system.id) ?: "",
            onSelect = { ctx, value ->
                PlayerOverridePrefs.set(ctx, system.id, value.ifEmpty { null })
                // The resolved player, "automatic" included, so a
                // subscribed plugin sees the real effective choice and the
                // core it launches with: the entry's own LIBRETRO core when
                // its template names one, else the system's configured core.
                val chosen = EmulatorResolution.resolveWithoutGame(
                    players,
                    value.ifEmpty { null },
                    EmulatorDefaults.globalPackage(ctx),
                )?.player
                if (chosen != null) {
                    PluginEventBus.notifyDefaultPlayerChangedAsync(
                        context = ctx,
                        systemId = system.id,
                        systemName = system.displayName,
                        playerId = chosen.id,
                        playerName = chosen.name,
                        playerPackage = chosen.packageName,
                        core = libretroCoreId(chosen, system.retroArchCore),
                    )
                }
            },
        )
    }

    // The generic RetroArch entry is named only "RetroArch"; its core is
    // what tells it from the database's per-core entries.
    private fun optionLabel(player: Player.AmStart, system: ConsoleSystemDef): String =
        if (player.packageName.startsWith("com.retroarch") && player.name == "RetroArch") {
            val core = libretroCoreId(player, system.retroArchCore)
            if (core != null) "RetroArch, core $core" else "RetroArch"
        } else {
            player.name
        }

    // What droidtop knows can run this system: installed ones say so, the
    // rest say "Not installed" and open the store page to get them.
    private fun knownEmulatorsGroup(context: Context, system: ConsoleSystemDef): CatalogGroup {
        val presets = KnownPlayers.forSystem(context, system.id)
        val byPackage = presets.groupBy { it.pkg }
        val rows = byPackage.map { (pkg, list) ->
            val isInstalled = runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess
            val name = if (isInstalled) {
                appLabel(context, pkg)
            } else {
                list.first().label.takeUnless { '.' in it } ?: pkg
            }
            Triple(isInstalled, name, pkg)
        }.sortedWith(compareBy<Triple<Boolean, String, String>>({ !it.first }, { it.second.lowercase() }))
        val installedRows = rows.filter { it.first }
        // One emulator can be published under more than one package (ARMSX2 is listed as
        // com.armsx2 and come.nanodata.armsx2): a "Not installed" row with the name of one that is
        // installed read as the same emulator listed twice (console, build 1386).
        val installedNames = installedRows.map { it.second.lowercase() }.toSet()
        val missingRows = rows.filter { !it.first && it.second.lowercase() !in installedNames }
            .distinctBy { it.second.lowercase() }
            .take(8)
        return CatalogGroup(
            id = "emulator_system_known",
            title = "Emulators for ${system.displayName}",
            items = (installedRows + missingRows).map { (isInstalled, name, pkg) ->
                ActionItem(
                    id = "emulator_known_${system.id}_$pkg",
                    title = name,
                    subtitle = if (isInstalled) pkg else "Not installed. Press A to get it.",
                    value = if (isInstalled) "Installed" else "Not installed",
                    run = if (isInstalled) ({ _ -> }) else AppSettingsCatalogs.installPackageAction(pkg),
                )
            }.ifEmpty {
                listOf(
                    ActionItem(
                        id = "emulator_known_none_${system.id}",
                        title = "No known emulators",
                        subtitle = "droidtop's platform database has none for ${system.displayName}. " +
                            "Add a custom player below.",
                        run = {},
                    ),
                )
            },
        )
    }

    /**
     * Opens an installed emulator itself, for the settings only it can change (Droidtop/tracker#248
     * item 4: droidtop shows an emulator's own options only where it can set them from outside, and
     * otherwise opens the emulator). Through [LaunchDisplay.start], the one way droidtop starts an app.
     */
    private fun openEmulatorRow(id: String, title: String, subtitle: String, name: String, packageName: String) = AsyncActionItem(
        id = id,
        title = title,
        subtitle = subtitle,
        value = "Open",
        run = { ctx, _ ->
            val intent = withContext(Dispatchers.IO) {
                ctx.packageManager.getLaunchIntentForPackage(packageName)
                    ?: ctx.packageManager.getLeanbackLaunchIntentForPackage(packageName)
            }
            if (intent == null) {
                "$name has no screen of its own to open"
            } else {
                withContext(Dispatchers.Main) { LaunchDisplay.start(ctx, intent) }
                "Opened $name"
            }
        },
    )

    /**
     * The emulator setup helper for the emulator in use (docs/SPEC.md "Emulator setup helper", Droidtop/tracker#248):
     * its BIOS files and the options droidtop can set in its own config, from [EmulatorSetup.specFor], the one source
     * of what an emulator needs. Where droidtop can reach the emulator's files (itself, or through Shizuku when the
     * person runs it) a row does the job; where it cannot, the same row says where to do it inside the emulator and
     * opens it. Built on IO with the file already read, so nothing is looked up while a row is drawn.
     */
    private fun setupGroup(context: Context, system: ConsoleSystemDef, player: Player.AmStart): CatalogGroup? {
        val spec = EmulatorSetup.specFor(context, player) ?: return null
        val name = appLabel(context, player.packageName)
        val configText = spec.config?.let { EmulatorSetup.read(it.file) }
        val items = buildList<CatalogItem> {
            addAll(biosItems(context, system, player, name, spec, configText))
            spec.config?.let { config ->
                config.settings.forEach { add(settingItem(player, name, config, it, configText)) }
            }
        }
        if (items.isEmpty()) return null
        return CatalogGroup(id = "emulator_setup_${system.id}", title = "Set up $name", items = items)
    }

    private fun biosItems(
        context: Context,
        system: ConsoleSystemDef,
        player: Player.AmStart,
        name: String,
        spec: EmulatorSetupSpec,
        configText: String?,
    ): List<CatalogItem> {
        val bios = BiosDatabase.forSystem(context, system.id) ?: return emptyList()
        val needed = bios.files.map { EmulatorSetup.biosRelative(it.file) }
        if (needed.isEmpty()) return emptyList()
        val shown = needed.take(4).joinToString(", ") + if (needed.size > 4) " and ${needed.size - 4} more" else ""
        val folder = EmulatorSetup.biosFolder(spec, configText)
            ?: return listOf(
                openEmulatorRow(
                    id = "emulator_bios_${system.id}",
                    title = "BIOS files for ${system.displayName}",
                    subtitle = "Needs $shown. Put them in $name's BIOS folder: " + (spec.biosManual ?: "$name's settings show where it is"),
                    name = name,
                    packageName = player.packageName,
                ),
            )
        val present = EmulatorSetup.listFolder(folder)
        val missing = present?.let { have -> needed.filter { want -> have.none { it.equals(want, ignoreCase = true) } } }
        val reach = EmulatorSetup.writeReach("$folder/x")
        val writable = reach == EmulatorSetup.Reach.DIRECT || reach == EmulatorSetup.Reach.HELPER
        val status = ActionItem(
            id = "emulator_bios_${system.id}",
            title = "BIOS files for ${system.displayName}",
            subtitle = when {
                missing == null -> "Needs $shown in $folder. droidtop cannot look in that folder to check"
                missing.isEmpty() -> "Every file droidtop knows of is in $folder"
                else -> "Missing ${missing.take(4).joinToString(", ")} in $folder"
            } + when {
                writable -> ""
                reach == EmulatorSetup.Reach.LOCKED -> ". Copy them there with a file manager, or from $name's own settings. " +
                    RiskyPrompts.turnOnHint(RiskyClass.OTHER_APP_FILES)
                else -> ". Copy them there with a file manager, or from $name's own settings"
            },
            value = when {
                missing == null -> "Unknown"
                missing.isEmpty() -> "All there"
                else -> "${missing.size} missing"
            },
            run = {},
        )
        if (!writable) return listOf(status)
        return listOfNotNull(
            status,
            // Only while a plugin that supplies BIOS files is installed and allowed (emulator.bios, Droidtop/tracker#415).
            dev.droidtop.library.integrations.PluginBios.row(context, system.id, system.displayName, bios, folder, name, reach == EmulatorSetup.Reach.HELPER),
            DocumentPickItem(
                id = "emulator_bios_add_${system.id}",
                title = "Add a BIOS file",
                subtitle = "Pick one of your own BIOS files; droidtop copies it into $name's BIOS folder under the name $name looks for",
                mimeType = "*/*",
                // Through the helper this writes into another app's folder (a risky action): say where, before the picker.
                confirmTitle = if (reach == EmulatorSetup.Reach.HELPER) RiskyPrompts.addFileConfirm(name, folder) else null,
                onPicked = { ctx, uri -> withContext(Dispatchers.IO) { placeBios(ctx, uri, bios, folder, name) } },
            ),
        )
    }

    // Reads the picked file (never past what a BIOS file can be), names it by the database, and writes it whole.
    private fun placeBios(
        context: Context,
        uri: android.net.Uri,
        bios: dev.droidtop.library.consoles.SystemBiosSpec,
        folder: String,
        name: String,
    ): String = runCatching {
        val picked = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() && it != "." && it != ".." }
            ?: "bios.bin"
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                require(out.size() <= dev.droidtop.runtime.tasks.ElevatedFiles.MAX_WRITE_BYTES) { "that file is too large to be a BIOS file" }
            }
            out.toByteArray()
        } ?: error("the file could not be opened")
        val target = EmulatorSetup.biosTarget(bios, picked, EmulatorSetup.md5(bytes))
        if (EmulatorSetup.write("$folder/$target", bytes)) "Added $target to $name's BIOS folder" else "Not added: droidtop could not write to $folder"
    }.getOrElse { "Not added: ${it.message ?: it.javaClass.simpleName}" }

    private fun settingItem(player: Player.AmStart, name: String, config: ConfigSpec, setting: ConfigSetting, configText: String?): CatalogItem {
        val reach = EmulatorSetup.writeReach(config.file)
        val writable = configText != null && (reach == EmulatorSetup.Reach.DIRECT || reach == EmulatorSetup.Reach.HELPER)
        if (!writable) {
            return openEmulatorRow(
                id = "emulator_setting_${setting.id}",
                title = setting.label,
                subtitle = "${setting.about}. To change it: ${setting.manual}" +
                    if (configText != null && reach == EmulatorSetup.Reach.LOCKED) ". " + RiskyPrompts.turnOnHint(RiskyClass.OTHER_APP_FILES) else "",
                name = name,
                packageName = player.packageName,
            )
        }
        val current = ConfigText.get(configText!!, config.format, setting.section, setting.key)
        val currentLabel = setting.options.firstOrNull { it.value.equals(current, ignoreCase = true) }?.label
        return AsyncActionItem(
            id = "emulator_setting_${setting.id}",
            title = setting.label,
            subtitle = setting.about + if (config.writesOnExit) ". Close $name first if it is open: it writes its own settings back when it closes" else "",
            value = currentLabel ?: "$name's default",
            // Through the helper this rewrites a file in another app's folder (a risky action): name it first.
            confirmTitle = if (reach == EmulatorSetup.Reach.HELPER) RiskyPrompts.writeFileConfirm(name, config.file) else null,
            run = { _, _ ->
                withContext(Dispatchers.IO) {
                    val text = EmulatorSetup.read(config.file)
                        ?: return@withContext "Not changed: droidtop could not read $name's settings. To change it: ${setting.manual}"
                    val now = ConfigText.get(text, config.format, setting.section, setting.key)
                    val index = setting.options.indexOfFirst { it.value.equals(now, ignoreCase = true) }
                    val next = setting.options[(index + 1).mod(setting.options.size)]
                    val changed = ConfigText.set(text, config.format, setting.section, setting.key, next.value)
                    if (EmulatorSetup.write(config.file, changed.toByteArray())) {
                        "${next.label}. $name uses it the next time it starts"
                    } else {
                        "Not changed: droidtop could not write $name's settings. To change it: ${setting.manual}"
                    }
                }
            },
        )
    }

    /**
     * The system's custom players (Droidtop/tracker#248 item 3): any installed app wired to the
     * system by its launch command, for an emulator droidtop's database does not know or a launch it
     * gets wrong. Each opens its own screen to edit, check, test, share or remove it; new ones are
     * typed in or loaded from a shared file. They are offered in the Emulator choice above like any
     * other installed emulator ([availablePlayers] lists them first).
     */
    private fun customPlayersGroup(context: Context, system: ConsoleSystemDef): CatalogGroup {
        val players = CustomPlayerPrefs.getForSystem(context, system.id)
        return CatalogGroup(
            id = "emulator_custom_players",
            title = "Custom players",
            items = players.map { player ->
                NestedScreenItem(
                    id = "emulator_custom_${player.id}",
                    title = player.name,
                    subtitle = player.packageName,
                    inline = customPlayerScreen(system, player.id),
                )
            } + listOf(
                NestedScreenItem(
                    id = "emulator_custom_add_${system.id}",
                    title = "Add a custom player",
                    subtitle = "Point ${system.displayName} at any installed app by its launch command",
                    inline = addCustomPlayerScreen(system),
                ),
                DocumentPickItem(
                    id = "emulator_custom_import_${system.id}",
                    title = "Add from a file",
                    subtitle = "A custom player someone shared, or a players database file",
                    mimeType = "*/*",
                    onPicked = { ctx, uri ->
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val text = ctx.contentResolver.openInputStream(uri)?.use { input ->
                                    input.bufferedReader().readText()
                                } ?: error("the file could not be opened")
                                CustomPlayerPrefs.importJson(ctx, text).message
                            }.getOrElse { "Not added: this is not a players file (${it.message ?: it.javaClass.simpleName})" }
                        }
                    },
                ),
            ),
        )
    }

    // Pending-buffer form: fields buffer here, Save commits atomically, after the same check the
    // edit screen shows, so a command that cannot launch is never saved silently.
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
                            TextInputItem(id = "add_player_name", title = "Player name", value = name, onChange = { _, v -> name = v }),
                            TextInputItem(
                                id = "add_player_pkg",
                                title = "Package name",
                                subtitle = "e.g. org.example.app",
                                value = pkg,
                                onChange = { _, v -> pkg = v.trim() },
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
                            AsyncActionItem(
                                id = "add_player_save",
                                title = "Save player",
                                subtitle = "Checks the command first; without a name it uses the package name",
                                run = { ctx, _ ->
                                    val faults = CustomPlayerPrefs.problems(pkg, args)
                                    if (faults.isNotEmpty()) {
                                        "Not saved: " + faults.joinToString(" ")
                                    } else {
                                        val saved = withContext(Dispatchers.IO) {
                                            CustomPlayerPrefs.add(ctx, system.id, name.ifBlank { pkg }, args, pkg, kill)
                                        }
                                        name = ""
                                        pkg = ""
                                        kill = false
                                        "Saved ${saved.name}. It is under Custom players, and in the Emulator choice once its app is installed"
                                    }
                                },
                            ),
                        ),
                    ),
                )
            },
        )
    }

    // One custom player: its fields write through (re-read on every entry, so the screen always
    // shows what is stored), then the check, a launch test with just this player, sharing and removal.
    private fun customPlayerScreen(system: ConsoleSystemDef, playerId: String): CatalogScreen = CatalogScreen(
        id = "emulator_custom_screen_$playerId",
        title = "Custom player",
        subtitle = "Use {file.path} and {file.uri} in the arguments for the file being played",
        groups = { context ->
            withContext(Dispatchers.IO) {
                val player = CustomPlayerPrefs.getForSystem(context, system.id).firstOrNull { it.id == playerId }
                if (player == null) {
                    return@withContext listOf(
                        CatalogGroup(
                            id = "emulator_custom_gone",
                            title = null,
                            items = listOf(ActionItem(id = "emulator_custom_gone_row", title = "This custom player was removed", run = {})),
                        ),
                    )
                }
                val edit: suspend (Context, (Player.AmStart) -> Player.AmStart) -> Unit = { ctx, change ->
                    withContext(Dispatchers.IO) {
                        CustomPlayerPrefs.getForSystem(ctx, system.id).firstOrNull { it.id == playerId }
                            ?.let { CustomPlayerPrefs.update(ctx, system.id, change(it)) }
                    }
                }
                val faults = CustomPlayerPrefs.problems(player.packageName, player.argumentsTemplate)
                val installed = runCatching { context.packageManager.getApplicationInfo(player.packageName, 0) }.isSuccess
                listOf(
                    CatalogGroup(
                        id = "emulator_custom_fields",
                        title = null,
                        items = listOf(
                            TextInputItem(
                                id = "emulator_custom_name",
                                title = "Player name",
                                value = player.name,
                                onChange = { ctx, v -> edit(ctx) { it.copy(name = v.ifBlank { it.packageName }) } },
                            ),
                            TextInputItem(
                                id = "emulator_custom_pkg",
                                title = "Package name",
                                value = player.packageName,
                                onChange = { ctx, v -> edit(ctx) { it.copy(packageName = v.trim()) } },
                            ),
                            TextInputItem(
                                id = "emulator_custom_args",
                                title = "am start arguments",
                                value = player.argumentsTemplate,
                                multiline = true,
                                onChange = { ctx, v -> edit(ctx) { it.copy(argumentsTemplate = v) } },
                            ),
                            ToggleItem(
                                id = "emulator_custom_kill",
                                title = "Kill package processes before launch",
                                current = player.killPackageProcesses,
                                onToggle = { ctx, v -> edit(ctx) { it.copy(killPackageProcesses = v) } },
                            ),
                        ),
                    ),
                    CatalogGroup(
                        id = "emulator_custom_actions",
                        title = null,
                        items = listOf(
                            ActionItem(
                                id = "emulator_custom_check",
                                title = "Check",
                                subtitle = when {
                                    faults.isNotEmpty() -> faults.joinToString(" ")
                                    !installed -> "The command reads right, but ${player.packageName} is not installed"
                                    else -> "The command reads right. The launch test shows whether the app takes it"
                                },
                                value = if (faults.isEmpty()) "OK" else "Fix it",
                                run = {},
                            ),
                            NestedScreenItem(
                                id = "emulator_custom_test",
                                title = "Launch test",
                                subtitle = "Start one of your games with this player",
                                inline = launchTestScreen(system, player),
                            ),
                            DocumentPickItem(
                                id = "emulator_custom_share",
                                title = "Save as a file",
                                subtitle = "A players database file to share, or to load on another device with Add from a file",
                                mimeType = "application/json",
                                createName = player.name.replace(Regex("[^A-Za-z0-9._-]+"), "-") + ".json",
                                onPicked = { ctx, uri ->
                                    withContext(Dispatchers.IO) {
                                        runCatching {
                                            val text = CustomPlayerPrefs.shareJson(system.id, listOf(player))
                                            ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
                                                ?: error("the file could not be opened")
                                            "Saved ${player.name}"
                                        }.getOrElse { "Not saved: ${it.message ?: it.javaClass.simpleName}" }
                                    }
                                },
                            ),
                            AsyncActionItem(
                                id = "emulator_custom_remove",
                                title = "Remove",
                                subtitle = "A system or game set to this player falls back to the next choice: your default emulator, then the first installed one",
                                confirmTitle = "Remove ${player.name}?",
                                run = { ctx, _ ->
                                    withContext(Dispatchers.IO) { CustomPlayerPrefs.remove(ctx, system.id, playerId) }
                                    "Removed ${player.name}"
                                },
                            ),
                        ),
                    ),
                )
            }
        },
    )

    /**
     * The launch test: pick one of this system's games and start it with
     * the emulator that would run it, getting a plain-words reason back if
     * it cannot start. It goes through [prepareLaunch], the launcher's own
     * preparation, so a test that passes is the launch that will run. It
     * reads the game file's name only and never copies or moves anything.
     */
    private fun launchTestScreen(system: ConsoleSystemDef, player: Player.AmStart? = null): CatalogScreen = CatalogScreen(
        id = "emulator_test_${system.id}" + (player?.let { "_${it.id}" } ?: ""),
        title = "Launch test: ${player?.name ?: system.displayName}",
        subtitle = if (player == null) "Pick a game to start with the emulator set for it" else "Pick a game to start with ${player.name}",
        groups = { context ->
            withContext(Dispatchers.IO) {
                val games = testGames(context, system)
                listOf(
                    CatalogGroup(
                        id = "emulator_test_games",
                        title = null,
                        items = if (games.isEmpty()) {
                            listOf<CatalogItem>(
                                ActionItem(
                                    id = "emulator_test_no_games_${system.id}",
                                    title = "No games found",
                                    subtitle = "droidtop found no ${system.displayName} games in your games folders to test with.",
                                    run = {},
                                ),
                            )
                        } else {
                            games.mapIndexed { index, file ->
                                AsyncActionItem(
                                    id = "emulator_test_game_${system.id}_$index",
                                    title = file.nameWithoutExtension,
                                    subtitle = "Start it now and report whether it worked",
                                    run = { ctx, onStatus -> runLaunchTest(ctx, system, file, onStatus, player) },
                                )
                            }
                        },
                    ),
                )
            }
        },
    )

    // Top-level files of the system's folders, matched by extension and
    // read lazily so a folder of thousands is never listed whole.
    private fun testGames(context: Context, system: ConsoleSystemDef): List<File> {
        val folders = SystemFolders.all(context, mapOf(system.id to system)).map { it.first }
        val found = ArrayList<File>()
        for (folder in folders) {
            if (found.size >= TEST_GAME_LIMIT) break
            runCatching {
                Files.newDirectoryStream(folder.toPath()).use { stream ->
                    for (path in stream) {
                        val file = path.toFile()
                        if (file.extension.lowercase() in system.extensions && file.isFile) {
                            found += file
                            if (found.size >= TEST_GAME_LIMIT) break
                        }
                    }
                }
            }
        }
        return found.sortedBy { it.name.lowercase() }
    }

    private suspend fun runLaunchTest(
        context: Context,
        system: ConsoleSystemDef,
        file: File,
        onStatus: (String) -> Unit,
        only: Player.AmStart? = null,
    ): String {
        onStatus("Checking the emulator...")
        val (resolved, prepared, needsAccess) = withContext(Dispatchers.IO) {
            // A custom player's own test runs that player; otherwise the game's emulator as a launch picks it.
            val resolved = if (only != null) {
                only to "custom player"
            } else {
                val alt = runCatching { RomDatabase.get(context).romDao().getGameMetadataSingle(file.absolutePath)?.altEmulator }.getOrNull()
                resolveEmulator(context, system, alt)?.let { it.player to it.source.label }
            }
            Triple(
                resolved,
                resolved?.let { prepareLaunch(context, system, it.first, file, checkGameFile = true) },
                resolved?.let { playerNeedsAllFilesAccess(context, it.first) } == true,
            )
        }
        if (resolved == null || prepared == null) {
            return "Test failed: no emulator for ${system.displayName} is installed. Install one from this screen's list."
        }
        val (player, source) = resolved
        val name = player.name
        // A hint, never a blocker: the launch was or was not sent exactly as it would be from the shell.
        val accessHint = if (needsAccess) {
            " $name needs All files access: the row on this system's emulator screen opens Android's settings for it."
        } else {
            ""
        }
        return when (prepared) {
            is PreparedLaunch.Blocked -> "Test failed: ${prepared.reason}$accessHint"
            is PreparedLaunch.Ready -> try {
                withContext(Dispatchers.Main) { LaunchDisplay.start(context, prepared.intent) }
                "Sent to $name ($source). If it opened and showed the game, this system is set up. " +
                    "If $name opened without the game, its own settings need a look.$accessHint"
            } catch (e: Exception) {
                "Test failed: ${explainLaunchFailure(e, name)}$accessHint"
            }
        }
    }
}
