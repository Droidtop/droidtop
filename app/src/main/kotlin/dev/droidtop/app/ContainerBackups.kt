package dev.droidtop.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Backups on a schedule (docs/SPEC.md 3d "Backups on a schedule", Droidtop/tracker#808): each container can be backed
 * up daily or weekly to a folder the person picked, as "Back up its data" does by hand. One system job a day, only
 * while charging and with the battery not low, makes every backup that is due. A backup needs the container stopped,
 * since its files change while it runs, so a running container is skipped and said so on its row; droidtop never
 * stops one for this. Settings in droidtop's own preferences, by container id.
 */
object ContainerBackups {
    private const val TAG = "droidtop.Backups"
    private const val PREFS = "container_backups"
    private const val JOB_ID = 0x424B50 // "BKP"
    private const val DAY_MS = 86_400_000L

    /** How often; Off by default. A little early still counts, so a daily job that runs late does not skip a day. */
    enum class Frequency(val id: String, val label: String, val intervalMs: Long) {
        OFF("off", "Off", 0L),
        DAILY("daily", "Every day", DAY_MS),
        WEEKLY("weekly", "Every week", 7 * DAY_MS),
        ;

        companion object {
            fun fromId(id: String?): Frequency = entries.firstOrNull { it.id == id } ?: OFF
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun frequency(context: Context, containerId: String): Frequency = Frequency.fromId(prefs(context).getString("$containerId.frequency", null))

    fun setFrequency(context: Context, containerId: String, frequency: Frequency) {
        prefs(context).edit().putString("$containerId.frequency", frequency.id).apply()
        schedule(context)
    }

    /** The folder backups go to (a tree the person granted), or null before one is picked. */
    fun folder(context: Context, containerId: String): Uri? = prefs(context).getString("$containerId.folder", null)?.let(Uri::parse)

    fun setFolder(context: Context, containerId: String, tree: Uri) {
        prefs(context).edit().putString("$containerId.folder", tree.toString()).apply()
    }

    /** What the last scheduled run did, for the row: "Saved 3 Oct, 02:14" or why not. */
    fun lastResult(context: Context, containerId: String): String? = prefs(context).getString("$containerId.result", null)

    private fun lastSaved(context: Context, containerId: String): Long = prefs(context).getLong("$containerId.saved", 0L)

    /** Whether a backup is due at [nowMs] after the last one at [lastMs]. A tenth early counts. Pure. */
    internal fun due(frequency: Frequency, lastMs: Long, nowMs: Long): Boolean =
        frequency != Frequency.OFF && nowMs - lastMs >= frequency.intervalMs - frequency.intervalMs / 10

    /** The file one backup is saved as: the container's name and when. Pure. */
    internal fun fileName(displayName: String, atMs: Long): String {
        val name = displayName.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "container" }
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date(atMs))
        return "$name-$stamp.tar"
    }

    /**
     * Keeps the one daily job in step with the settings: there while any container has a schedule, gone otherwise.
     * Called when droidtop's process starts (the job is not persisted) and when a schedule changes.
     */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return
        val any = prefs(app).all.any { (key, value) -> key.endsWith(".frequency") && value != Frequency.OFF.id }
        if (!any) {
            scheduler.cancel(JOB_ID)
            return
        }
        if (scheduler.getPendingJob(JOB_ID) != null) return
        scheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(app, ContainerBackupJobService::class.java))
                .setPeriodic(DAY_MS)
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .setPersisted(false)
                .build(),
        )
    }

    /** Makes every backup that is due. Blocking work: run from the job's IO scope. */
    suspend fun runDue(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val due = prefs(app).all.keys.filter { it.endsWith(".frequency") }.map { it.removeSuffix(".frequency") }
            .filter { id -> due(frequency(app, id), lastSaved(app, id), now) && folder(app, id) != null }
        if (due.isEmpty()) return
        val runtime = ContainerRuntimeFactory.select(app)
        val containers = runCatching { runtime.listContainers() }.getOrDefault(emptyList()).associateBy { it.container.id }
        for (id in due) {
            val info = containers[id] ?: continue
            val result = when {
                runtime.dataBackupUnavailableReason != null -> "Not saved: ${runtime.dataBackupUnavailableReason}"
                info.running -> "Skipped: it was running. Next try tomorrow"
                else -> save(app, runtime, info, folder(app, id)!!, now)
            }
            prefs(app).edit().putString("$id.result", result).apply()
        }
    }

    private suspend fun save(
        context: Context,
        runtime: dev.droidtop.runtime.ContainerRuntime,
        info: dev.droidtop.runtime.ContainerInfo,
        tree: Uri,
        now: Long,
    ): String {
        val resolver = context.contentResolver
        val parent = runCatching { DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)) }.getOrNull()
            ?: return "Not saved: the folder can't be reached. Pick it again"
        val file = runCatching { DocumentsContract.createDocument(resolver, parent, "application/x-tar", fileName(info.displayName, now)) }.getOrNull()
            ?: return "Not saved: the folder can't be written to. Pick it again"
        return runCatching {
            resolver.openOutputStream(file, "wt")!!.use { runtime.exportData(info.container, it) }
            prefs(context).edit().putLong("${info.container.id}.saved", now).apply()
            "Saved " + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(now))
        }.getOrElse {
            Log.w(TAG, "Scheduled backup of ${info.container.id} failed", it)
            // A half-written archive that looks like a backup is worse than none.
            runCatching { DocumentsContract.deleteDocument(resolver, file) }
            "Not saved: ${it.message ?: "it failed"}"
        }
    }
}

/** Runs [ContainerBackups.runDue] when Android's job scheduler says it is time. */
class ContainerBackupJobService : JobService() {
    private val scope = dev.droidtop.runtime.util.AppScopes.io
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            runCatching { ContainerBackups.runDue(applicationContext) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }
}
