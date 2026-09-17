package dev.droidtop.library

import android.content.Context
import java.io.File

/**
 * A time budget for ONE folder of a library scan.
 *
 * The rig showed why this replaces the whole-provider timeout it used to
 * sit beside (build 523): one slow subtree under a games root
 * (`Steam/steamapps/workshop`, 1434 directories) ran past
 * [dev.droidtop.library.Library]'s 60-second provider timeout, and the
 * provider's *entire* result set was discarded — `ConsoleRomProvider timed
 * out scanning`, 11 games in the library. A budget that throws away
 * everything it did not finish is worse than no budget: the scan is slow
 * AND empty.
 *
 * So the budget is per folder and cooperative, which are the two
 * properties that make it honest:
 *
 * - **Per folder, and per folder's OWN work.** Each folder gets its own
 *   budget covering its own listing and detection -- not its whole
 *   subtree. The rig proved why that distinction decides whether games
 *   appear: with a subtree budget, `adult/` (eleven engine folders,
 *   hundreds of games, every individual step fast) surfaced ONE game
 *   before its 20 seconds ran out. Bigness is not pathology. What a
 *   budget must catch is a single operation that does not come back -- a
 *   directory with 18,126 entries on a slow share, a corrupted entry that
 *   hung `ls` itself -- and that is one folder's own step. A folder whose
 *   own step runs over is skipped with a reason; its siblings, its parent
 *   and every other root are untouched, and a large healthy library is
 *   never truncated for being large.
 * - **Cooperative.** The walks check [expired] between folder steps rather
 *   than being cancelled from outside. A coroutine timeout cannot preempt
 *   one long blocking `listFiles()` already in progress (a real case on
 *   this device: a corrupted directory entry that hung `ls` itself), so a
 *   budget enforced only from outside can stop *waiting* but cannot stop
 *   *walking*. Checking it inside the walk is what actually bounds the
 *   work.
 *
 * A budget of zero or less never expires, which is what a caller that
 * genuinely wants the whole walk (the explicit "Rescan now" of a single
 * folder, a test) passes.
 */
class ScanBudget private constructor(
    private val budgetMs: Long,
    private val clock: () -> Long,
) {
    private val startedAt: Long = clock()

    /** Milliseconds since this budget started. */
    val elapsedMs: Long get() = clock() - startedAt

    /** Whether the walk must stop descending now. */
    val expired: Boolean get() = budgetMs > 0 && elapsedMs >= budgetMs

    /**
     * What a log line says when this budget cut a folder's own step
     * short. Deliberately says nothing about what the walk does next:
     * the ROM walk stops at such a directory (reading it again would cost
     * the same again), while the engine walk drops only this folder's own
     * evidence and still walks its children, each under a budget of its
     * own -- see [dev.droidtop.library.GameEngineDetector]'s walk.
     */
    fun reason(): String =
        "reading this folder's own contents ran past its ${budgetMs} ms budget"

    companion object {
        /**
         * Default per-folder budget. Chosen against measured reality
         * rather than taste: the rig's own `roms/` system folders read in
         * well under a second each over the host share, the largest real
         * ROM folder anybody has measured here (a `j2me` directory with
         * 18,126 files) took single-digit seconds, and the tree this
         * budget exists to bound is thousands of directories deep. Twenty
         * seconds is therefore far above every folder that legitimately
         * finishes and far below "the user is staring at a spinner".
         */
        const val DEFAULT_FOLDER_BUDGET_MS = 20_000L

        fun start(
            budgetMs: Long = DEFAULT_FOLDER_BUDGET_MS,
            clock: () -> Long = { System.currentTimeMillis() },
        ): ScanBudget = ScanBudget(budgetMs, clock)

        /** A budget that never expires — the whole walk, however long. */
        fun unlimited(): ScanBudget = ScanBudget(0L, { 0L })
    }
}


/**
 * What one walk did NOT read, by reason, with the folders each reason
 * fired on.
 *
 * Counts alone were the whole of this until 2026-09-16, deliberately:
 * the rig's logcat had several hundred `Not listing games in ...` lines
 * and the one line that mattered was buried, so [ScanLog] prints one line
 * per folder and per root with counts by reason instead. The rig then
 * showed what a bare count cannot do: `engine folder .../Ubisoft: 0 games,
 * 2 folders skipped (2 x it is a console system folder, scanned for ROMs
 * instead)` gives a person no way to tell WHICH two folders were passed
 * over, and the games missing from that root could not be traced from the
 * log at all. So the folders are kept beside the count and the line names
 * them, capped at [MAX_NAMED] per reason so one rule firing on a thousand
 * hidden folders still costs one short line.
 *
 * Names are printed relative to the folder the line is about, because
 * "Far Cry 5/data_final/pc" is the fact a person needs and "pc" is not.
 */
class ScanSkips {

    private val byReason = LinkedHashMap<String, MutableList<File>>()

    /** Records that [folder] was not read, because of [reason]. */
    fun add(folder: File, reason: String) {
        byReason.getOrPut(reason) { mutableListOf() }.add(folder)
    }

    /** Folds another walk's skips into this one, reason by reason. */
    fun addAll(other: ScanSkips) {
        for ((reason, folders) in other.byReason) byReason.getOrPut(reason) { mutableListOf() }.addAll(folders)
    }

    /** How many folders were skipped in total. */
    val total: Int get() = byReason.values.sumOf { it.size }

    val isEmpty: Boolean get() = byReason.isEmpty()

    /** Counts by reason -- what a caller that only wants the numbers asks for. */
    fun counts(): Map<String, Int> = byReason.mapValues { it.value.size }

    /** The folders one reason fired on, in the order they were met. */
    fun folders(reason: String): List<File> = byReason[reason].orEmpty().toList()

    /**
     * `2 x it is a console system folder, ... (Far Cry 5/data_final/pc,
     * Far Cry New Dawn/data_final/pc)`, reasons in descending count order.
     * [base] is the folder the summary line is about; a folder outside it
     * keeps its absolute path.
     */
    fun describe(base: File?): String =
        byReason.entries
            .sortedByDescending { it.value.size }
            .joinToString("; ") { (reason, folders) ->
                val shown = folders.take(MAX_NAMED).joinToString(", ") { relativeTo(base, it) }
                val more = folders.size - MAX_NAMED
                val names = if (more > 0) "$shown, +$more more" else shown
                "${folders.size} x $reason ($names)"
            }

    private fun relativeTo(base: File?, folder: File): String {
        val basePath = base?.absolutePath ?: return folder.absolutePath
        val path = folder.absolutePath
        return if (path.startsWith(basePath + File.separator)) {
            path.substring(basePath.length + 1)
        } else {
            path
        }
    }

    companion object {
        /** How many folders one reason names before the line says "+N more". */
        const val MAX_NAMED = 6

        /** Every skip of [skipped], as this type -- for walks that collect pairs. */
        fun of(skipped: List<Pair<File, String>>): ScanSkips =
            ScanSkips().apply { for ((folder, reason) in skipped) add(folder, reason) }
    }
}

/**
 * The one shape of a library-scan log line: one line per folder and one
 * per root, with counts, never one line per skipped directory.
 *
 * The rig's logcat is why this exists as a type instead of a `Log.i` call
 * at the point of the skip: scanning the user's whole `G:\games` printed
 * `Not listing games in …: it is a hidden folder` several hundred times
 * and `it holds add-on content` hundreds more, which buried the one line
 * that mattered (the provider giving up) and made the log useless for
 * seeing what the scan actually did. Counts by reason say the same thing
 * in one line and say it better, because a count is the fact a person
 * wants: "1434 skipped, all of them Steam's own tree" is information;
 * 1434 identical lines are not. The count alone was not enough either,
 * for the opposite reason: build 540's `2 x it is a console system
 * folder` never said WHICH two folders a root's missing games were behind
 * (INBOX, 2026-09-16). Each reason now names the folders it fired on, up
 * to [ScanSkips.MAX_NAMED] of them, so the line stays one line either
 * way.
 *
 * Pure string formatting, deliberately — the callers pass it to
 * `android.util.Log`, so the format is testable on the JVM without a
 * logging framework in the way.
 */
object ScanLog {

    /** The tag every scan summary goes out under. */
    const val TAG = "droidtop.ScanLog"

    /**
     * Where droidtop keeps its own copy of this log, under the app's
     * external files directory, and why there is one at all.
     *
     * A rig session on 2026-09-16 (build 539) walked a whole library and
     * could not find a single `droidtop.ScanLog` line in logcat
     * afterwards, which made every scan defect in that session
     * undiagnosable: "found nothing", "never ran" and "ran and its lines
     * were evicted from a 40-minute logcat ring buffer shared with the
     * vendored backbone's own logging" are three different bugs that look
     * identical when the log is gone. logcat is not droidtop's to
     * guarantee -- its buffer size, its rotation and its filters belong to
     * the device -- so the scan log is written twice: to logcat, where it
     * has always gone, and to a file droidtop owns, which a rig reads with
     *
     *     adb shell cat /sdcard/Android/data/dev.droidtop.app/files/logs/scan.log
     *
     * [install] also writes one line per process start, so "is droidtop
     * logging at all" is one grep rather than an inference from silence.
     */
    private const val LOG_DIR = "logs"
    private const val LOG_NAME = "scan.log"

    /** Rotated at a quarter megabyte: two files, never unbounded growth. */
    private const val MAX_LOG_BYTES = 256L * 1024L

    @Volatile
    private var logFile: File? = null

    private val fileLock = Any()

    /**
     * Points the scan log at this app's own files and records that the
     * process has started. Called once, from the Application: the log
     * belongs to the shared core, not to a mode.
     */
    fun install(context: Context) {
        val base = runCatching { context.getExternalFilesDir(null) }.getOrNull() ?: context.filesDir
        val dir = File(base, LOG_DIR)
        runCatching { dir.mkdirs() }
        logFile = File(dir, LOG_NAME)
        // The version NAME carries the build number ("0.1.0-dev-539"),
        // so one line says which build produced everything under it.
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown build"
        write("droidtop $version started; this log is also at ${logFile?.absolutePath}")
    }

    /**
     * One scan line, to logcat and to droidtop's own copy of the log.
     * Every caller goes through here, so there is one place a line is
     * emitted and one format (see [summary]).
     */
    fun write(line: String) {
        android.util.Log.i(TAG, line)
        val file = logFile ?: return
        synchronized(fileLock) {
            runCatching {
                if (file.length() > MAX_LOG_BYTES) {
                    val previous = File(file.parentFile, "$LOG_NAME.1")
                    previous.delete()
                    file.renameTo(previous)
                }
                file.appendText(timestamp() + " " + line + System.lineSeparator())
            }
        }
    }

    /** [summary]'s line, emitted. */
    fun write(
        label: String,
        games: Int,
        skipped: ScanSkips,
        durationMs: Long,
        note: String? = null,
        base: File? = null,
    ) = write(summary(label, games, skipped, durationMs, note, base))

    private fun timestamp(): String =
        java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())

    /**
     * One folder's or one root's summary: what it found, what it did not
     * read and why, and how long it took.
     *
     * [skipped] names the rule that fired AND the folders it fired on
     * ([ScanSkips]), capped so the line stays one line: a count alone
     * ("2 x it is a console system folder") left the rig unable to say
     * which two folders a root's missing games were behind. [base] is the
     * folder the names are printed relative to -- the folder or root this
     * line is about. [note] carries the one thing a count cannot say --
     * that a budget cut this folder short, and which folder it stopped at.
     */
    fun summary(
        label: String,
        games: Int,
        skipped: ScanSkips,
        durationMs: Long,
        note: String? = null,
        base: File? = null,
    ): String = buildString {
        append(label)
        append(": ")
        append(games)
        append(if (games == 1) " game, " else " games, ")
        append(skipped.total)
        append(if (skipped.total == 1) " folder skipped" else " folders skipped")
        if (!skipped.isEmpty) {
            append(" (")
            append(skipped.describe(base))
            append(")")
        }
        append(", ")
        append(durationMs)
        append(" ms")
        if (note != null) {
            append(" -- ")
            append(note)
        }
    }

}
