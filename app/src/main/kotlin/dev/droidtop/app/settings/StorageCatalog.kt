package dev.droidtop.app.settings

import android.content.Context
import android.text.format.Formatter
import dev.droidtop.library.FolderSizes
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.PcSource
import dev.droidtop.library.freeSpaceLine
import dev.droidtop.library.friendlyLocation
import dev.droidtop.library.installVolumes
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.LibraryPaths
import dev.droidtop.library.settings.PathChange
import dev.droidtop.library.stores.StoreChanges
import dev.droidtop.library.stores.StoreGame
import dev.droidtop.library.stores.StoreLibraries
import dev.droidtop.library.stores.StoreLibrary
import dev.droidtop.library.userFacingErrorMessage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A store's installed games, the biggest first; a game with no size the store knows sorts last by title. */
internal fun installedBySize(games: List<StoreGame>): List<StoreGame> =
    games.filter { it.installed }.sortedWith(compareByDescending<StoreGame> { it.sizeBytes }.thenBy { it.title.lowercase() })

/** One PC game a folder holds (not a store's install), as a Storage row: its id, title and folder. */
internal data class FolderGameRow(val id: String, val title: String, val path: String, val hidden: Boolean)

/**
 * The PC games of [entries] that live in a folder of their own and no store
 * installed (a store's installs are listed with their store), one per
 * folder, in title order so a row never moves when its size arrives. Pure.
 */
internal fun folderGameRows(entries: List<LibraryEntry>): List<FolderGameRow> =
    entries.asSequence()
        .filter { !it.missing && it.kind != LibraryEntryKind.CONSOLE_ROM }
        .filter { PcSource.storeIdOf(it.pcInfo?.storeId ?: it.id) == null }
        .mapNotNull { entry ->
            val path = entry.pcInfo?.installPath?.takeIf { it.startsWith("/") } ?: entry.id.takeIf { it.startsWith("/") }
            path?.let { FolderGameRow(entry.id, entry.title, it, entry.hidden) }
        }
        .distinctBy { it.path }
        .sortedBy { it.title.lowercase() }
        .toList()

/** "N hidden games · 4.2 GB": every hidden game as one row, never one row each. */
internal fun hiddenRowTitle(count: Int): String = "$count hidden ${if (count == 1) "game" else "games"}"

/**
 * The Storage page of Game sources (docs/SPEC.md 7j "Places",
 * Droidtop/tracker#227 and #397 slice E): the room each game folder has,
 * then Retro (each console system's games, from the library's index, drawn
 * at once and never moved), then PC: each store's installed games biggest
 * first with the size the store recorded and an Uninstall through the
 * store's own service, then the games that are folders of their own, whose
 * sizes are measured in the background ([FolderSizes]) and fill in row by
 * row. A measured folder is kept by its stamp, so leaving and coming back
 * restarts nothing; leaving stops the measuring. Hidden games are one row.
 * Nothing on this page is read on the main thread.
 */
internal object StorageCatalog {
    const val SCREEN_ID = "stores_storage"

    /** Bumped as folder sizes arrive, at most a few times a second: the page is built again from what is measured. */
    private val sizesChanged = MutableStateFlow(0)
    private val measureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var measuring: Job? = null

    fun screen() = CatalogScreen(
        id = SCREEN_ID,
        title = "Storage",
        subtitle = "What your games take, and how much room is left",
        groups = { context -> groups(context) },
        // The search index must not read every store's tables for rows that are only data.
        indexGroups = { emptyList() },
        live = sizesChanged,
        onLeave = {
            measuring?.cancel()
            measuring = null
            started = false
        },
    )

    /** What the measuring found, by folder path: what the rows read, so building the page asks the disk nothing per row. */
    private val measured = java.util.concurrent.ConcurrentHashMap<String, FolderSizes.Size>()

    /** Whether this visit of the page started its measuring; leaving clears it. */
    @Volatile
    private var started = false

    /**
     * Measures [paths] one after another, off the main thread, once per visit
     * of the page. A folder measured before and unchanged since costs one
     * `stat` ([FolderSizes]), so a row that was finished stays finished.
     */
    private fun measureInBackground(paths: List<String>) {
        if (started || paths.isEmpty()) return
        started = true
        measuring = measureScope.launch {
            var shownAt = 0L
            for (path in paths) {
                if (!isActive) return@launch
                val found = FolderSizes.measure(path) { !isActive } ?: continue
                if (measured.size >= 4096) measured.clear()
                if (measured.put(path, found) == found) continue
                val now = System.currentTimeMillis()
                if (now - shownAt >= SHOW_EVERY_MS) {
                    shownAt = now
                    sizesChanged.value++
                }
            }
            sizesChanged.value++
        }
    }

    private const val SHOW_EVERY_MS = 400L

    private suspend fun groups(context: Context): List<CatalogGroup> = withContext(Dispatchers.IO) {
        val size = { bytes: Long -> Formatter.formatFileSize(context, bytes) }
        val volumes = installVolumes(context)
        val library = dev.droidtop.app.LibraryCore.library(context)
        val roms = runCatching { library.currentEntriesOf(setOf(LibraryEntryKind.CONSOLE_ROM)) }.getOrDefault(emptyList())
        val pcEntries = runCatching { library.currentEntriesOf(LibraryKinds.PC_GAMES) }.getOrDefault(emptyList())
        val hiddenIds = pcEntries.filter { it.hidden }.mapTo(HashSet()) { it.id }
        val perStore = StoreLibraries.all().map { store ->
            store to installedBySize(runCatching { store.games(context) }.getOrDefault(emptyList()))
        }.filter { (_, games) -> games.isNotEmpty() }
        val folderRows = folderGameRows(pcEntries)

        val space = if (volumes.isEmpty()) {
            listOf(
                ActionItem(
                    id = "storage_no_folder",
                    title = "No game folder yet",
                    subtitle = "Store games install into a game folder. Add one under Game sources > Folders",
                    run = {},
                ),
            )
        } else {
            volumes.map { volume ->
                ActionItem(
                    id = "storage_volume_${volume.path}",
                    title = volume.name,
                    subtitle = volume.path,
                    value = freeSpaceLine(volume, size),
                    run = {},
                )
            }
        }

        // Retro first: what the index already holds, so it is drawn at once and never moves.
        val systems = runCatching {
            dev.droidtop.library.consoles.ConsoleSystemsRepository.allSystems(context).associate { it.id to it.displayName }
        }.getOrDefault(emptyMap())
        val retro = roms.filter { !it.missing && !it.hidden }.groupingBy { it.systemId.orEmpty() }.eachCount()
            .entries.sortedBy { (id, _) -> (systems[id] ?: id).lowercase() }
            .map { (id, count) ->
                ActionItem(id = "storage_retro_$id", title = systems[id] ?: id.ifEmpty { "Other" }, value = gamesWord(count), run = {})
            }

        // PC: each store's installs, then the folders of their own, sizes as they are measured.
        val storeGroups = perStore.mapNotNull { (store, installed) ->
            val shown = installed.filterNot { it.key in hiddenIds }
            if (shown.isEmpty()) return@mapNotNull null
            CatalogGroup(
                id = "storage_${store.id}",
                title = "${store.label}, ${size(shown.sumOf { it.sizeBytes })}",
                items = shown.map { game -> uninstallRow(store, game, size) },
            )
        }
        measureInBackground(folderRows.map { it.path })
        val folderItems: List<CatalogItem> = folderRows.filterNot { it.hidden }.map { row ->
            ActionItem(
                id = "storage_folder_${row.id}",
                title = row.title,
                subtitle = friendlyLocation(row.path),
                value = measured[row.path]?.let { FolderSizes.words(it, size) } ?: "Measuring…",
                run = {},
            )
        }
        // Hidden games, store installs and folders alike, are one row.
        val hiddenStore = perStore.flatMap { (_, games) -> games.filter { it.key in hiddenIds } }
        val hiddenFolders = folderRows.filter { it.hidden }
        val hiddenRow = (hiddenStore.size + hiddenFolders.size).takeIf { it > 0 }?.let { count ->
            val folderSizes = hiddenFolders.map { measured[it.path] }
            val bytes = hiddenStore.sumOf { it.sizeBytes } + folderSizes.sumOf { it?.bytes ?: 0L }
            val partial = folderSizes.any { it == null || it.atLeast }
            ActionItem(
                id = "storage_hidden",
                title = hiddenRowTitle(count),
                subtitle = "Shown when PC Games' Filter selects Hidden",
                value = FolderSizes.words(FolderSizes.Size(bytes, partial), size),
                run = {},
            )
        }
        val pc = storeGroups + listOfNotNull(
            CatalogGroup("storage_folders", "Game folders", folderItems).takeIf { folderItems.isNotEmpty() },
            hiddenRow?.let { CatalogGroup("storage_hidden_group", null, listOf(it)) },
        )
        listOfNotNull(
            CatalogGroup("storage_space", "Room left", space),
            CatalogGroup("storage_retro", "Retro", retro).takeIf { retro.isNotEmpty() },
        ) + pc.ifEmpty {
            listOf(CatalogGroup("storage_none", "PC", listOf(ActionItem("storage_nothing", "No PC games installed yet", run = {}))))
        }
    }

    private fun uninstallRow(store: StoreLibrary, game: StoreGame, size: (Long) -> String): AsyncActionItem {
        val where = game.installPath?.let { friendlyLocation(it) } ?: "its folder"
        return AsyncActionItem(
            id = "storage_${store.id}_${game.gameId}",
            title = game.title,
            subtitle = "Uninstall to free ${size(game.sizeBytes)} on $where. ${store.label} still lists the game",
            value = size(game.sizeBytes),
            confirmTitle = "Uninstall ${game.title}? Deletes its files from $where",
            run = { ctx, _ ->
                store.uninstall(ctx, game.gameId).fold(
                    onSuccess = {
                        // The files are gone, so exactly that folder leaves the library; nothing is walked.
                        game.installPath?.let { path -> LibraryPaths.report(ctx, PathChange.removed(File(path))) }
                        StoreChanges.announce(ctx)
                        "Removed ${game.title}"
                    },
                    onFailure = { "Could not remove it: ${userFacingErrorMessage(it)}" },
                )
            },
        )
    }
}
