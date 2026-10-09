package dev.droidtop.app.settings

import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.runtime.windows.WinePrefixTools
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lutris-style tools for a Wine prefix as settings rows (docs/SPEC.md 7c, "Prefix tools"): Wine's own
 * programs, running any program in it, and stopping every Wine process. Reached from "Prefix tools" in
 * Wine and graphics ([WineOptionsCatalog]), for the prefix [entryId] starts in (null: the shared one,
 * [shared]). [WinePrefixTools] starts each one.
 */
object PrefixToolsCatalog {

    /** The path typed into "Path to a program", until it is run. */
    @Volatile private var typedPath: String = ""

    fun screen(entryId: String?, title: String?, shared: Boolean): CatalogScreen = CatalogScreen(
        id = "wine_prefix_tools",
        title = "Prefix tools",
        subtitle = title,
        groups = { _ ->
            val where = if (shared) "the shared prefix, which every game without a prefix of its own runs in" else "this prefix"
            listOf(
                CatalogGroup(
                    id = "wine_prefix_tools_programs",
                    title = "Wine's own programs",
                    items = WinePrefixTools.Program.entries.map { program ->
                        AsyncActionItem(
                            id = "wine_prefix_tool_" + program.name.lowercase(),
                            title = program.title,
                            subtitle = when (program) {
                                WinePrefixTools.Program.CONFIGURATION -> "Windows version, libraries, drives and audio, in $where"
                                WinePrefixTools.Program.REGISTRY_EDITOR -> "Look at and change the registry of $where"
                                WinePrefixTools.Program.COMMAND_PROMPT -> "A Windows command prompt inside $where"
                            },
                            run = { ctx, _ -> WinePrefixTools.open(ctx, entryId, program) },
                        )
                    },
                ),
                CatalogGroup(
                    id = "wine_prefix_tools_run",
                    title = "Run a program in this prefix",
                    items = listOf(
                        DocumentPickItem(
                            id = "wine_prefix_run_pick",
                            title = "Choose a program",
                            subtitle = "An .exe, .msi, .bat or .cmd on this device. It runs where it is; nothing is copied",
                            mimeType = "*/*",
                            onPicked = { ctx, uri ->
                                val file = withContext(Dispatchers.IO) { PickedFiles.fileOf(uri) }
                                    ?: return@DocumentPickItem "Couldn't get that file's real path on this device; pick one on this device's storage, or type its path below"
                                WinePrefixTools.run(ctx, entryId, file)
                            },
                        ),
                        TextInputItem(
                            id = "wine_prefix_run_path",
                            title = "Path to a program",
                            subtitle = "For a file the picker cannot reach, such as a shared folder",
                            value = typedPath,
                            onChange = { _, text -> typedPath = text.trim() },
                        ),
                        AsyncActionItem(
                            id = "wine_prefix_run_typed",
                            title = "Run the program at that path",
                            run = { ctx, _ ->
                                if (typedPath.isEmpty()) {
                                    "Type the path first"
                                } else {
                                    WinePrefixTools.run(ctx, entryId, File(typedPath)).also { if (it.startsWith("Started")) typedPath = "" }
                                }
                            },
                        ),
                    ),
                ),
                CatalogGroup(
                    id = "wine_prefix_tools_stop",
                    title = null,
                    items = listOf(
                        AsyncActionItem(
                            id = "wine_prefix_kill",
                            title = "Stop every Wine process",
                            subtitle = "For a program that hung, or a game left running in the background. Ends every Windows program that is running, in any prefix",
                            confirmTitle = "Stop every Wine process?",
                            run = { _, _ -> WinePrefixTools.killAll() },
                        ),
                    ),
                ),
            )
        },
        // Per-prefix rows belong to the screen, not to settings search.
        indexGroups = { _ -> emptyList() },
    )
}
