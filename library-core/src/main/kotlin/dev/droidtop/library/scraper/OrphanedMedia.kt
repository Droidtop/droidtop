package dev.droidtop.library.scraper

import android.content.Context
import dev.droidtop.library.GamesRoots
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.consoles.RomDatabase
import dev.droidtop.library.consoles.SystemOverridePrefs
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds scraped media and metadata rows whose game is gone (real ES-DE
 * has the same housekeeping). Deleting or renaming a ROM leaves its
 * artwork behind forever: the media lives under `downloaded_media` keyed
 * by filename, and nothing ever revisits it. On a device where a
 * library gets reorganised a few times that is quietly hundreds of
 * megabytes of images for games that no longer exist, plus metadata
 * rows that keep a deleted game in collections and counts.
 *
 * Reporting and deleting are separate calls on purpose: this removes
 * real files, so nothing here runs without the user having seen the
 * count first.
 */
object OrphanedMedia {

    data class Report(val files: List<File>, val metadataIds: List<String>, val bytes: Long) {
        val isEmpty: Boolean get() = files.isEmpty() && metadataIds.isEmpty()

        fun describe(): String = when {
            isEmpty -> "Nothing orphaned: every scraped file and metadata row still has its game."
            else -> {
                val mb = bytes / (1024.0 * 1024.0)
                "${files.size} media files (${"%.1f".format(mb)} MB) and ${metadataIds.size} metadata rows " +
                    "belong to games that are no longer here."
            }
        }
    }

    /**
     * Every media file and metadata row with no game behind it.
     *
     * A media file counts as orphaned only when its SYSTEM folder still
     * exists and the game does not: if a whole system folder is
     * temporarily missing (an SD card not mounted yet, a folder being
     * moved), its media is not garbage, and deleting it would be the
     * worst possible reading of a transient state.
     */
    suspend fun find(context: Context): Report = withContext(Dispatchers.IO) {
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        val roots = GamesRoots.current(context)
        val orphanFiles = mutableListOf<File>()
        var bytes = 0L

        roots.forEach { root ->
            val mediaRoots = listOf(
                File(root.parentFile ?: root, "ES-DE/downloaded_media"),
                File(root, "downloaded_media"),
            ).filter { it.isDirectory }

            mediaRoots.forEach { mediaRoot ->
                (mediaRoot.listFiles() ?: emptyArray()).filter { it.isDirectory }.forEach { systemDir ->
                    val systemId = systemDir.name
                    val system = systemsById[systemId]
                    // The folder holding this system's games, resolved the
                    // same way the scan resolves it.
                    val gameFolders = (root.listFiles() ?: emptyArray()).filter { folder ->
                        folder.isDirectory &&
                            SystemOverridePrefs.resolveForFolder(
                                context,
                                folder.absolutePath,
                                folder.name,
                                systemsById,
                            )?.id == systemId
                    }
                    // No folder for this system at all: the games may
                    // simply not be mounted. Leave it alone.
                    if (gameFolders.isEmpty()) return@forEach
                    val extensions = system?.extensions.orEmpty()
                    val liveBaseNames = gameFolders
                        .flatMap { folder -> folder.walkTopDown().filter { it.isFile }.toList() }
                        .filter { extensions.isEmpty() || it.extension.lowercase() in extensions }
                        .map { it.nameWithoutExtension }
                        .toHashSet()

                    (systemDir.listFiles() ?: emptyArray()).filter { it.isDirectory }.forEach { mediaTypeDir ->
                        (mediaTypeDir.listFiles() ?: emptyArray()).filter { it.isFile }.forEach { media ->
                            if (media.nameWithoutExtension !in liveBaseNames) {
                                orphanFiles += media
                                bytes += media.length()
                            }
                        }
                    }
                }
            }
        }

        // Metadata rows are keyed by absolute ROM path, so "the file is
        // gone" is the whole test -- but only for paths under a root
        // that currently exists, for the same unmounted-card reason.
        val dao = RomDatabase.get(context).romDao()
        val liveRootPaths = roots.filter { it.isDirectory }.map { it.absolutePath }
        val orphanedMetadata = dao.getAllGameMetadataIds()
            .filter { id -> liveRootPaths.any { id.startsWith(it) } && !File(id).isFile }

        Report(orphanFiles, orphanedMetadata, bytes)
    }

    /** Deletes what [find] reported. Returns what actually went. */
    suspend fun clean(context: Context, report: Report): String = withContext(Dispatchers.IO) {
        if (report.isEmpty) return@withContext report.describe()
        var deleted = 0
        report.files.forEach { if (runCatching { it.delete() }.getOrDefault(false)) deleted++ }
        val dao = RomDatabase.get(context).romDao()
        report.metadataIds.chunked(400).forEach { chunk -> dao.deleteGameMetadata(chunk) }
        "Removed $deleted media files and ${report.metadataIds.size} metadata rows."
    }
}
