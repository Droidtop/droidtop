package dev.droidtop.app.settings

import dev.droidtop.app.GamesRootPrefs
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.FolderPickItem
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.runtime.windows.PcContainers
import dev.droidtop.runtime.windows.WindowsInstalls
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Install a Windows game" (docs/SPEC.md 7c, "Install a new game"): run a GOG offline installer or any
 * setup program in a new prefix of its own, then add what it installed to the library. The installer is
 * run where it is (picked, or typed as a path); the game goes inside the new prefix unless a folder of the
 * person's is chosen to install into. [WindowsInstalls] does the work. Registered as [SCREEN_ID] so the
 * general "Add a game" flow (Droidtop/tracker#407) can open it by id.
 */
object WindowsInstallCatalog {

    const val SCREEN_ID = "windows_install"

    /** The path typed into "Path to the installer", until it is run. */
    @Volatile private var typedInstaller: String = ""

    /** The folder chosen to install into, or null: inside the new prefix. */
    @Volatile private var target: File? = null

    fun screen(): CatalogScreen = CatalogScreen(
        id = SCREEN_ID,
        title = "Install a Windows game",
        subtitle = "Run an installer in a new Windows prefix, then add the game it installed to your library",
        groups = { context ->
            val started = WindowsInstalls.pending(context)
            val chosen = target
            listOf(
                CatalogGroup(
                    id = "windows_install_start",
                    title = "Install",
                    items = buildList<CatalogItem> {
                        add(
                            FolderPickItem(
                                id = "windows_install_target",
                                title = if (chosen == null) "Install into a folder of mine (optional)" else "Install into: ${chosen.path}",
                                subtitle = "Without one, the game is installed inside the new prefix and none of your folders is written to. " +
                                    "With one, the installer sees that folder as a drive and you choose it there",
                                onPicked = { _, uri ->
                                    val folder = withContext(Dispatchers.IO) { GamesRootPrefs.resolveStoragePath(uri) }
                                    if (folder == null) {
                                        "Couldn't get that folder's real path on this device"
                                    } else {
                                        target = folder
                                        null
                                    }
                                },
                            ),
                        )
                        if (chosen != null) {
                            add(
                                ActionItem(
                                    id = "windows_install_target_clear",
                                    title = "Install inside the new prefix instead",
                                    run = { target = null },
                                ),
                            )
                        }
                        add(
                            DocumentPickItem(
                                id = "windows_install_pick",
                                title = "Choose the installer",
                                subtitle = "A GOG offline installer (setup_*.exe) or any setup program, .exe or .msi. It runs where it is; " +
                                    "nothing is copied or moved, and its folder is shown to the installer as a drive so it finds its data files",
                                mimeType = "*/*",
                                onPicked = { ctx, uri ->
                                    val file = withContext(Dispatchers.IO) { PickedFiles.fileOf(uri) }
                                        ?: return@DocumentPickItem "Couldn't get that file's real path on this device; pick one on this device's storage, or type its path below"
                                    WindowsInstalls.begin(ctx, file, target) {}
                                },
                            ),
                        )
                        add(
                            TextInputItem(
                                id = "windows_install_path",
                                title = "Path to the installer",
                                subtitle = "For a file the picker cannot reach, such as a shared folder",
                                value = typedInstaller,
                                onChange = { _, text -> typedInstaller = text.trim() },
                            ),
                        )
                        add(
                            AsyncActionItem(
                                id = "windows_install_typed",
                                title = "Install from that path",
                                run = { ctx, onStatus ->
                                    if (typedInstaller.isEmpty()) {
                                        "Type the path first"
                                    } else {
                                        WindowsInstalls.begin(ctx, File(typedInstaller), target, onStatus).also { if (it.startsWith("Started")) typedInstaller = "" }
                                    }
                                },
                            ),
                        )
                    },
                ),
                CatalogGroup(
                    id = "windows_install_started",
                    title = "Installs started",
                    items = if (started.isEmpty()) {
                        listOf(ActionItem(id = "windows_install_none", title = "No install is waiting for its game to be added", run = {}))
                    } else {
                        started.map { pending ->
                            NestedScreenItem(
                                id = "windows_install_" + pending.id,
                                title = pending.title,
                                subtitle = "Started from ${File(pending.installer).name}. Add the game when the installer is done",
                                inline = pendingScreen(pending),
                            )
                        }
                    },
                ),
            )
        },
        // Per-install rows belong to the screen, not to settings search.
        indexGroups = { _ -> emptyList() },
    )

    private fun pendingScreen(pending: WindowsInstalls.Pending): CatalogScreen = CatalogScreen(
        id = "windows_install_" + pending.id,
        title = pending.title,
        subtitle = "Installed in its own prefix",
        groups = { context ->
            val folders = WindowsInstalls.candidates(context, pending.id)
            listOf(
                CatalogGroup(
                    id = "windows_install_add",
                    title = "Add the installed game to the library",
                    items = if (folders.isEmpty()) {
                        listOf(
                            ActionItem(
                                id = "windows_install_nothing",
                                title = "Nothing new found yet",
                                subtitle = "Let the installer finish, then open this screen again. Its folders appear here",
                                run = {},
                            ),
                        )
                    } else {
                        folders.map { folder ->
                            AsyncActionItem(
                                id = "windows_install_folder_" + folder.name,
                                title = folder.name,
                                subtitle = folder.path,
                                run = { ctx, _ -> WindowsInstalls.addGame(ctx, pending.id, folder) },
                            )
                        }
                    },
                ),
                CatalogGroup(
                    id = "windows_install_more",
                    title = null,
                    items = listOf(
                        AsyncActionItem(
                            id = "windows_install_again",
                            title = "Run the installer again",
                            run = { ctx, _ -> WindowsInstalls.runAgain(ctx, pending.id) },
                        ),
                        NestedScreenItem(
                            id = "windows_install_tools",
                            title = "Prefix tools",
                            subtitle = "Wine configuration, the registry editor, a command prompt, Windows components and a look at the files of this prefix",
                            inline = PrefixToolsCatalog.screen(PcContainers.ofContainer(pending.id), pending.title, shared = false),
                        ),
                        AsyncActionItem(
                            id = "windows_install_discard",
                            title = "Throw this install away",
                            subtitle = "Deletes the new prefix and everything installed in it. The installer and your folders are not touched",
                            confirmTitle = "Throw this install away?",
                            run = { ctx, _ -> WindowsInstalls.discard(ctx, pending.id) },
                        ),
                    ),
                ),
            )
        },
        indexGroups = { _ -> emptyList() },
    )
}
