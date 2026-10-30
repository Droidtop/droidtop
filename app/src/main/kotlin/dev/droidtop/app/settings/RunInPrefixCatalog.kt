package dev.droidtop.app.settings

import android.content.Context
import dev.droidtop.library.LinuxToolsScreen
import dev.droidtop.library.ManageScreens
import dev.droidtop.library.PcFolderClassifier
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.runtime.windows.WinePrefixTools
import dev.droidtop.runtime.windows.WinePrefixes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Run a program in a Wine prefix" (docs/SPEC.md 7i, "Manage"; Droidtop/tracker#636), offered on every kind
 * of game: pick a program, the game's own folder first, then the prefix it runs attached to (the game's own,
 * the shared one, or another on this device). It runs where it is, through [WinePrefixTools.runIn], and may
 * write the game's folder: the case it is for is MGQ Paradox's translation updater, run under Wine beside an
 * engine game Enginehost plays. Opened from the game menu by [ManageScreens.RUN_IN_PREFIX].
 */
object RunInPrefixCatalog {

    /** The file "Choose another file" picked, until the screen is left for another game. */
    @Volatile private var picked: Pair<String, File>? = null

    fun screen(): CatalogScreen = CatalogScreen(
        id = ManageScreens.RUN_IN_PREFIX,
        title = TITLE,
        groups = { _ -> listOf(CatalogGroup("run_in_prefix_none", null, listOf(ActionItem(id = "run_in_prefix_from_game", title = "Open this from a game's options")))) },
        indexGroups = { _ -> emptyList() },
        forDeepLink = { argument ->
            val target = LinuxToolsScreen.parse(argument)
            CatalogScreen(
                id = ManageScreens.RUN_IN_PREFIX,
                title = TITLE,
                subtitle = target.title,
                groups = { context -> groups(context, target) },
                indexGroups = { _ -> emptyList() },
            )
        },
    )

    private suspend fun groups(context: Context, target: LinuxToolsScreen.Target): List<CatalogGroup> {
        val gameRoot = File(target.gameRoot)
        val programs = withContext(Dispatchers.IO) {
            if (gameRoot.isDirectory) {
                // Breadth first, so the programs beside the game come before the ones in its subfolders.
                PcFolderClassifier.read(gameRoot).filter { WinePrefixTools.isProgram(File(it.name)) }.map { it.path }
            } else {
                emptyList()
            }
        }
        val other = picked?.takeIf { it.first == target.entryId }?.second
        val items = buildList<CatalogItem> {
            programs.forEach { path -> add(programItem("run_in_prefix_game:$path", path, File(gameRoot, path), target)) }
            other?.let { add(programItem("run_in_prefix_picked", it.name, it, target)) }
            add(
                DocumentPickItem(
                    id = "run_in_prefix_pick",
                    title = "Choose another file",
                    subtitle = "An .exe, .msi, .bat or .cmd anywhere on this device. It runs in place; nothing is copied",
                    mimeType = "*/*",
                    // The game's own folder first, when it is on the shared storage the picker can open in.
                    startIn = target.gameRoot.removePrefix(SHARED_STORAGE).takeIf { it != target.gameRoot && it.isNotEmpty() },
                    onPicked = { _, uri ->
                        val file = withContext(Dispatchers.IO) { PickedFiles.fileOf(uri) }
                            ?: return@DocumentPickItem "Couldn't get that file's real path on this device; pick one on this device's storage"
                        if (!WinePrefixTools.isProgram(file)) return@DocumentPickItem "${file.name} is not a program Wine can run (.exe, .msi, .bat or .cmd)"
                        picked = target.entryId to file
                        "Added ${file.name} above; choose it to pick the prefix"
                    },
                ),
            )
        }
        return listOf(
            CatalogGroup(
                id = "run_in_prefix_programs",
                title = if (programs.isEmpty()) "No program in ${gameRoot.name}" else "Programs in ${gameRoot.name}",
                items = items,
            ),
        )
    }

    /** One program's row: it opens the prefix choice for [file]. */
    private fun programItem(id: String, title: String, file: File, target: LinuxToolsScreen.Target): CatalogItem =
        NestedScreenItem(
            id = id,
            title = title,
            subtitle = "Choose the prefix it runs in",
            inline = CatalogScreen(
                id = "run_in_prefix_where",
                title = "Run ${file.name} in",
                subtitle = target.title,
                groups = { context -> prefixGroups(context, target.entryId, file) },
                indexGroups = { _ -> emptyList() },
            ),
        )

    private suspend fun prefixGroups(context: Context, entryId: String, file: File): List<CatalogGroup> {
        val choices = withContext(Dispatchers.IO) { WinePrefixes.choicesFor(context, entryId) }
        val items = choices.map { choice ->
            AsyncActionItem(
                id = "run_in_prefix_with:${choice.id}",
                title = when (choice.role) {
                    WinePrefixes.Role.OWN -> "This game's own prefix"
                    WinePrefixes.Role.SHARED -> "The shared prefix"
                    WinePrefixes.Role.OTHER -> choice.name
                },
                subtitle = when (choice.role) {
                    WinePrefixes.Role.OWN -> "The one it was given when it chose its own Wine build"
                    WinePrefixes.Role.SHARED -> "The one every game without a prefix of its own runs in"
                    WinePrefixes.Role.OTHER -> "Another prefix on this device"
                },
                run = { ctx, _ ->
                    val result = WinePrefixes.open(ctx, choice.id, file)
                    if (result.succeeded) "Started ${file.name}" else "Couldn't start ${file.name}: ${result.detail}"
                },
            )
        }
        return listOf(
            CatalogGroup(
                id = "run_in_prefix_where",
                title = if (items.isEmpty()) null else "It may change files in its own folder",
                items = items.ifEmpty {
                    listOf(AsyncActionItem(id = "run_in_prefix_no_prefix", title = "No Windows environment yet", run = { _, _ -> WinePrefixTools.NO_ENVIRONMENT }))
                },
            ),
        )
    }

    private const val TITLE = "Run a program in a Wine prefix"
    private const val SHARED_STORAGE = "/storage/emulated/0/"
}
