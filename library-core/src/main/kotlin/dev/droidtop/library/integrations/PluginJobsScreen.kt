package dev.droidtop.library.integrations

import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.pluginhost.PluginJobsCenter
import java.util.concurrent.ConcurrentHashMap

/**
 * The one shared "what plugin jobs are running" screen (docs/SPEC.md 12a
 * "Jobs": "a shared jobs surface... e.g. a Jobs screen plus a progress
 * row in context, reused by acquire_content downloads so there's one
 * mechanism"). Reads [PluginJobsCenter], which every job -- a Get-games
 * download, an app_status action, a [dev.droidtop.pluginhost.PluginEvent]
 * reacting on its own -- is tracked through regardless of where it was
 * started, so this screen shows all of them, not just ones started from
 * itself.
 *
 * Laid out as Steam's downloads page (docs/SPEC.md 7j "Places", Droidtop/
 * tracker#363 slice 9): the jobs fall into Current, Paused and Completed,
 * each headed with its count; a job is ONE row with its name, a progress
 * bar, how far it has got and roughly how long is left ([JobEta]), and what
 * its status line says (a download's size); A opens the job's own page with
 * Pause or Resume and Cancel. The screen is live ([CatalogScreen.live]): it is
 * read again whenever a job moves, so nothing on it polls.
 *
 * Registered once (in `:app`'s `AppSettingsCatalogs`) and reached from
 * the "Downloads and installs" entry of Settings and, in the Gaming
 * shell, from the left menu's place of the same name (docs/SPEC.md 7j
 * "Places") -- same shared-catalog registry every other cross-module
 * management screen uses, so every renderer gets it for free. A store's
 * page lists its own installs with the same rows ([jobRow]).
 */
object PluginJobsScreen {
    const val ID = "plugin_jobs"

    /**
     * The one place the list of "what is running" is reached; every store's installs are jobs in
     * it. [headed] false is for a host that already names the screen in its own header (the Quick
     * Menu's panel), so the heading is not said twice.
     */
    fun screen(headed: Boolean = true): CatalogScreen = CatalogScreen(
        id = ID,
        title = "Downloads and installs",
        subtitle = "Plugin downloads, library scrapes and other long-running actions, wherever they were started from".takeIf { headed },
        groups = { _ -> groupsFor(PluginJobsCenter.entries().value, System.currentTimeMillis()) },
        live = PluginJobsCenter.entries(),
    )

    /** The screen's sections for [snapshot]: Current, Paused, Completed, each only when it has a job. */
    internal fun groupsFor(snapshot: List<PluginJobsCenter.Entry>, nowMs: Long): List<CatalogGroup> {
        JobEta.forget(snapshot.mapTo(HashSet()) { it.jobId })
        if (snapshot.isEmpty()) {
            return listOf(
                CatalogGroup(
                    id = "plugin_jobs_list",
                    title = null,
                    // A short title: the navigator's detail strip repeats any title longer than a row shows.
                    items = listOf(ActionItem(id = "plugin_jobs_none", title = "Nothing running", subtitle = "No jobs running or recently finished", run = {})),
                ),
            )
        }
        return listOfNotNull(
            section("plugin_jobs_current", "Current", snapshot.filter { !it.done && !it.paused }, nowMs),
            section("plugin_jobs_paused", "Paused", snapshot.filter { !it.done && it.paused }, nowMs),
            section("plugin_jobs_done", "Completed", snapshot.filter { it.done }, nowMs),
        )
    }

    private fun section(id: String, title: String, jobs: List<PluginJobsCenter.Entry>, nowMs: Long): CatalogGroup? =
        jobs.takeIf { it.isNotEmpty() }?.let { CatalogGroup(id = id, title = "$title (${it.size})", items = it.map { entry -> jobRow(entry, nowMs) }) }

    /** The owner's name, the job, and for a job a provider runs for another plugin, who ran it (docs/plugin-api.md 2.4). */
    private fun jobTitle(entry: PluginJobsCenter.Entry): String =
        "${entry.pluginLabel}: ${entry.title}" + (entry.via?.let { " (via $it)" } ?: "")

    private fun outcome(entry: PluginJobsCenter.Entry): String =
        if (entry.result?.ok == true) entry.result?.values?.get("summary") ?: "Done" else (entry.result?.error ?: "Failed")

    /**
     * What a running job's row says in its value column: how far it has got ("42%", "Paused", or
     * the status line while its size is unknown) and roughly how long is left, then its status line
     * (a download's "120 MB of 300 MB"). Pure.
     */
    internal fun progressLine(entry: PluginJobsCenter.Entry, timeLeft: String?): String {
        val first = when {
            entry.paused -> "Paused"
            entry.percent >= 0 -> listOfNotNull("${entry.percent}%", timeLeft).joinToString(" · ")
            else -> entry.statusLine
        }
        return if (entry.percent >= 0 || entry.paused) "$first\n${entry.statusLine}" else first
    }

    /**
     * One job as one row, wherever a list of jobs is drawn (this screen, a store's page): a running
     * or paused job is its name, its progress bar and [progressLine], and A opens its page; a
     * finished one is its name and how it ended.
     */
    fun jobRow(entry: PluginJobsCenter.Entry, nowMs: Long = System.currentTimeMillis()): CatalogItem {
        if (entry.done) {
            return ActionItem(
                id = "plugin_job_${entry.jobId}",
                title = jobTitle(entry),
                subtitle = outcome(entry),
                value = if (entry.result?.ok == true) "Done" else "Failed",
                run = {},
            )
        }
        val line = progressLine(entry, JobEta.line(entry, nowMs))
        return NestedScreenItem(
            id = "plugin_job_${entry.jobId}",
            title = jobTitle(entry),
            subtitle = entry.statusLine,
            inline = jobPage(entry.jobId, jobTitle(entry)),
            valueLabel = { line },
            progress = if (entry.percent >= 0) entry.percent / 100f else -1f,
        )
    }

    /** A job's own page: where it has got, then Pause or Resume and Cancel where the job supports them. Live. */
    private fun jobPage(jobId: String, title: String): CatalogScreen = CatalogScreen(
        id = "plugin_job_page_$jobId",
        title = title,
        groups = { _ ->
            val entry = PluginJobsCenter.entries().value.firstOrNull { it.jobId == jobId }
            listOf(CatalogGroup(id = "plugin_job_page_$jobId", title = null, items = jobControls(jobId, entry)))
        },
        // A job is not a setting: search has nothing to find here.
        indexGroups = { emptyList() },
        live = PluginJobsCenter.entries(),
    )

    private fun jobControls(jobId: String, entry: PluginJobsCenter.Entry?): List<CatalogItem> {
        if (entry == null) {
            return listOf(ActionItem(id = "plugin_job_${jobId}_gone", title = "This job is no longer tracked", run = {}))
        }
        if (entry.done) {
            return listOf(ActionItem(id = "plugin_job_${jobId}_outcome", title = "Finished", subtitle = outcome(entry), value = outcome(entry), run = {}))
        }
        return buildList {
            add(
                ActionItem(
                    id = "plugin_job_${jobId}_progress",
                    title = "Progress",
                    subtitle = entry.statusLine,
                    value = progressLine(entry, JobEta.line(entry, System.currentTimeMillis())),
                    progress = if (entry.percent >= 0) entry.percent / 100f else -1f,
                    run = {},
                ),
            )
            // A native job with no checkpoint yet pauses to a fresh start, so it may always be paused.
            if (entry.pausable && entry.resumable && (entry.paused || entry.resumePayload != null || entry.nativeKind != null)) {
                add(
                    ActionItem(
                        id = "plugin_job_${jobId}_${if (entry.paused) "resume" else "pause"}",
                        title = if (entry.paused) "Resume" else "Pause",
                        run = { if (entry.paused) PluginJobsCenter.resume(jobId) else PluginJobsCenter.pause(jobId) },
                    ),
                )
            }
            add(
                ActionItem(
                    id = "plugin_job_${jobId}_cancel",
                    title = "Cancel",
                    subtitle = when (entry.nativeKind) {
                        null -> "Best effort: the plugin decides whether it actually stops"
                        DownloadJobs.KIND -> "Stops the download and deletes what was downloaded so far"
                        else -> "Stops at the next game"
                    },
                    confirmTitle = "Cancel this job?",
                    run = { PluginJobsCenter.cancel(jobId) },
                ),
            )
        }
    }
}

/**
 * Roughly how long a running job has left (docs/SPEC.md 7j "Places"), from how fast it has moved
 * since droidtop first saw it moving: honest about what it measured, so nothing is said until the
 * job has moved [MIN_MOVED_PERCENT] over at least [MIN_ELAPSED_MS], and a pause, a step backwards
 * or the end starts the measure again. In memory, for the screens that show jobs; nothing polls.
 */
internal object JobEta {
    private const val MIN_MOVED_PERCENT = 1
    private const val MIN_ELAPSED_MS = 5_000L

    private data class Sample(val atMs: Long, val percent: Int)

    private val since = ConcurrentHashMap<String, Sample>()

    /** "About 3 min left" for [entry] at [nowMs], or null while there is nothing honest to say. */
    fun line(entry: PluginJobsCenter.Entry, nowMs: Long): String? {
        if (entry.done || entry.paused || entry.percent !in 0..99) {
            since.remove(entry.jobId)
            return null
        }
        val start = since[entry.jobId]
        if (start == null || entry.percent < start.percent) {
            since[entry.jobId] = Sample(nowMs, entry.percent)
            return null
        }
        return remainingMs(start.atMs, start.percent, nowMs, entry.percent)?.let(::words)
    }

    /** Forgets the jobs no longer listed. */
    fun forget(keep: Set<String>) {
        since.keys.retainAll(keep)
    }

    /** The time left at the rate seen from ([startMs], [startPercent]) to ([nowMs], [percent]); null until it has moved enough. Pure. */
    fun remainingMs(startMs: Long, startPercent: Int, nowMs: Long, percent: Int): Long? {
        val moved = percent - startPercent
        val elapsed = nowMs - startMs
        if (moved < MIN_MOVED_PERCENT || elapsed < MIN_ELAPSED_MS) return null
        return elapsed * (100 - percent) / moved
    }

    /** The time left in the coarsest words that are honest. Pure. */
    fun words(ms: Long): String {
        val minutes = ms / 60_000L
        return when {
            minutes < 1 -> "Under a minute left"
            minutes < 60 -> "About $minutes min left"
            else -> "About ${(minutes + 30) / 60} h left"
        }
    }
}
