package dev.droidtop.app.settings

import android.content.Context
import android.net.Uri
import dev.droidtop.app.GamesRootPrefs
import dev.droidtop.library.GamesRoots
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.PcSource
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.FolderPickItem
import dev.droidtop.library.settings.GamingSettingsCatalog
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
    const val SCREEN_ID = "game_sources_folders_screen"

    fun screen() = CatalogScreen(
        id = SCREEN_ID,
        title = "Folders",
        subtitle = "Your game folders, what droidtop found in each, and a rescan of them",
        groups = { context -> groups(context) },
        // The search index must not read the library to list a few rows.
        indexGroups = { emptyList() },
    )

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

    /** A root row's hint: where it is, and what selecting it does. */
    fun rootLine(root: RootCount): String = "${root.path}. Select to stop looking here"

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
                    ActionItem(
                        id = "folders_root_${root.path}",
                        title = File(root.path).name.ifBlank { root.path },
                        subtitle = rootLine(root),
                        value = rootValue(root, now),
                        confirmTitle = "Stop looking in ${root.path}? Its games leave the library; adding it back restores them",
                        run = { ctx -> GamesRootPrefs.removeGamesRoot(ctx, root.path) },
                    )
                }.ifEmpty {
                    listOf(ActionItem(id = "folders_none", title = "No game folders yet", subtitle = "Add the folder your games are in", run = {}))
                } + TextBlockItem(id = "folders_note", text = "New games appear after Rescan or a store sync."),
            ),
            CatalogGroup(
                id = "folders_actions",
                title = null,
                items = listOf(
                    GamingSettingsCatalog.rescanPcFoldersItem(),
                    FolderPickItem(
                        id = "folders_add",
                        title = "Add a folder",
                        subtitle = "An SD card, a second internal folder, anywhere games live",
                        onPicked = { ctx, uri: Uri ->
                            val resolved = GamesRootPrefs.resolveStoragePath(uri)
                            if (resolved != null) {
                                GamesRootPrefs.addGamesRoot(ctx, resolved)
                                null
                            } else {
                                "Couldn't resolve that folder to a real path on this device. Not added"
                            }
                        },
                    ),
                    ActionItem(
                        // The Gaming shell opens PC Games on the Wine shortcuts (PcSource.LIBRARY_ITEM_PREFIX).
                        id = "${PcSource.LIBRARY_ITEM_PREFIX}${PcSource.WineShortcut.id}",
                        title = PcSource.WineShortcut.label(),
                        subtitle = "Shortcuts made by hand in a Wine prefix. PC Games lists them",
                        value = gamesWord(counts.shortcuts),
                        run = {},
                    ),
                ),
            ),
        )
    }
}
