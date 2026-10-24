package dev.droidtop.pluginhost

/**
 * What the one jobs-summary notification says (docs/SPEC.md 12a "Downloads"). A single-file
 * download is a job like any other (droidtop's own downloader has no notification of its own), so the summary
 * counts every running job: downloads, library scrapes, plugin work jobs (store depots among them), brokered jobs.
 */
object JobsSummary {
    /** Jobs running right now that have no notification of their own: not finished, not paused. */
    fun runningCount(entries: List<PluginJobsCenter.Entry>): Int =
        entries.count { !it.done && !it.paused }

    /**
     * How far the running jobs have got together, in whole steps of [STEP] percent so the notification's bar moves a
     * few times over a download instead of with every report; -1 while none of them knows (an indeterminate bar).
     */
    fun runningPercent(entries: List<PluginJobsCenter.Entry>): Int {
        val known = entries.filter { !it.done && !it.paused && it.percent >= 0 }.map { it.percent.coerceAtMost(100) }
        if (known.isEmpty()) return -1
        return known.average().toInt() / STEP * STEP
    }

    const val STEP = 5

    /** "1 job running", "3 jobs running". */
    fun text(count: Int): String = if (count == 1) "1 job running" else "$count jobs running"
}
