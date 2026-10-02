package dev.droidtop.app.settings

import android.content.Context
import dev.droidtop.library.LaunchDisplay
import dev.droidtop.library.consoles.ConsoleSystemDef
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.consoles.EmulatorDefaults
import dev.droidtop.library.consoles.EmulatorResolution
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
import dev.droidtop.library.settings.NestedScreenItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

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
        subtitle = "Which emulator runs each system, and what to do when none does",
        groups = { context -> groups(context, forIndex = false) },
        // The settings search reads the default-emulator row only; the
        // per-system rows are live library state, as for Console systems.
        indexGroups = { context -> groups(context, forIndex = true) },
    )

    // The one hint row for an emulator that can launch from a plain path but lacks All files access
    // (Droidtop/tracker#270): it only opens Android's own screen for the package; droidtop grants nothing.
    private fun fileAccessRow(id: String, name: String, packageName: String): ActionItem = ActionItem(
        id = id,
        title = "$name needs All files access",
        subtitle = "Opens Android's All files access screen for it. droidtop never grants it for you.",
        value = "Open settings",
        run = { ctx -> openAllFilesAccessSettings(ctx, packageName) },
    )

    // The RetroArch core a system launches with (Droidtop/tracker#271): installed, or installed
    // here without opening RetroArch when a root helper can place it.
    private fun coreRow(id: String, need: RetroArchCores.Need): AsyncActionItem {
        val state = RetroArchCores.state(need)
        return AsyncActionItem(
            id = id,
            title = "RetroArch core",
            // Without root droidtop cannot see RetroArch's cores, and RetroArch itself only shows
            // a black screen when one is missing (console, build 1386), so the row says what to check.
            subtitle = if (state == RetroArchCores.State.UNKNOWN) {
                "${need.core}. If games stay black, it is not installed in RetroArch"
            } else {
                need.core
            },
            value = when (state) {
                RetroArchCores.State.INSTALLED -> "Installed"
                RetroArchCores.State.MISSING -> "Install core"
                RetroArchCores.State.UNKNOWN -> "Open RetroArch"
            },
            run = { ctx, onStatus -> outcomeLine(RetroArchCores.ensure(ctx, need, onStatus), need) },
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
                                subtitle = "Install an emulator, then open a system below to pick it. " +
                                    "droidtop recognises the emulators in its platform database.",
                                run = {},
                            ),
                        )
                    } else {
                        installed.flatMap { (app, label) ->
                            val names = app.systemIds.mapNotNull { systemNames[it] }.sorted()
                            val shown = names.take(4).joinToString(", ") + if (names.size > 4) " and more" else ""
                            val row = ActionItem(
                                id = "emulator_app_${app.packageName}",
                                title = label,
                                subtitle = "Can run ${names.size} " + (if (names.size == 1) "system" else "systems") +
                                    (if (names.isEmpty()) "" else ": $shown"),
                                value = "Installed",
                                run = {},
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
                val retroArchCores = AsyncActionItem(
                    id = "emulators_retroarch_cores",
                    title = "RetroArch cores",
                    subtitle = "Install the cores these systems use",
                    run = { ctx, onStatus -> RetroArchCores.ensureForLibrary(ctx, withGames, onStatus) },
                )
                val usesRetroArch = resolvedById.values.any { resolved ->
                    resolved?.player?.let { RetroArchCores.needFor(it.packageName, it.argumentsTemplate) } != null
                }
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
                                resolved?.player?.let { RetroArchCores.needFor(it.packageName, it.argumentsTemplate) }?.let { need ->
                                    coreRow("emulator_core_${system.id}", need)
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
                    knownEmulatorsGroup(context, system),
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
        val missingRows = rows.filter { !it.first }.take(8)
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
                            "Add a custom player from the system's folder under Console systems.",
                        run = {},
                    ),
                )
            },
        )
    }

    /**
     * The launch test: pick one of this system's games and start it with
     * the emulator that would run it, getting a plain-words reason back if
     * it cannot start. It goes through [prepareLaunch], the launcher's own
     * preparation, so a test that passes is the launch that will run. It
     * reads the game file's name only and never copies or moves anything.
     */
    private fun launchTestScreen(system: ConsoleSystemDef): CatalogScreen = CatalogScreen(
        id = "emulator_test_${system.id}",
        title = "Launch test: ${system.displayName}",
        subtitle = "Pick a game to start with the emulator set for it",
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
                                    run = { ctx, onStatus -> runLaunchTest(ctx, system, file, onStatus) },
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
    ): String {
        onStatus("Checking the emulator...")
        val (resolved, prepared, needsAccess) = withContext(Dispatchers.IO) {
            val alt = runCatching { RomDatabase.get(context).romDao().getGameMetadataSingle(file.absolutePath)?.altEmulator }.getOrNull()
            val resolved = resolveEmulator(context, system, alt)
            Triple(
                resolved,
                resolved?.let { prepareLaunch(context, system, it.player, file, checkGameFile = true) },
                resolved?.let { playerNeedsAllFilesAccess(context, it.player) } == true,
            )
        }
        if (resolved == null || prepared == null) {
            return "Test failed: no emulator for ${system.displayName} is installed. Install one from this screen's list."
        }
        val name = resolved.player.name
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
                "Sent to $name (${resolved.source.label}). If it opened and showed the game, this system is set up. " +
                    "If $name opened without the game, its own settings need a look.$accessHint"
            } catch (e: Exception) {
                "Test failed: ${explainLaunchFailure(e, name)}$accessHint"
            }
        }
    }
}
