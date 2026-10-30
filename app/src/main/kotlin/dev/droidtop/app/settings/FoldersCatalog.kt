package dev.droidtop.app.settings

import android.content.Context
import android.net.Uri
import dev.droidtop.app.GamesRootPrefs
import dev.droidtop.library.GamesRoots
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.PcSource
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.FolderPickItem
import dev.droidtop.library.settings.GamingSettingsCatalog
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.TextBlockItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One game folder as Game sources > Folders shows it. */
internal data class RootCount(val path: String, val games: Int, val available: Boolean, val scannedAt: Long?)

/** The person's game folders with the PC and engine games under each, and the Wine shortcuts beside them. */
internal data class FolderCounts(val roots: List<RootCount>, val shortcuts: Int) {
    val games: Int get() = roots.sumOf { it.games }
}

/**
 * Game sources > Folders (docs/SPEC.md 7j "Places", Droidtop/tracker#397
 * slice E): the game folders droidtop looks in for PC and engine games, each
 * with how many it found there and when it last looked, or "Not available"
 * when the folder cannot be read (an SD card that is out: its games are kept,
 * never marked gone). Adding and removing a folder are the same settings
 * Settings > Game folders has ([GamesRootPrefs]); removing one keeps what the
 * library knows of its games, by their ids, so adding it back restores them.
 * "Rescan PC game folders" walks these for PC and engine games only. New games
 * appear after a Rescan or a store sync: there is no folder watcher (a rescan
 * of a card can take minutes, docs/SPEC.md 7g).
 */
internal object FoldersCatalog {
    /**
     * The one game folders screen (Droidtop/tracker#589): Settings > Library > Game folders, Game sources > Folders,
     * the empty libraries' Add a folder and the Launcher's games grid all open this id. It was two screens over the
     * same `GamesRootPrefs` list, each with its own add and rescan rows.
     */
    const val SCREEN_ID = "rom_folders"

    fun screen() = CatalogScreen(
        id = SCREEN_ID,
        title = "Game folders",
        subtitle = "Where droidtop looks for games. Console games go in a folder per system (snes, psx) inside",
        groups = { context -> groups(context) },
        // The search index must not read the library: it gets the rows that never change.
        indexGroups = { listOf(actionsGroup(shortcuts = null), byPathGroup()) },
    )

    // The typed path, between the field and the Add action.
    @Volatile
    private var pendingPath = ""

    /**
     * The one "Add a folder" row (Game sources > Folders and Settings > Game folders): Android's folder picker, the
     * folder stored as a game folder, and the row says what happened ("Added Games"), since the folder shows up in the
     * list only after the picker has gone (Droidtop/tracker#537).
     */
    fun addFolderItem(id: String) = FolderPickItem(
        id = id,
        title = "Add a folder",
        subtitle = "An SD card, a second internal folder, anywhere games live",
        onPicked = { ctx, uri: Uri ->
            val resolved = GamesRootPrefs.resolveStoragePath(uri)
            if (resolved != null) {
                GamesRootPrefs.addGamesRoot(ctx, resolved)
                "Added " + folderName(resolved.path)
            } else {
                "Couldn't resolve that folder to a real path on this device. Not added"
            }
        },
    )

    /** A folder's name for a list: its last segment, or the whole path for a root. */
    fun folderName(path: String): String = File(path).name.ifBlank { path }

    /** "3 folders · 412 games": the Folders row's value in Game sources. */
    fun summary(counts: FolderCounts): String {
        val folders = counts.roots.size
        if (folders == 0) return "No folders yet"
        return "$folders ${if (folders == 1) "folder" else "folders"} · ${gamesWord(counts.games)}"
    }

    /**
     * Each configured game folder with the PC and engine games the library
     * holds under it (the most specific folder when one is inside another,
     * [PcSource.of]), whether it can be read, and when the PC walk last
     * finished it ([GamesRoots.pcScannedAt]). The library's own index, read
     * once; one `stat` and listing per folder for "Not available". Off the
     * main thread.
     */
    suspend fun counts(context: Context): FolderCounts = withContext(Dispatchers.IO) {
        val roots = GamesRootPrefs.gamesRootPaths(context).sorted()
        val entries = runCatching {
            dev.droidtop.app.LibraryCore.library(context).currentEntriesOf(LibraryKinds.PC_GAMES)
        }.getOrDefault(emptyList()).filterNot { it.missing }
        val sources = entries.mapNotNull { PcSource.of(it, roots) }
        val byRoot = sources.mapNotNull { (it as? PcSource.Folder)?.rootId }.groupingBy { it }.eachCount()
        val scanned = runCatching { GamesRoots.pcScannedAt(context) }.getOrDefault(emptyMap())
        FolderCounts(
            roots = roots.map { RootCount(it, byRoot[it] ?: 0, GamesRoots.isAvailable(File(it)), scanned[it]) },
            shortcuts = sources.count { it == PcSource.WineShortcut },
        )
    }

    /**
     * A root row's value: its games and when the walk last finished it, or that it cannot be read now. The scan time
     * is a fact the row shows, not a tooltip: the Gaming shell draws a row's subtitle only as its hint (rig, build
     * 1715: "Last scanned" never showed on the folder's row).
     */
    fun rootValue(root: RootCount, now: Long): String {
        if (!root.available) return "Not available"
        val scanned = root.scannedAt?.let { "scanned " + agoWords(now, it) } ?: "not scanned yet"
        return "${gamesWord(root.games)} · $scanned"
    }

    /**
     * One folder's own page: what was found there and when, a rescan, and Remove as a named row behind a question.
     * A folder row used to remove the folder on A, with nothing on the row saying so (Droidtop/tracker#589), so two
     * presses could drop a 2,000-game folder.
     */
    private fun folderScreen(root: RootCount, now: Long) = CatalogScreen(
        id = "folders_root_screen_${root.path}",
        title = folderName(root.path),
        subtitle = root.path,
        groups = {
            listOf(
                CatalogGroup(
                    id = "folders_root_facts_${root.path}",
                    title = null,
                    items = listOf(
                        TextBlockItem(id = "folders_root_found_${root.path}", text = rootValue(root, now).replaceFirstChar { it.uppercase() }),
                        GamingSettingsCatalog.rescanPcFoldersItem(),
                        ActionItem(
                            id = "folders_root_remove_${root.path}",
                            title = "Remove this folder",
                            subtitle = "Its games leave the library until you add it back. Nothing on the device is deleted",
                            confirmTitle = "Stop looking in ${root.path}?",
                            run = { ctx -> GamesRootPrefs.removeGamesRoot(ctx, root.path) },
                        ),
                    ),
                ),
            )
        },
        indexGroups = { emptyList() },
    )

    /** "5 min ago", in the coarsest honest unit, as the store rows say "Synced 5 min ago". */
    fun agoWords(now: Long, then: Long): String = syncedAgo(now, then).removePrefix("Synced ")

    private suspend fun groups(context: Context): List<CatalogGroup> {
        val counts = counts(context)
        val now = System.currentTimeMillis()
        return listOf(
            CatalogGroup(
                id = "folders_roots",
                title = null,
                items = counts.roots.map { root ->
                    NestedScreenItem(
                        id = "folders_root_${root.path}",
                        title = folderName(root.path),
                        subtitle = root.path,
                        inline = folderScreen(root, now),
                        valueLabel = { rootValue(root, now) },
                        icon = dev.droidtop.library.settings.CatalogIcon.GAME_FOLDERS,
                    )
                }.ifEmpty {
                    listOf(ActionItem(id = "folders_none", title = "No game folders yet", subtitle = "Add the folder your games are in"))
                } + TextBlockItem(id = "folders_note", text = "New games appear after Rescan or a store sync."),
            ),
            actionsGroup(counts.shortcuts),
            byPathGroup(),
        )
    }

    /**
     * Add a folder, the two rescans (the whole library, and the PC walk alone), and the Wine shortcuts. [shortcuts]
     * is null for the search index, which must not read the library, so the shortcuts row is left out there.
     */
    private fun actionsGroup(shortcuts: Int?) = CatalogGroup(
        id = "folders_actions",
        title = null,
        items = listOfNotNull(
            addFolderItem("folders_add"),
            GamingSettingsCatalog.rescanLibraryItem(),
            GamingSettingsCatalog.rescanPcFoldersItem(),
            shortcuts?.let { count ->
                ActionItem(
                    // The Gaming shell opens PC Games on the Wine shortcuts (PcSource.LIBRARY_ITEM_PREFIX).
                    id = "${PcSource.LIBRARY_ITEM_PREFIX}${PcSource.WineShortcut.id}",
                    title = PcSource.WineShortcut.label(),
                    subtitle = "Shortcuts made by hand in a Wine prefix. PC Games lists them",
                    value = gamesWord(count),
                )
            },
        ),
    )

    /** A folder the picker cannot offer, typed: an emulator's host share, a rooted device's own mount, a USB drive. */
    private fun byPathGroup() = CatalogGroup(
        id = "folders_add_by_path",
        title = "Add a folder by path",
        items = listOf(
            TextInputItem(
                id = "folders_path",
                title = "Folder path",
                subtitle = "The picker only offers what Android calls a storage volume. An emulator's host share " +
                    "(BlueStacks' /mnt/windows/BstSharedFolder), a mount a rooted device adds itself, or a USB drive " +
                    "under /mnt is none of those, and is typed here instead",
                value = pendingPath,
                onChange = { _, value -> pendingPath = value },
            ),
            AsyncActionItem(
                id = "folders_path_add",
                title = "Add this folder",
                subtitle = "Checked for real before it is stored: it must exist, be a folder, and be readable",
                run = { ctx, _ ->
                    val typed = pendingPath
                    val error = withContext(Dispatchers.IO) { GamesRootPrefs.addGamesRootByPath(ctx, typed) }
                    if (error == null) {
                        pendingPath = ""
                        "Added " + typed.trim()
                    } else {
                        error
                    }
                },
            ),
        ),
    )
}
