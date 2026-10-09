package dev.droidtop.app.settings

import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.runtime.windows.WinePrefixTools
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lutris-style tools for a Wine prefix as settings rows (docs/SPEC.md 7c, "Prefix tools"): Wine's own
 * programs, installing Windows components into it, running any program in it, looking inside it, stopping
 * every Wine process and resetting it. Reached from "Prefix tools" in Wine and graphics
 * ([WineOptionsCatalog]), for the prefix [entryId] starts in (null: the shared one, [shared]).
 * [WinePrefixTools] does each one.
 */
object PrefixToolsCatalog {

    /** The path typed into "Path to a program", until it is run. */
    @Volatile private var typedPath: String = ""

    fun screen(entryId: String?, title: String?, shared: Boolean): CatalogScreen = CatalogScreen(
        id = "wine_prefix_tools",
        title = "Prefix tools",
        subtitle = title,
        groups = { context ->
            val components = WinePrefixTools.components(context, entryId).orEmpty()
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
                    id = "wine_prefix_tools_components",
                    title = "Windows components",
                    items = components.map { component ->
                        AsyncActionItem(
                            id = "wine_prefix_component_" + component.id,
                            title = component.name,
                            subtitle = if (component.native) {
                                "Windows' own files are in $where. Press to go back to Wine's own"
                            } else {
                                "Wine's own is in use. Press to install Windows' own files into $where, from droidtop's component catalog (each download checked against its SHA-256)"
                            },
                            value = if (component.native) "Installed" else "Wine's own",
                            run = { ctx, onStatus -> WinePrefixTools.setComponent(ctx, entryId, component.id, !component.native, onStatus) },
                        )
                    }.ifEmpty {
                        listOf(AsyncActionItem(id = "wine_prefix_components_none", title = "No Windows environment yet", run = { _, _ -> WinePrefixTools.NO_ENVIRONMENT }))
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
                    id = "wine_prefix_tools_files",
                    title = null,
                    items = listOf(
                        NestedScreenItem(
                            id = "wine_prefix_files",
                            title = "Look inside the prefix",
                            subtitle = "Its folders and files, read only: nothing here can be changed or deleted",
                            inline = PrefixFolderCatalog.screen(entryId, title),
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
                        AsyncActionItem(
                            id = "wine_prefix_reset",
                            title = "Reset the prefix",
                            subtitle = "Makes $where new. Every program installed in it, its registry and the saves a game keeps inside it are deleted; " +
                                "its settings stay, and the next start sets it up again. Game folders are not touched",
                            confirmTitle = "Reset the prefix? What is installed in it and the saves inside it are deleted",
                            run = { ctx, onStatus -> WinePrefixTools.reset(ctx, entryId, onStatus) },
                        ),
                    ),
                ),
            )
        },
        // Per-prefix rows belong to the screen, not to settings search.
        indexGroups = { _ -> emptyList() },
    )
}
