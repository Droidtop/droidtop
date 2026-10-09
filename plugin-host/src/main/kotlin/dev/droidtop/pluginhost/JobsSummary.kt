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

    /** "1 job running", "3 jobs running". */
    fun text(count: Int): String = if (count == 1) "1 job running" else "$count jobs running"
}
