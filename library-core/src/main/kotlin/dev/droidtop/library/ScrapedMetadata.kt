package dev.droidtop.library

import dev.droidtop.library.consoles.RomDao
import java.io.File

/**
 * Merges previously-scraped `game_metadata` rows into whatever a
 * provider just built out of the filesystem.
 *
 * ONE merge for every provider, deliberately: a scrape writes a row
 * keyed by the entry's own id, and any provider that skips this step
 * shows a freshly-scraped game with no description and no cover on the
 * very next scan. That was the real state of the engine-game and PC
 * providers before the PC/engine scrape existed -- the rows had nowhere
 * to be read back from, so the console provider's private version of
 * this was the only one, and only ROMs could hold metadata at all.
 *
 * Media that ES-DE's own `downloaded_media` layout already resolved
 * ([EsDeArtwork]) wins over the row's stored path: the layout is the
 * live truth on disk, while the path is what the scrape recorded, and
 * the row's path is the fallback for entries whose ids are not files
 * under a games root (a store install, a Wine shortcut) and so have no
 * layout lookup at all.
 */
suspend fun List<LibraryEntry>.withScrapedMetadata(dao: RomDao): List<LibraryEntry> {
    if (isEmpty()) return this
    val metadataById = dao.getGameMetadata(map { it.id }).associateBy { it.id }
    if (metadataById.isEmpty()) return this
    return map { entry ->
        val meta = metadataById[entry.id] ?: return@map entry
        entry.copy(
            artworkUri = entry.artworkUri ?: meta.artworkPath?.takeIf { File(it).isFile },
            videoUri = entry.videoUri ?: meta.videoPath?.takeIf { File(it).isFile },
            description = meta.description,
            developer = meta.developer,
            publisher = meta.publisher,
            genre = meta.genre,
            releaseDate = meta.releaseDate,
            rating = meta.rating,
            players = meta.players,
            favorite = meta.favorite,
            completed = meta.completed,
            kidGame = meta.kidGame,
            hidden = meta.hidden,
            broken = meta.broken,
            noGameCount = meta.noGameCount,
            noMultiScrape = meta.noMultiScrape,
            hideMetadata = meta.hideMetadata,
            controllerShortName = meta.controllerShortName,
            altEmulator = meta.altEmulator,
            launchScreen = meta.launchScreen,
            sortName = meta.sortName,
            collectionSortName = meta.collectionSortName,
        )
    }
}
