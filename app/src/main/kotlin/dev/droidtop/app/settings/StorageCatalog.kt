package dev.droidtop.app.settings

import android.content.Context
import android.text.format.Formatter
import dev.droidtop.library.freeSpaceLine
import dev.droidtop.library.friendlyLocation
import dev.droidtop.library.installVolumes
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.LibraryPaths
import dev.droidtop.library.settings.PathChange
import dev.droidtop.library.stores.StoreChanges
import dev.droidtop.library.stores.StoreGame
import dev.droidtop.library.stores.StoreLibraries
import dev.droidtop.library.stores.StoreLibrary
import dev.droidtop.library.userFacingErrorMessage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A store's installed games, the biggest first; a game with no size the store knows sorts last by title. */
internal fun installedBySize(games: List<StoreGame>): List<StoreGame> =
    games.filter { it.installed }.sortedWith(compareByDescending<StoreGame> { it.sizeBytes }.thenBy { it.title.lowercase() })

/**
 * The Storage page of the Stores place (docs/SPEC.md 7j "Places",
 * Droidtop/tracker#227): the room each game folder has, then for each store
 * the games it installed, biggest first, each with an Uninstall that goes
 * through the store's own service (the one the game page uses), so freeing
 * space is one step from where the person sees what takes it. The sizes are
 * the stores' own copy of what they installed (no walk, no network); free
 * space is one stat per game folder, read off the main thread like the rest.
 */
internal object StorageCatalog {
    const val SCREEN_ID = "stores_storage"

    fun screen() = CatalogScreen(
        id = SCREEN_ID,
        title = "Storage",
        subtitle = "What installed store games take, and how much room is left",
        groups = { context -> groups(context) },
        // The search index must not read every store's tables for rows that are only data.
        indexGroups = { emptyList() },
    )

    private suspend fun groups(context: Context): List<CatalogGroup> = withContext(Dispatchers.IO) {
        val size = { bytes: Long -> Formatter.formatFileSize(context, bytes) }
        val volumes = installVolumes(context)
        val perStore = StoreLibraries.all().map { store ->
            store to installedBySize(runCatching { store.games(context) }.getOrDefault(emptyList()))
        }.filter { (_, games) -> games.isNotEmpty() }

        val space = if (volumes.isEmpty()) {
            listOf(
                ActionItem(
                    id = "storage_no_folder",
                    title = "No game folder yet",
                    subtitle = "Store games install into a game folder. Add one under Settings > Game folders",
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
        val total = perStore.sumOf { (_, games) -> games.sumOf { it.sizeBytes } }
        val games = if (perStore.isEmpty()) {
            listOf(CatalogGroup("storage_none", "Installed games", listOf(ActionItem("storage_nothing", "Nothing installed from a store yet", run = {}))))
        } else {
            perStore.map { (store, installed) ->
                CatalogGroup(
                    id = "storage_${store.id}",
                    title = "${store.label}, ${size(installed.sumOf { it.sizeBytes })}",
                    items = installed.map { game -> uninstallRow(store, game, size) },
                )
            }
        }
        listOf(
            CatalogGroup("storage_space", "Room left", space),
            CatalogGroup(
                "storage_total",
                null,
                listOf(ActionItem("storage_total_row", "Installed store games", value = size(total), run = {})),
            ).takeIf { perStore.isNotEmpty() },
        ).filterNotNull() + games
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
