package dev.droidtop.pluginhost

/**
 * How often one job's progress reaches its row (docs/SPEC.md, "Downloads"): a report is published when [intervalMs]
 * has passed since the last one, or at once when it carries a new checkpoint (a pause resumes from the latest one);
 * otherwise it is held as the latest, for a trailing publish to show when the interval is up, so the row always ends
 * on the last thing reported. Thread-safe; [clock] is the time in milliseconds, a test's own.
 */
internal class ProgressThrottle(private val intervalMs: Long, private val clock: () -> Long) {
    data class Report(val percent: Int, val status: String, val checkpoint: String?)

    private var lastAt: Long? = null
    private var lastCheckpoint: String? = null
    private var pending: Report? = null

    /** Whether [report] is published now; when not, it is kept as the one [takePending] gives. */
    @Synchronized
    fun offer(report: Report): Boolean {
        val now = clock()
        val since = lastAt?.let { now - it }
        val newCheckpoint = report.checkpoint != null && report.checkpoint != lastCheckpoint
        if (since == null || since >= intervalMs || newCheckpoint) {
            published(report, now)
            return true
        }
        pending = report
        return false
    }

    /** The report held back since the last publish, now published; null when there is none. */
    @Synchronized
    fun takePending(): Report? {
        val report = pending ?: return null
        published(report, clock())
        return report
    }

    /** How long until a held report is due. */
    @Synchronized
    fun waitMs(): Long = lastAt?.let { (it + intervalMs - clock()).coerceAtLeast(0L) } ?: 0L

    private fun published(report: Report, at: Long) {
        lastAt = at
        report.checkpoint?.let { lastCheckpoint = it }
        pending = null
    }
}
