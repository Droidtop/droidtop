package dev.droidtop.library.integrations

import dev.droidtop.library.settings.JobLook
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.library.settings.Notices
import dev.droidtop.pluginhost.JobState
import dev.droidtop.pluginhost.PluginJobsCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A download ending says so in one short flash with its mark (docs/SPEC.md "Motion and visual status",
 * Droidtop/tracker#643: "Toast or banner cards that slide in and out, with an icon or glyph and one short line. Details
 * on press, through facets in place"; "several downloads finishing become one card that counts up, not a flood"). A
 * finish is "Hades is ready" with a check; a failure "Hades did not download" with a cross, never the error itself,
 * which is one press away on the job's page. Several finishing while the first card is still up become "3 downloads
 * finished", and a press then shows Downloads. Only downloads: a scrape or a sync ending is no news. Jobs restored
 * already finished, from before the process started, are not announced ([newlyEnded]). Never written to Android's toast: a flash with
 * no droidtop surface on screen waits for the next one.
 */
object JobNotices {
    private const val DONE_GROUP = "jobs_done"
    private const val FAILED_GROUP = "jobs_failed"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        val sinceMs = System.currentTimeMillis()
        scope.launch {
            val announced = HashSet<String>()
            PluginJobsCenter.entries().collect { entries ->
                val finished = entries.filter { it.done && PluginJobsCenter.isDownloadKind(it.nativeKind) }
                newlyEnded(announced, finished, sinceMs).forEach { entry ->
                    announced += entry.jobId
                    announce(entry)
                }
            }
        }
    }

    /**
     * The jobs in [finished] not yet announced ([known]) that ended at or after [sinceMs]: a job restored already
     * finished, from an earlier run, ended before it and is no news, however late the restored list arrives. Pure.
     */
    internal fun newlyEnded(known: Set<String>, finished: List<PluginJobsCenter.Entry>, sinceMs: Long): List<PluginJobsCenter.Entry> =
        finished.filter { it.jobId !in known && (it.finishedAtMs ?: 0L) >= sinceMs }

    /** What a flash says for [entry] ending. Pure. */
    internal fun line(entry: PluginJobsCenter.Entry): String =
        if (entry.state == JobState.FAILED) "${entry.title} did not download" else "${entry.title} is ready"

    /** What a flash says for [count] downloads ending the same way. Pure. */
    internal fun groupLine(failed: Boolean, count: Int): String =
        if (failed) "$count downloads did not finish" else "$count downloads finished"

    private fun announce(entry: PluginJobsCenter.Entry) {
        val failed = entry.state == JobState.FAILED
        Notices.flash(
            line(entry),
            tone = if (failed) NoticeTone.Caution else NoticeTone.Info,
            mark = if (failed) JobLook.FAILED else JobLook.DONE,
            group = if (failed) FAILED_GROUP else DONE_GROUP,
            groupLine = { count -> groupLine(failed, count) },
            opens = { PluginJobsScreen.pageOf(entry.jobId, entry.title) },
            groupOpens = { PluginJobsScreen.screen() },
        )
    }
}
