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

    /**
     * How far the report has got, for the row that is waiting on it.
     *
     * The rig made this necessary rather than nice: on the user's own
     * library (150 games on a host share) the folder row sat on a motionless
     * "Looking at this folder." for over six minutes. A count that takes
     * minutes has to say it is moving, and the walk is already folder by
     * folder ([GameEngineDetector.topLevelFolders]), so the progress is
     * real work done rather than an animation.
     */
    data class Progress(val foldersDone: Int, val foldersTotal: Int, val gamesSoFar: Int)

    /** What the folder row shows while [of] is still running. */
    fun describe(progress: Progress?): String = when {
        progress == null || progress.foldersTotal == 0 -> "Looking at this folder."
        else -> "Looking at this folder - ${progress.foldersDone} of ${progress.foldersTotal} folders, " +
            "${progress.gamesSoFar} " + (if (progress.gamesSoFar == 1) "game" else "games") + " so far."
    }

    suspend fun of(
        context: Context,
        path: String,
        onProgress: (Progress) -> Unit = {},
    ): Report = withContext(Dispatchers.IO) {
        // The walk runs on IO; the caller's progress state does not
        // belong to this thread, so every report crosses back to Main.
        suspend fun report(progress: Progress) = withContext(Dispatchers.Main) { onProgress(progress) }
        val root = File(path)
        if (!root.isDirectory) {
            return@withContext Report(path, exists = false, engineGames = 0, romFiles = 0, systems = emptyList())
        }
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        val engineGames = runCatching {
            // Folder by folder, with the same per-folder budget the real
            // library scan uses, so the count the user is shown and the
            // library they get afterwards cannot disagree.
            val defs = EnginesDatabase.defs(context)
            val top = GameEngineDetector.topLevelFolders(root, systemsById)
            var found = 0
            report(Progress(0, top.folders.size, 0))
            top.folders.forEachIndexed { index, folder ->
                found += GameEngineDetector.scanFolder(
                    folder,
                    systemsById,
                    defs,
                    override = { f -> EngineOverridePrefs.engineFor(context, f.absolutePath) },
                    budget = { ScanBudget.start() },
                ).games.size
                report(Progress(index + 1, top.folders.size, found))
            }
            found
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
