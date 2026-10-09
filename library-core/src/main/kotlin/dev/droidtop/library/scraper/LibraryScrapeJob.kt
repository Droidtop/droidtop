package dev.droidtop.library.scraper

import android.content.Context
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.consoles.SystemFolders
import dev.droidtop.pluginhost.PluginJobsCenter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The library scrape as a job (docs/SPEC.md 12a "Jobs", 7h; Droidtop/tracker#174).
 * The Games options menu, the per-system Settings row and the one-time offer
 * all start it through [start], so there is one place it runs, one entry in
 * "Downloads and installs" with Pause, Resume and Cancel, and one rate limit:
 * every request still goes through [ScreenScraperClient]'s pacing, so a
 * keyless run is as slow as the anonymous tier allows and says so by taking its
 * time, not by failing.
 *
 * The checkpoint is the position in the scrape queue: the system folder being
 * scraped and the last ROM of it that was finished ([Position]). The queue is
 * the library's system folders in their usual order, so a restarted process
 * rebuilds it and carries on from the folder, and from the ROM after the
 * checkpoint inside it ([scrapeSystemArtwork]'s `resumeAfter`). Pause lands
 * between two games, never inside one.
 *
 * PC and engine games are the last item of the same queue ([PC_ITEM], docs/SPEC.md 7h
 * "The whole library"), scraped by [PcScraper] with the same filter and the same checkpoint
 * rule: the entries are sorted by id and the checkpoint is the last id finished. A run of
 * everything is the console folders and then that item; "PC and engine games" alone is that
 * item only.
 */
object LibraryScrapeJob {
    const val KIND = "library_scrape"
    private const val ARG_SYSTEM = "system"
    private const val ARG_FOLDER = "folder"
    private const val ARG_PC_ONLY = "pc_only"

    /** The queue item that stands for every PC and engine game; its checkpoint is the last entry id finished. */
    internal const val PC_ITEM = "pc:library"
    private const val PC_NAME = "PC and engine games"

    /**
     * Where the job reads the library's games from: the library's own index (a walk only when it
     * has none yet), set by :app next to [register]. The job runs without a screen, so it cannot
     * be handed a list.
     */
    @Volatile
    var libraryGames: suspend (Context) -> List<LibraryEntry> = { emptyList() }

    /** Where a scrape stood: [folder] is the queue item, [afterRom] its last finished ROM ("" when none). */
    internal data class Position(val folder: String, val afterRom: String) {
        fun encode(): String = JSONObject().put("folder", folder).put("rom", afterRom).toString()

        companion object {
            fun decode(raw: String?): Position? = raw?.let {
                runCatching { JSONObject(it).let { o -> Position(o.getString("folder"), o.optString("rom")) } }.getOrNull()
            }
        }
    }

    /** What one queue item came to; [stopQueue] ends the whole run (the source refused everything it was asked). */
    internal class StepResult(val line: String, val stopQueue: Boolean = false)

    /** Registers the runner with the one jobs registry. Called once at process start. */
    fun register(context: Context) {
        val appContext = context.applicationContext
        PluginJobsCenter.registerNative(KIND) { args, checkpoint, report -> execute(appContext, args, checkpoint, report) }
    }

    /**
     * Starts (or finds, if already running or paused) the scrape of every system folder, of one
     * system ([systemId]), or of one folder of it ([folder]). [onFinished] gets the summary
     * sentence when this process sees the job end.
     */
    fun start(
        context: Context,
        title: String,
        systemId: String? = null,
        folder: File? = null,
        // Only the PC and engine games of the whole library, not the console folders.
        pcOnly: Boolean = false,
        onFinished: (String) -> Unit = {},
    ): String? = PluginJobsCenter.startNative(
        context = context,
        kind = KIND,
        title = title,
        args = buildMap {
            systemId?.let { put(ARG_SYSTEM, it) }
            folder?.let { put(ARG_FOLDER, it.absolutePath) }
            if (pcOnly) put(ARG_PC_ONLY, "1")
        },
        onComplete = { result -> onFinished(if (result.ok) result.values["summary"] ?: "Done" else (result.error ?: "Failed")) },
    )

    private suspend fun execute(
        context: Context,
        args: Map<String, String>,
        checkpoint: String?,
        report: (percent: Int, statusLine: String, checkpoint: String?) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val systemId = args[ARG_SYSTEM]
        val folderArg = args[ARG_FOLDER]
        val pcOnly = args[ARG_PC_ONLY] == "1"
        // Everything (no system named) covers the PC and engine games too; one system or folder does not.
        val wantConsoles = !pcOnly
        val wantPc = pcOnly || systemId == null
        // A source that cannot be asked (a key that is not set) says so, with the fix,
        // before anything is walked: never a count of systems "scraped" by nothing.
        val romProblem = if (wantConsoles) ScraperReadiness.romSourceProblem(context) else null
        val pcProblem = if (wantPc) ScraperReadiness.pcSourceProblem(context) else null
        val notes = mutableListOf<String>()
        if (romProblem != null && pcProblem != null) return@withContext "Nothing was scraped\n$romProblem\n$pcProblem"
        if (romProblem != null) {
            if (!wantPc) return@withContext "Nothing was scraped\n$romProblem"
            notes += "Console games were not scraped. $romProblem"
        }
        if (pcProblem != null) {
            if (!wantConsoles) return@withContext "Nothing was scraped\n$pcProblem"
            notes += "PC and engine games were not scraped. $pcProblem"
        }
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        val queue: List<Pair<File, dev.droidtop.library.consoles.ConsoleSystemDef>> = when {
            !wantConsoles || romProblem != null -> emptyList()
            // A folder the person has pinned to a system is scraped as asked, wherever it sits.
            folderArg != null && systemId != null -> systemsById[systemId]?.let { listOf(File(folderArg) to it) }.orEmpty()
            systemId != null -> SystemFolders.all(context, systemsById).filter { it.second.id == systemId }
            else -> SystemFolders.all(context, systemsById)
        }
        val withPc = wantPc && pcProblem == null
        if (queue.isEmpty() && !withPc) {
            return@withContext if (systemId == null) "No game folders to scrape." else "No folder for ${systemsById[systemId]?.displayName ?: systemId} in any games root."
        }
        var consolesStopped = false
        val result = runQueue(
            items = queue.map { (folder, system) -> folder.absolutePath to system.displayName } +
                if (withPc) listOf(PC_ITEM to PC_NAME) else emptyList(),
            from = Position.decode(checkpoint),
            step = { index, resumeAfter, romDone, progress ->
                if (index >= queue.size) {
                    val games = libraryGames(context).filter { it.isPcOrEngineGame && !it.missing }.sortedBy { it.id }
                    StepResult(
                        PcScraper.scrape(context, games, resumeAfter = resumeAfter, onEntryDone = romDone, onProgress = progress),
                    )
                } else if (consolesStopped) {
                    // The console source refused everything; the later systems would be refused the same way.
                    StepResult("")
                } else {
                    val (folder, system) = queue[index]
                    var refusedEverything = false
                    val line = scrapeSystemArtwork(
                        context, folder, system,
                        onRefusedEverything = { refusedEverything = true },
                        resumeAfter = resumeAfter?.let(::File),
                        onRomDone = { romDone(it.absolutePath) },
                        onProgress = progress,
                    )
                    // Every further system would be refused the same way; one system's refusal is its own line.
                    // The PC and engine games use another source, so they are still asked.
                    if (refusedEverything && queue.size > 1) {
                        if (withPc) consolesStopped = true
                        StepResult(line, stopQueue = !withPc)
                    } else {
                        StepResult(line)
                    }
                }
            },
            report = report,
        )
        if (notes.isEmpty()) result else notes.joinToString("\n") + "\n" + result
    }

    /**
     * Walks [items] (folder path and display name) from [from], or from the top when [from] is
     * null or names a folder no longer in the queue, reporting the checkpoint as it goes. Pure
     * of Android so the checkpoint and resume rules are tested directly. Throws
     * CancellationException at the next item or ROM boundary after a pause or cancel.
     */
    internal suspend fun runQueue(
        items: List<Pair<String, String>>,
        from: Position?,
        step: suspend (
            index: Int,
            resumeAfter: String?,
            romDone: (String) -> Unit,
            progress: (done: Int, total: Int) -> Unit,
        ) -> StepResult,
        report: (percent: Int, statusLine: String, checkpoint: String?) -> Unit,
    ): String {
        val total = items.size
        val startIndex = from?.let { p -> items.indexOfFirst { it.first == p.folder } }?.takeIf { it >= 0 } ?: 0
        val lines = mutableListOf<String>()
        for (index in startIndex until total) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val (folder, name) = items[index]
            val resumeAfter = if (index == startIndex && from != null && items[index].first == from.folder) from.afterRom.takeIf { it.isNotEmpty() } else null
            val label = "[${index + 1}/$total] $name"
            fun percent(done: Int, of: Int) = ((index * 100L + if (of > 0) done * 100L / of else 0L) / total).toInt()
            report(percent(0, 0), label, Position(folder, resumeAfter.orEmpty()).encode())
            val result = step(
                index,
                resumeAfter,
                { rom -> report(percent(0, 0), label, Position(folder, rom).encode()) },
                { done, of -> report(percent(done, of), "$label: $done/$of", null) },
            )
            if (result.stopQueue) return "Stopped at system ${index + 1} of $total\n${result.line}"
            if (result.line.isNotBlank()) lines += result.line
        }
        return summarize(lines, hasPc = items.any { it.first == PC_ITEM })
    }

    /**
     * The one result text of a run. A system or the PC games that had nothing to do under the scrape
     * filter say so in one line naming them, instead of a sentence each: a run over eighteen systems
     * with two to scrape was a wall of text a gamepad could not scroll (Droidtop/tracker#374).
     */
    internal fun summarize(lines: List<String>, hasPc: Boolean): String {
        val said = lines.filter { it.isNotBlank() }
        if (said.size == 1) return said.single()
        val (idle, active) = said.partition { isIdle(it) }
        val heading = if (hasPc) "Scraped ${said.size - 1} systems and the PC and engine games" else "Scraped ${said.size} systems"
        return buildString {
            append(heading)
            active.forEach { append('\n').append(it) }
            if (idle.isNotEmpty()) {
                append("\nNothing to scrape under the scrape filter: ")
                append(idle.joinToString(", ") { it.substringBefore(':') })
                append('.')
            }
        }
    }

    /** A step line that only says the filter left nothing to do; see [summarize]. */
    private fun isIdle(line: String): Boolean =
        line.contains(": nothing matches the \"") && line.endsWith("scrape filter.") || line.endsWith(": nothing was left to scrape.") || line.endsWith(": none in the library.")
}
