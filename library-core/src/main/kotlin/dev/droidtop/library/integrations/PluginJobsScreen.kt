package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.format.ValueFormat
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.JobLook
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.Place
import dev.droidtop.net.DownloadPolicy
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.pluginhost.JobNumbers
import dev.droidtop.pluginhost.JobReport
import dev.droidtop.pluginhost.JobState
import dev.droidtop.pluginhost.Outcome
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.pluginhost.ReportItem
import dev.droidtop.runtime.util.guarded
import kotlinx.coroutines.launch

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
 * ONE row with its name, a progress bar and roughly how long is left
 * ([JobPace]); its figures ([detailLine]) are on its page. A opens the job's
 * own page: Pause or Resume and Cancel while it runs; once it has finished,
 * what it did, line by line ([JobReport]), with Open, Retry and Clear
 * (docs/SPEC.md "Jobs and their results", Droidtop/tracker#577). The screen is
 * live ([CatalogScreen.live]): it is read again whenever a job moves, so
 * nothing on it polls.
 *
 * Registered once (in `:app`'s `AppSettingsCatalogs`) and reached from
 * the "Downloads and Updates" entry of Settings and, in the Gaming
 * shell, from the left menu's place of the same name (docs/SPEC.md 7j
 * "Places"): the Steam-style downloads view, where nothing starts an
 * update (owner, 2026-10-29, Droidtop/tracker#749; updates are checked
 * for and started in the Quick Menu's Downloads tab, [QUICK_MENU_ID])
 * -- same shared-catalog registry every other cross-module
 * management screen uses, so every renderer gets it for free. A store's
 * page lists its own installs with the same rows ([jobRow]).
 */
object PluginJobsScreen {
    const val ID = "plugin_jobs"

    /**
     * The Quick Menu's Downloads tab (owner, 2026-10-29, Droidtop/tracker#749): Check now, Update all, every update,
     * then the jobs. Registered by `:app` (`UpdatesPage`), which owns the updates.
     */
    const val QUICK_MENU_ID = "quick_menu_downloads"

    /** The row that opens Updates ([updatesGroup]). */
    const val UPDATES_LINK_ID = "updates_open"

    /** The Download rules screen's registry id, registered by :app (`DownloadRulesCatalog`). */
    const val RULES_SCREEN_ID = "download_rules"

    /** What a download held for Wi-Fi offers, registered by :app (`MeteredConnectionCatalog`). */
    const val METERED_SCREEN_ID = "metered_connection"

    /**
     * The one place the list of "what is running" is reached; every store's installs are jobs in
     * it. [headed] false is for a host that already names the screen in its own header (the Quick
     * Menu's panel), so the heading is not said twice. In order (Droidtop/tracker#749): the jobs, then [lead] and
     * [trail] (the place registered by `:app` puts Get games and the Sources status list there,
     * Droidtop/tracker#644 slice 3), then Download rules. The page is read again on every job report, so the two are
     * kept for [EXTRA_KEEP_MS] rather than read each time (they read plugins and stat game folders); they are read in
     * the background, so the jobs show at once and the two fill in under a loading row of their own
     * (Droidtop/tracker#748: the page took about 10 s to show anything).
     */
    fun screen(
        headed: Boolean = true,
        lead: suspend (Context) -> List<CatalogGroup> = { emptyList() },
        trail: suspend (Context) -> List<CatalogGroup> = { emptyList() },
    ): CatalogScreen {
        val kept = Kept(lead, trail)
        return CatalogScreen(
            id = ID,
            title = Place.DOWNLOADS.title,
            subtitle = "Getting games, plugin downloads, library scrapes, updates and other long-running work".takeIf { headed },
            groups = { context ->
                val nowMs = System.currentTimeMillis()
                val extra = kept.read(context, nowMs)
                dev.droidtop.library.settings.UpdatesWaiting.refresh?.invoke(context)
                listOf(updatesGroup()) + groupsFor(PluginJobsCenter.entries().value, nowMs) +
                    (extra?.let { (before, after) -> before + after } ?: listOf(loadingGroup())) +
                    rulesGroup()
            },
            onLeave = { kept.clear() },
            live = kotlinx.coroutines.flow.merge(PluginJobsCenter.entries(), kept.changes, dev.droidtop.library.settings.UpdatesWaiting.count),
        )
    }

    /**
     * The way to Updates from Downloads and Updates and the Quick Menu's Downloads tab: one row, "Updates · N
     * available", opening the page (the owner, 2026-10-30, Droidtop/tracker#792: "An updates link should be in the
     * Downloads and Updates tab. Not there directly, but a shortcut"). The updates are only on their own page.
     */
    fun updatesGroup() = CatalogGroup(
        id = "updates_link",
        title = null,
        items = listOf(
            dev.droidtop.library.settings.NestedScreenItem(
                id = UPDATES_LINK_ID,
                title = Place.UPDATES.title,
                subtitle = "droidtop, plugins, apps and games with a newer version",
                valueLabel = { dev.droidtop.library.settings.UpdatesWaiting.line(dev.droidtop.library.settings.UpdatesWaiting.count.value) },
                registryId = Place.ID_UPDATES,
            ),
        ),
    )

    /** Get games and Sources while they are first read: one row of their own, loading. */
    private fun loadingGroup() = CatalogGroup(
        id = "plugin_jobs_loading",
        title = null,
        items = listOf(ActionItem(id = "plugin_jobs_loading_row", title = "Getting your sources", progress = -1f)),
    )

    /** The jobs list's groups as they stand, for a screen that adds its own above them (the Quick Menu's Downloads tab). */
    fun jobGroups(nowMs: Long = System.currentTimeMillis()): List<CatalogGroup> = groupsFor(PluginJobsCenter.entries().value, nowMs)

    /** How long the place's own groups stand before a job report reads them again. */
    const val EXTRA_KEEP_MS = 5_000L

    /**
     * [lead] and [trail] as last read, for [EXTRA_KEEP_MS]. A read runs in the background: [read] answers at once with
     * what is kept (null before the first read ends), and [changes] says when a read ended so the page is built again.
     */
    private class Kept(
        val lead: suspend (Context) -> List<CatalogGroup>,
        val trail: suspend (Context) -> List<CatalogGroup>,
    ) {
        @Volatile private var atMs = 0L
        @Volatile private var groups: Pair<List<CatalogGroup>, List<CatalogGroup>>? = null
        private val reading = java.util.concurrent.atomic.AtomicBoolean(false)
        private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val changes = kotlinx.coroutines.flow.MutableStateFlow(0)

        fun read(context: Context, nowMs: Long): Pair<List<CatalogGroup>, List<CatalogGroup>>? {
            val shown = groups
            if (shown != null && nowMs - atMs < EXTRA_KEEP_MS) return shown
            if (reading.compareAndSet(false, true)) {
                val app = context.applicationContext
                scope.launch {
                    try {
                        guarded("droidtop.Jobs", "reading Get games and Sources", Unit) {
                            groups = lead(app) to trail(app)
                            atMs = System.currentTimeMillis()
                        }
                    } finally {
                        reading.set(false)
                        changes.value++
                    }
                }
            }
            // What was read last stands while it is read again.
            return shown
        }

        fun clear() {
            groups = null
        }
    }

    /** The screen's sections for [snapshot]: Current, Waiting, Paused, Finished, each only when it has a job. */
    internal fun groupsFor(snapshot: List<PluginJobsCenter.Entry>, nowMs: Long): List<CatalogGroup> {
        JobPace.forget(snapshot.mapTo(HashSet()) { it.jobId })
        if (snapshot.isEmpty()) {
            return listOf(
                CatalogGroup(
                    id = "plugin_jobs_list",
                    title = null,
                    // A short title: the navigator's detail strip repeats any title longer than a row shows.
                    items = listOf(ActionItem(id = "plugin_jobs_none", title = "Nothing running", subtitle = "No jobs running or recently finished")),
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
                id = "plugin_jobs_add_link",
                title = "Add download",
                subtitle = "An https link, a magnet link, or a .torrent or .metalink file",
                registryId = ManualDownloads.SCREEN_ID,
            ),
            NestedScreenItem(
                id = "plugin_jobs_rules_link",
                title = "Download rules",
                subtitle = "Which network downloads use, when updates run, and which games update themselves",
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
    internal fun amountLine(numbers: JobNumbers?, speedBps: Long? = numbers?.downloadBps): String? {
        numbers ?: return null
        val bytes = when {
            numbers.bytesDone >= 0 && numbers.bytesTotal > 0 -> "${ValueFormat.size(numbers.bytesDone)} of ${ValueFormat.size(numbers.bytesTotal)}"
            numbers.bytesDone >= 0 -> ValueFormat.size(numbers.bytesDone)
            else -> null
        }
        val speed = speedBps?.takeIf { it > 0 }?.let { "${ValueFormat.size(it)}/s" }
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
     * What a running job's row says in its value column: where it stands ("Paused", "Waiting for the connection") or,
     * while it moves, roughly how long is left ([timeLeft], from [JobPace]), never a number that changes with every
     * report (owner, 2026-10-24, Droidtop/tracker#643: "The current 'flickery numbers for download progress', for
     * instance, is bad"). The bar beside it shows how far; the percent, amounts and speed are [detailLine], on demand.
     * A job that sends only a percent and a line keeps its line under it. Pure.
     */
    internal fun progressLine(entry: PluginJobsCenter.Entry, timeLeft: String?): String {
        val moving = entry.percent >= 0 || amountLine(entry.numbers, speedBps = null) != null
        // A job's own line, said only by a job that sends no numbers: with numbers, its figures are the detail.
        val own = entry.statusLine.takeIf { entry.numbers == null && it.isNotBlank() }
        val first = when {
            entry.hold != null -> entry.hold!!
            entry.paused -> "Paused"
            entry.state == JobState.WAITING_FOR_NETWORK -> "Waiting for the connection"
            entry.state == JobState.WAITING_TO_RETRY -> "Waiting to try again"
            moving && timeLeft != null -> timeLeft
            moving && own != null -> return own
            moving -> return if ((entry.numbers?.bytesDone ?: -1L) >= 0) "Downloading" else "Working"
            else -> entry.statusLine
        }
        // The status line is said once: a paused job whose status is "Paused" read "Paused Paused" (tracker#85).
        val second = own?.takeIf { !it.equals(first, ignoreCase = true) }
        return if ((entry.percent >= 0 || entry.paused) && second != null) "$first\n$second" else first
    }

    /**
     * How far a running job has got in figures, for where the person asked (its page; a focused row): "42%, 120 MB of
     * 300 MB, 4 MB/s", the speed smoothed ([JobPace]). Null when the job gives no figures. Pure but for the pace.
     */
    fun detailLine(entry: PluginJobsCenter.Entry, nowMs: Long): String? {
        val percent = entry.percent.takeIf { it in 0..100 }?.let { "$it%" }
        val amount = amountLine(entry.numbers, speedBps = JobPace.speed(entry, nowMs) ?: entry.numbers?.downloadBps)
        return listOfNotNull(percent, amount).joinToString(", ").ifEmpty { null }
    }

    /**
     * How [entry]'s bar moves (Droidtop/tracker#643): waiting its turn or for a connection is queued, stopped by the
     * person is paused, under way with no fraction is checking, and a finished job is done or failed. Pure.
     */
    fun lookOf(entry: PluginJobsCenter.Entry): JobLook = when (entry.state) {
        JobState.FAILED -> JobLook.FAILED
        JobState.DONE -> JobLook.DONE
        JobState.HELD, JobState.WAITING_FOR_NETWORK, JobState.WAITING_TO_RETRY -> JobLook.QUEUED
        JobState.PAUSED -> JobLook.PAUSED
        JobState.RUNNING -> if (entry.percent >= 0) JobLook.ACTIVE else JobLook.CHECKING
    }

    /** How many parts [entry] is counted in, for a segmented bar: its things to do when it counts 2 or more, else 0. Pure. */
    fun partsOf(entry: PluginJobsCenter.Entry): Int = entry.numbers?.itemsTotal?.takeIf { it >= 2 } ?: 0

    /**
     * A job said in one line, for a place with room for one: a game's button and its menu row (docs/SPEC.md 12a,
     * "Jobs and their results"). The same words as the job's row here ([progressLine]), joined, so the game page cannot
     * say less than the Downloads place; a failed job says why.
     */
    fun oneLine(entry: PluginJobsCenter.Entry, nowMs: Long = System.currentTimeMillis()): String =
        if (entry.done) outcome(entry).lineSequence().first() else progressLine(entry, JobPace.line(entry, nowMs)).replace("\n", " · ")

    /**
     * One job as one row, wherever a list of jobs is drawn (this screen, a store's page): a running
     * or paused job is its name, its progress bar and [progressLine] under it, and A opens its page; a
     * finished one is its name, its mark (a check or a cross) and how it ended under it.
     *
     * Show, then say (owner, 2026-10-29, Droidtop/tracker#738: "we need to use visual indicators instead of words
     * whenever possible. Numbers and words can be in ADDITION to them"): the bar and the mark are the state; the words
     * are the line under the title, never a value column, which took the title's width in a narrow pane and broke it
     * mid-word ("Synct / hing", Droidtop/tracker#739).
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
                progressLook = lookOf(entry),
                summaryShown = true,
            )
        }
        val line = progressLine(entry, JobPace.line(entry, nowMs)).replace("\n", " · ")
        return NestedScreenItem(
            id = "plugin_job_${entry.jobId}",
            title = entry.title,
            subtitle = listOf(line, jobSource(entry)).filter { it.isNotBlank() }.joinToString(" · "),
            inline = jobPage(entry.jobId, jobTitle(entry)),
            progress = if (entry.percent >= 0) entry.percent / 100f else -1f,
            progressLook = lookOf(entry),
            progressParts = partsOf(entry),
            summaryShown = true,
        )
    }

    /**
     * A job's own page: where it has got, then Pause or Resume and Cancel where the job supports them. Live, and it
     * follows its job (tracker#577, rig on 0.2.0-dev.2194: a page left open said "This job is no longer tracked" when
     * its job finished or was retried): after Retry it shows the new run ([PluginJobsCenter.followed]), and while the
     * list does not hold the job for a moment it keeps showing what it last saw. Only a job the person cleared is gone.
     */
    /** [jobId]'s own page, for a press on its flash (JobNotices): what it did, with Open, Retry and Clear. */
    fun pageOf(jobId: String, title: String): CatalogScreen = jobPage(jobId, title)

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
            return listOf(ActionItem(id = "plugin_job_${jobId}_gone", title = said))
        }
        if (entry.done) return finishedControls(jobId, entry)
        return buildList {
            add(
                ActionItem(
                    id = "plugin_job_${jobId}_progress",
                    title = "Progress",
                    // The page is where the person asked: the figures are here, as well as the calm line.
                    subtitle = listOfNotNull(detailLine(entry, System.currentTimeMillis()), entry.statusLine.takeIf { it.isNotBlank() }).joinToString("\n"),
                    value = progressLine(entry, JobPace.line(entry, System.currentTimeMillis())),
                    progress = if (entry.percent >= 0) entry.percent / 100f else -1f,
                    progressLook = lookOf(entry),
                    progressParts = partsOf(entry),
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
                    // Named for what it stops, and its question's safe answer for what it keeps (the owner on the
                    // console: both buttons said "Cancel", the first meaning keep downloading).
                    title = if (dev.droidtop.pluginhost.PluginJobsCenter.isDownloadKind(entry.nativeKind)) "Cancel download" else "Cancel job",
                    confirmKeep = if (dev.droidtop.pluginhost.PluginJobsCenter.isDownloadKind(entry.nativeKind)) "Keep downloading" else "Keep it running",
                    subtitle = when (entry.nativeKind) {
                        null -> "Best effort: the plugin decides whether it actually stops"
                        // A store install's cancel deletes its partial files too (StoreInstallJob.discard).
                        DownloadJobs.KIND, dev.droidtop.library.stores.StoreInstallJob.KIND -> "Stops the download and deletes what was downloaded so far"
                        else -> "Stops at the next game"
                    },
                    confirmTitle = if (dev.droidtop.pluginhost.PluginJobsCenter.isDownloadKind(entry.nativeKind)) "Cancel this download?" else "Cancel this job?",
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
                ActionItem(id = "plugin_job_${jobId}_head_${outcome.name}", title = "${outcomeHeading(outcome)} (${lines.size})"),
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
