package dev.droidtop.library

import android.content.Context
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.consoles.RomScanWalk
import dev.droidtop.library.consoles.resolveSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What a games folder actually contains, reported back to the person who
 * just added it.
 *
 * Onboarding used to add a folder and say nothing at all; whether the
 * folder held a library or a typo was something a person found out after
 * onboarding, in an empty Games tab. ES-DE's own configurator makes this
 * a step ("only an information dialog about missing games" on Android,
 * three concrete repairs on desktop), and droidtop takes the desktop
 * behaviour: the count is reported here and the repair offer keys off it
 * (docs/SPEC.md 7b, "Game folders" and "No games yet").
 *
 * Both halves of droidtop's library are counted, because both are what
 * "games" means here: engine games found by the same walk
 * [GameEngineDetector.scan] uses, and console ROMs inside the
 * per-system folders [dev.droidtop.library.consoles.ConsoleRomProvider]
 * scans. Nothing else runs -- no metadata, no scraping, no database
 * write. This is a count, not a scan whose results are kept.
 */
object GamesRootReport {

    /**
     * [systems] is the ES-DE system ids that had ROMs under this root, in
     * the order they were found: what the person recognises the folder by.
     */
    data class Report(
        val path: String,
        val exists: Boolean,
        val engineGames: Int,
        val romFiles: Int,
        val systems: List<String>,
    ) {
        val total: Int get() = engineGames + romFiles
        val empty: Boolean get() = total == 0
    }

    suspend fun of(context: Context, path: String): Report = withContext(Dispatchers.IO) {
        val root = File(path)
        if (!root.isDirectory) {
            return@withContext Report(path, exists = false, engineGames = 0, romFiles = 0, systems = emptyList())
        }
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        val engineGames = runCatching {
            GameEngineDetector.scan(
                root,
                systemsById,
                EnginesDatabase.defs(context),
                override = { folder -> EngineOverridePrefs.engineFor(context, folder.absolutePath) },
            ).size
        }.getOrDefault(0)

        var roms = 0
        val systems = mutableListOf<String>()
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory && ScanPrune.isScannableFolder(it) }
            .sortedBy { it.name }
            .forEach { folder ->
                val system = resolveSystem(folder.name, systemsById) ?: return@forEach
                val found = runCatching { RomScanWalk.walk(folder, system.extensions).files.size }.getOrDefault(0)
                if (found > 0) {
                    roms += found
                    systems += system.displayName
                }
            }

        Report(path, exists = true, engineGames = engineGames, romFiles = roms, systems = systems)
    }

    /** The one sentence a folder row shows under its path. */
    fun describe(report: Report): String = when {
        !report.exists -> "This folder is not readable right now."
        report.empty -> "No games found here yet."
        else -> buildString {
            append(report.total)
            append(if (report.total == 1) " game" else " games")
            if (report.systems.isNotEmpty()) {
                append(" - ")
                append(report.systems.take(3).joinToString(", "))
                if (report.systems.size > 3) append(" and ${report.systems.size - 3} more")
            }
        }
    }
}
