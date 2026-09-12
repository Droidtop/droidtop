package dev.droidtop.library

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

    /** What a log line says when this budget cut a folder short. */
    fun reason(): String =
        "reading this folder itself ran past its ${budgetMs} ms budget, so nothing below it was read"

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
 * 1434 identical lines are not.
 *
 * Pure string formatting, deliberately — the callers pass it to
 * `android.util.Log`, so the format is testable on the JVM without a
 * logging framework in the way.
 */
object ScanLog {

    /** The tag every scan summary goes out under. */
    const val TAG = "droidtop.ScanLog"

    /**
     * One folder's or one root's summary: what it found, what it did not
     * read and why, and how long it took.
     *
     * [skippedByReason] is counts keyed by the reason
     * [ScanPrune.skipReason] (or the ROM walk's own rules) gave, so the
     * line names the rule that fired rather than the folders it fired on.
     * [note] carries the one thing a count cannot say — that a budget cut
     * this folder short, and which folder it stopped at.
     */
    fun summary(
        label: String,
        games: Int,
        skippedByReason: Map<String, Int>,
        durationMs: Long,
        note: String? = null,
    ): String {
        val skipped = skippedByReason.values.sum()
        return buildString {
            append(label)
            append(": ")
            append(games)
            append(if (games == 1) " game, " else " games, ")
            append(skipped)
            append(if (skipped == 1) " folder skipped" else " folders skipped")
            if (skippedByReason.isNotEmpty()) {
                append(" (")
                append(
                    skippedByReason.entries
                        .sortedByDescending { it.value }
                        .joinToString("; ") { (reason, count) -> "$count x $reason" },
                )
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

    /** [skipped] as counts by reason, for [summary]. */
    fun countByReason(skipped: List<Pair<File, String>>): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for ((_, reason) in skipped) counts[reason] = (counts[reason] ?: 0) + 1
        return counts
    }
}
