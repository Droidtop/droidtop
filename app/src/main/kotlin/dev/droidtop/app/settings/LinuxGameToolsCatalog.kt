package dev.droidtop.app.settings

import dev.droidtop.library.LinuxGameOptionsPrefs
import dev.droidtop.library.LinuxToolsScreen
import dev.droidtop.library.PcGameRuntimeRegistry
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.ToggleItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The prefix tools of a native Linux game (docs/SPEC.md 7c, "Prefix tools for Linux games"), the Linux
 * counterpart of [PrefixToolsCatalog]: such a game runs inside Desktop mode's container (through FEX or
 * box64 when its code is x86, SPEC 3c), and its "launch environment" is that container with, when the
 * person asks, a home of its own. Run a program from the game's folder, stop its processes, keep its saves
 * and settings in a home of its own, and reset that home. The shared container is never reset here.
 * Opened from the game's page ([LinuxToolsScreen]); [PcGameRuntimeRegistry] reaches the runtime.
 */
object LinuxGameToolsCatalog {

    /** The path typed into "Path to a program", until it is run. */
    @Volatile private var typedPath: String = ""

    fun screen(): CatalogScreen = CatalogScreen(
        id = LinuxToolsScreen.ID,
        title = "Linux tools",
        groups = { context -> groups(context, null) },
        indexGroups = { _ -> emptyList() },
        forDeepLink = { argument ->
            val target = LinuxToolsScreen.parse(argument)
            CatalogScreen(
                id = LinuxToolsScreen.ID,
                title = "Linux tools",
                subtitle = target.title,
                groups = { context -> groups(context, target) },
                indexGroups = { _ -> emptyList() },
            )
        },
    )

    private suspend fun groups(context: android.content.Context, target: LinuxToolsScreen.Target?): List<CatalogGroup> {
        if (target == null) {
            return listOf(
                CatalogGroup("linux_tools_none", null, listOf(ActionItem(id = "linux_tools_open_from_game", title = "Open this from a game's page", run = {}))),
            )
        }
        val runtime = PcGameRuntimeRegistry.runtime
        val ownHome = withContext(Dispatchers.IO) { LinuxGameOptionsPrefs.ownHome(context, target.entryId) }
        val up = runtime?.isLinuxContainerAvailable == true
        val gameRoot = File(target.gameRoot)
        return listOf(
            CatalogGroup(
                id = "linux_tools_state",
                title = null,
                items = listOf(
                    ActionItem(
                        id = "linux_tools_where",
                        title = "Runs in Desktop mode's container",
                        subtitle = if (up) "The container is running" else "The container is not running. Start Desktop mode first: these tools act inside it",
                        value = if (ownHome) "Own home" else "Shared home",
                        run = {},
                    ),
                    ToggleItem(
                        id = "linux_tools_own_home",
                        title = "Keep this game's saves and settings in a home of its own",
                        subtitle = "Off: the game uses the container's home, as it always has, and its saves stay where they are. " +
                            "On: from its next start the game's home and XDG folders are a folder only this game uses. " +
                            "Saves already in the container's home are not moved",
                        current = ownHome,
                        onToggle = { ctx, on -> withContext(Dispatchers.IO) { LinuxGameOptionsPrefs.setOwnHome(ctx, target.entryId, on) } },
                    ),
                ),
            ),
            CatalogGroup(
                id = "linux_tools_run",
                title = "Run a program from this game's folder",
                items = listOf(
                    DocumentPickItem(
                        id = "linux_tools_run_pick",
                        title = "Choose a program",
                        subtitle = "A file inside the game's folder, run where it is in the same environment as the game",
                        mimeType = "*/*",
                        onPicked = { ctx, uri ->
                            val file = withContext(Dispatchers.IO) { PickedFiles.fileOf(uri) }
                                ?: return@DocumentPickItem "Couldn't get that file's real path on this device; type its path below"
                            run(ctx, target, gameRoot, file)
                        },
                    ),
                    TextInputItem(
                        id = "linux_tools_run_path",
                        title = "Path to a program",
                        subtitle = "Inside the game's folder",
                        value = typedPath,
                        onChange = { _, text -> typedPath = text.trim() },
                    ),
                    AsyncActionItem(
                        id = "linux_tools_run_typed",
                        title = "Run the program at that path",
                        run = { ctx, _ ->
                            if (typedPath.isEmpty()) "Type the path first" else run(ctx, target, gameRoot, File(typedPath)).also { typedPath = "" }
                        },
                    ),
                ),
            ),
            CatalogGroup(
                id = "linux_tools_danger",
                title = null,
                items = listOf(
                    AsyncActionItem(
                        id = "linux_tools_stop",
                        title = "Stop this game's processes",
                        subtitle = "Ends every program in the container that was started from this game's folder",
                        confirmTitle = "Stop this game's processes?",
                        run = { _, _ -> runtime?.stopLinuxProcesses(gameRoot) ?: "Windows and Linux support isn't loaded" },
                    ),
                    AsyncActionItem(
                        id = "linux_tools_reset",
                        title = "Reset this game's saves and settings",
                        subtitle = if (ownHome) {
                            "Empties the game's own home: its saves and settings are deleted and it starts as new. The game's folder and the container's home are not touched"
                        } else {
                            "Only a game with a home of its own can be reset. Turn that on first; the container's home is never reset here"
                        },
                        confirmTitle = "Reset this game's saves and settings? They are deleted",
                        run = { ctx, _ ->
                            if (!LinuxGameOptionsPrefs.ownHome(ctx, target.entryId)) {
                                "This game uses the container's home, which is not reset here. Turn on its own home first"
                            } else {
                                runtime?.resetLinuxHome(target.entryId) ?: "Windows and Linux support isn't loaded"
                            }
                        },
                    ),
                ),
            ),
        )
    }

    private suspend fun run(context: android.content.Context, target: LinuxToolsScreen.Target, gameRoot: File, program: File): String {
        val runtime = PcGameRuntimeRegistry.runtime ?: return "Windows and Linux support isn't loaded"
        val result = runtime.runLinuxProgram(program, gameRoot, target.entryId)
        return if (result.succeeded) "${program.name} finished" else "Couldn't run ${program.name}: ${result.detail}"
    }
}
