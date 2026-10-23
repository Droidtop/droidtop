package dev.droidtop.library.integrations

import dev.droidtop.library.format.ValueFormat
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.net.DownloadPolicy
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.pluginhost.JobNumbers
import dev.droidtop.pluginhost.JobReport
import dev.droidtop.pluginhost.JobState
import dev.droidtop.pluginhost.Outcome
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.pluginhost.ReportItem
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
 * tracker#363 slice 9): the jobs fall into Current, Waiting (held by the
 * download rules), Paused and Finished, each headed with its count; a job is
 * ONE row with its name, a progress bar, how far it has got from its numbers
 * ([amountLine]) and roughly how long is left ([JobEta]). A opens the job's
 * own page: Pause or Resume and Cancel while it runs; once it has finished,
 * what it did, line by line ([JobReport]), with Open, Retry and Clear
 * (docs/SPEC.md "Jobs and their results", Droidtop/tracker#577). The screen is
 * live ([CatalogScreen.live]): it is read again whenever a job moves, so
 * nothing on it polls.
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

    /** The Download rules screen's registry id, registered by :app (`DownloadRulesCatalog`). */
    const val RULES_SCREEN_ID = "download_rules"

    /** What a download held for Wi-Fi offers, registered by :app (`MeteredConnectionCatalog`). */
    const val METERED_SCREEN_ID = "metered_connection"

    /**
     * The one place the list of "what is running" is reached; every store's installs are jobs in
     * it. [headed] false is for a host that already names the screen in its own header (the Quick
     * Menu's panel), so the heading is not said twice.
     */
    fun screen(headed: Boolean = true): CatalogScreen = CatalogScreen(
        id = ID,
        title = "Downloads and installs",
        subtitle = "Plugin downloads, library scrapes and other long-running actions, wherever they were started from".takeIf { headed },
        groups = { _ -> groupsFor(PluginJobsCenter.entries().value, System.currentTimeMillis()) + rulesGroup() },
        live = PluginJobsCenter.entries(),
    )

    /** The screen's sections for [snapshot]: Current, Waiting, Paused, Finished, each only when it has a job. */
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
        val finished = snapshot.filter { it.done }.sortedByDescending { it.finishedAtMs ?: it.startedAtMs }
        return listOfNotNull(
            section("plugin_jobs_current", "Current", snapshot.filter { it.state == JobState.RUNNING }, nowMs),
            // Held by the download rules, or waiting for the connection or for a retry: none of them is the person's pause.
            section("plugin_jobs_waiting", "Waiting", snapshot.filter { it.state in WAITING }, nowMs),
            section("plugin_jobs_paused", "Paused", snapshot.filter { it.state == JobState.PAUSED }, nowMs),
            section("plugin_jobs_done", "Finished", finished, nowMs)?.let { group ->
                group.copy(items = group.items + clearFinishedItem())
            },
        )
    }

    private val WAITING = setOf(JobState.HELD, JobState.WAITING_FOR_NETWORK, JobState.WAITING_TO_RETRY)

    private fun clearFinishedItem(): CatalogItem = ActionItem(
        id = "plugin_jobs_clear_finished",
        title = "Clear finished",
        subtitle = "Forgets every finished job and what it did",
        confirmTitle = "Clear every finished job from this list?",
        run = { PluginJobsCenter.clearFinished() },
    )

    /** The way to the download rules (network, update window, automatic updates), from the place where downloads are seen. */
    private fun rulesGroup() = CatalogGroup(
        id = "plugin_jobs_rules",
        title = null,
        items = listOf(
            NestedScreenItem(
                id = "plugin_jobs_rules_link",
                title = "Download rules",
                subtitle = "Which network downloads use, when updates may run, and which games keep themselves up to date",
                registryId = RULES_SCREEN_ID,
            ),
        ),
    )

    private fun section(id: String, title: String, jobs: List<PluginJobsCenter.Entry>, nowMs: Long): CatalogGroup? =
        jobs.takeIf { it.isNotEmpty() }?.let { CatalogGroup(id = id, title = "$title (${it.size})", items = it.map { entry -> jobRow(entry, nowMs) }) }

    /** The owner's name, the job, and for a job a provider runs for another plugin, who ran it (docs/plugin-api.md 2.4). */
    private fun jobTitle(entry: PluginJobsCenter.Entry): String =
        "${entry.pluginLabel}: ${entry.title}" + (entry.via?.let { " (via $it)" } ?: "")

    /**
     * Who runs a job, for a row's second line: the plugin and the launcher it came through. A row's title is the job's
     * own name, so a list of jobs from one plugin is not ten rows that all read "Downloads: ..." once cut to the
     * row's width (Droidtop/tracker#85, rig 2026-10-14).
     */
    private fun jobSource(entry: PluginJobsCenter.Entry): String = entry.pluginLabel + (entry.via?.let { " (via $it)" } ?: "")

    private fun outcome(entry: PluginJobsCenter.Entry): String =
        if (entry.state != JobState.FAILED) entry.result?.values?.get("summary") ?: "Done" else (entry.result?.error ?: "Failed")

    /**
     * How far a job has got, in its own numbers: "120 MB of 300 MB, 4.2 MB/s" for a transfer, "12 of 40" for work
     * counted in things; null when the job reports only a percent. Pure.
     */
    internal fun amountLine(numbers: JobNumbers?): String? {
        numbers ?: return null
        val bytes = when {
            numbers.bytesDone >= 0 && numbers.bytesTotal > 0 -> "${ValueFormat.size(numbers.bytesDone)} of ${ValueFormat.size(numbers.bytesTotal)}"
            numbers.bytesDone >= 0 -> ValueFormat.size(numbers.bytesDone)
            else -> null
        }
        val speed = numbers.downloadBps.takeIf { it > 0 }?.let { "${ValueFormat.size(it)}/s" }
        val items = if (numbers.itemsDone >= 0 && numbers.itemsTotal > 0) "${numbers.itemsDone} of ${numbers.itemsTotal}" else null
        return listOfNotNull(bytes, speed, items).joinToString(", ").ifEmpty { null }
    }

    /** What a report holds, counted: "3 done, 1 skipped, 2 failed". Null for an empty report. Pure. */
    internal fun reportCounts(report: JobReport?): String? {
        report ?: return null
        return OUTCOME_ORDER.mapNotNull { outcome -> report.count(outcome).takeIf { it > 0 }?.let { "$it ${outcomeWord(outcome)}" } }
            .joinToString(", ").ifEmpty { null }
    }

    private val OUTCOME_ORDER = listOf(Outcome.FAILED, Outcome.NEEDS_PICK, Outcome.DONE, Outcome.SKIPPED)

    private fun outcomeWord(outcome: Outcome): String = when (outcome) {
        Outcome.DONE -> "done"
        Outcome.SKIPPED -> "skipped"
        Outcome.NEEDS_PICK -> "need your pick"
        Outcome.FAILED -> "failed"
    }

    private fun outcomeHeading(outcome: Outcome): String = when (outcome) {
        Outcome.DONE -> "Done"
        Outcome.SKIPPED -> "Skipped"
        Outcome.NEEDS_PICK -> "Needs your pick"
        Outcome.FAILED -> "Failed"
    }

    /**
     * What a running job's row says in its value column: how far it has got ("42%", "Paused", or
     * the status line while its size is unknown) and roughly how long is left, then its status line
     * (a download's "120 MB of 300 MB"). Pure.
     */
    internal fun progressLine(entry: PluginJobsCenter.Entry, timeLeft: String?): String {
        val first = when {
            entry.hold != null -> entry.hold!!
            entry.paused -> "Paused"
            entry.state == JobState.WAITING_FOR_NETWORK -> "Waiting for the connection"
            entry.state == JobState.WAITING_TO_RETRY -> "Waiting to try again"
            entry.percent >= 0 -> listOfNotNull("${entry.percent}%", timeLeft).joinToString(" · ")
            else -> entry.statusLine
        }
        // The job's own numbers say how far it has got better than its line does; the line stays for what they cannot say.
        amountLine(entry.numbers)?.takeIf { !entry.paused }?.let { amount ->
            val status = entry.statusLine.takeIf { it.isNotBlank() && !it.equals(first, ignoreCase = true) }
            return listOfNotNull(first, amount, status).joinToString("\n")
        }
        // The status line is said once: a paused job whose status is "Paused" read "Paused Paused" (tracker#85).
        val second = entry.statusLine.takeIf { it.isNotBlank() && !it.equals(first, ignoreCase = true) }
        return if ((entry.percent >= 0 || entry.paused) && second != null) "$first\n$second" else first
    }

    /**
     * One job as one row, wherever a list of jobs is drawn (this screen, a store's page): a running
     * or paused job is its name, its progress bar and [progressLine], and A opens its page; a
     * finished one is its name and how it ended.
     */
    fun jobRow(entry: PluginJobsCenter.Entry, nowMs: Long = System.currentTimeMillis()): CatalogItem {
        if (entry.done) {
            // A finished job opens what it did (its report), with Open, Retry and Clear.
            val said = reportCounts(entry.report) ?: outcome(entry).lineSequence().first()
            return NestedScreenItem(
                id = "plugin_job_${entry.jobId}",
                title = entry.title,
                subtitle = "${jobSource(entry)} · $said",
                inline = jobPage(entry.jobId, jobTitle(entry)),
                valueLabel = { if (entry.state == JobState.FAILED) "Failed" else "Done" },
            )
        }
        val line = progressLine(entry, JobEta.line(entry, nowMs))
        return NestedScreenItem(
            id = "plugin_job_${entry.jobId}",
            title = entry.title,
            subtitle = jobSource(entry),
            inline = jobPage(entry.jobId, jobTitle(entry)),
            valueLabel = { line },
            progress = if (entry.percent >= 0) entry.percent / 100f else -1f,
        )
    }

    /**
     * A job's own page: where it has got, then Pause or Resume and Cancel where the job supports them. Live, and it
     * follows its job (tracker#577, rig on 0.2.0-dev.2194: a page left open said "This job is no longer tracked" when
     * its job finished or was retried): after Retry it shows the new run ([PluginJobsCenter.followed]), and while the
     * list does not hold the job for a moment it keeps showing what it last saw. Only a job the person cleared is gone.
     */
    private fun jobPage(jobId: String, title: String): CatalogScreen {
        var lastSeen: PluginJobsCenter.Entry? = null
        return CatalogScreen(
            id = "plugin_job_page_$jobId",
            title = title,
            groups = { _ ->
                val current = PluginJobsCenter.followed(jobId)
                val entry = PluginJobsCenter.find(current)?.also { lastSeen = it }
                    ?: lastSeen?.takeIf { it.jobId == current && !PluginJobsCenter.wasCleared(current) }
                listOf(CatalogGroup(id = "plugin_job_page_$jobId", title = null, items = jobControls(current, entry)))
            },
            // A job is not a setting: search has nothing to find here.
            indexGroups = { emptyList() },
            live = PluginJobsCenter.entries(),
        )
    }

    private fun jobControls(jobId: String, entry: PluginJobsCenter.Entry?): List<CatalogItem> {
        if (entry == null) {
            val said = if (PluginJobsCenter.wasCleared(jobId)) "This job was cleared" else "This job is no longer tracked"
            return listOf(ActionItem(id = "plugin_job_${jobId}_gone", title = said, run = {}))
        }
        if (entry.done) return finishedControls(jobId, entry)
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
            // Download now on a held download, even a single file that has no Pause, puts it first in line and starts it,
            // except past the connection: it waits for that like every download, first (docs/SPEC.md 12a). On a metered
            // connection the person allows the connection, never this one download.
            val networkHold = DownloadPolicy.isNetworkHold(entry.hold)
            if (entry.hold == DownloadPolicy.WAITING_FOR_WIFI) {
                add(
                    NestedScreenItem(
                        id = "plugin_job_${jobId}_metered",
                        title = "Downloads on this connection",
                        subtitle = "This connection is metered. You can allow it for every download, never for one",
                        registryId = METERED_SCREEN_ID,
                    ),
                )
            }
            if (entry.hold != null || (entry.pausable && entry.resumable && (entry.paused || entry.resumePayload != null || entry.nativeKind != null))) {
                add(
                    ActionItem(
                        id = "plugin_job_${jobId}_${if (entry.paused) "resume" else "pause"}",
                        title = if (entry.hold != null) "Download now" else if (entry.paused) "Resume" else "Pause",
                        subtitle = if (networkHold) "Goes first. It starts when there is a connection downloads may use" else null,
                        run = { if (entry.paused) PluginJobsCenter.resume(jobId) else PluginJobsCenter.pause(jobId) },
                    ),
                )
            }
            // A store download that stopped (a crash left it Paused with an empty bar, Droidtop/tracker#313) can be started
            // over: Resume continues from the files on disk, Start over deletes them first, and asks.
            if (entry.nativeKind == dev.droidtop.library.stores.StoreInstallJob.KIND && entry.paused) {
                add(
                    AsyncActionItem(
                        id = "plugin_job_${jobId}_restart",
                        title = "Start over",
                        subtitle = "Deletes what was downloaded so far and downloads the game again",
                        confirmTitle = "Start this download over? What was downloaded so far is deleted.",
                        run = { ctx, _ -> if (dev.droidtop.library.stores.StoreInstallJob.restart(ctx, jobId)) "Starting over" else "This download can no longer be started over" },
                    ),
                )
            }
            reportItems(jobId, entry.report).forEach(::add)
            add(
                ActionItem(
                    id = "plugin_job_${jobId}_cancel",
                    title = "Cancel",
                    subtitle = when (entry.nativeKind) {
                        null -> "Best effort: the plugin decides whether it actually stops"
                        // A store install's cancel deletes its partial files too (StoreInstallJob.discard).
                        DownloadJobs.KIND, dev.droidtop.library.stores.StoreInstallJob.KIND -> "Stops the download and deletes what was downloaded so far"
                        else -> "Stops at the next game"
                    },
                    confirmTitle = "Cancel this job?",
                    run = { PluginJobsCenter.cancel(jobId) },
                ),
            )
        }
    }

    /**
     * A finished job's page: how it ended, then Open (what it made), Retry (a native job, with the same arguments)
     * and Clear, then what it did, a section per outcome, failures first (docs/SPEC.md "Jobs and their results").
     */
    private fun finishedControls(jobId: String, entry: PluginJobsCenter.Entry): List<CatalogItem> = buildList {
        add(
            ActionItem(
                id = "plugin_job_${jobId}_outcome",
                title = if (entry.state == JobState.FAILED) "Failed" else "Finished",
                subtitle = listOfNotNull(outcome(entry), reportCounts(entry.report)).joinToString("\n"),
                run = {},
            ),
        )
        entry.open?.takeIf { JobOpen.available }?.let { target ->
            add(ActionItem(id = "plugin_job_${jobId}_open", title = JobOpen.label(target), run = { ctx -> JobOpen.open(ctx, target) }))
        }
        if (entry.retryable) {
            add(
                ActionItem(
                    id = "plugin_job_${jobId}_retry",
                    title = "Retry",
                    subtitle = "Runs it again the same way",
                    run = { ctx -> PluginJobsCenter.retry(ctx, jobId) },
                ),
            )
        }
        add(
            ActionItem(
                id = "plugin_job_${jobId}_clear",
                title = "Clear",
                subtitle = "Forgets this job and what it did",
                run = { PluginJobsCenter.clear(jobId) },
            ),
        )
        reportItems(jobId, entry.report).forEach(::add)
    }

    /** A report's lines, grouped by outcome with a heading row each; a line with somewhere to go opens it. */
    private fun reportItems(jobId: String, report: JobReport?): List<CatalogItem> {
        report ?: return emptyList()
        return OUTCOME_ORDER.flatMap { outcome ->
            val lines = report.items.filter { it.outcome == outcome }
            if (lines.isEmpty()) return@flatMap emptyList()
            listOf<CatalogItem>(
                ActionItem(id = "plugin_job_${jobId}_head_${outcome.name}", title = "${outcomeHeading(outcome)} (${lines.size})", run = {}),
            ) + lines.mapIndexed { index, line -> reportRow(jobId, outcome, index, line) }
        }
    }

    private fun reportRow(jobId: String, outcome: Outcome, index: Int, line: ReportItem): CatalogItem {
        val target = line.open?.takeIf { JobOpen.available }
        return ActionItem(
            id = "plugin_job_${jobId}_${outcome.name}_$index",
            title = line.title,
            subtitle = line.detail,
            run = { ctx -> target?.let { JobOpen.open(ctx, it) } },
        )
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
