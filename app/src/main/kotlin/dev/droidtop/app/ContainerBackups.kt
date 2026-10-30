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

    /** The name every file of [displayName]'s backups starts with. Pure. */
    internal fun baseName(displayName: String): String = displayName.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "container" }

    private fun stamp(atMs: Long): String = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date(atMs))

    /** A full backup's file: the container's name and when. Pure. */
    internal fun fileName(displayName: String, atMs: Long): String = "${baseName(displayName)}-${stamp(atMs)}.tar"

    /** An increment's archive and its manifest (docs/SPEC.md 3d, slice B2). Pure. */
    internal fun incrementNames(displayName: String, atMs: Long): Pair<String, String> {
        val base = "${baseName(displayName)}-${stamp(atMs)}"
        return "$base$INCREMENT_SUFFIX" to "$base$MANIFEST_SUFFIX"
    }

    const val INCREMENT_SUFFIX = "-changes.tar"
    const val MANIFEST_SUFFIX = "-changes.manifest"

    /** One backup in the folder, by its file's name: when, and whether it is a full one or an increment. */
    data class Point(val name: String, val stamp: String, val full: Boolean)

    /** The backups of [base] among [names], oldest first. Pure. */
    internal fun points(base: String, names: List<String>): List<Point> = names.mapNotNull { name ->
        if (!name.startsWith("$base-")) return@mapNotNull null
        val rest = name.removePrefix("$base-")
        when {
            rest.endsWith(INCREMENT_SUFFIX) -> Point(name, rest.removeSuffix(INCREMENT_SUFFIX), full = false)
            rest.endsWith(".tar") -> Point(name, rest.removeSuffix(".tar"), full = true)
            else -> null
        }
    }.filter { STAMP.matches(it.stamp) }.sortedBy { it.stamp }

    private val STAMP = Regex("""\d{4}-\d{2}-\d{2}-\d{4}""")

    /** What restoring [chosen] takes: the full backup at or before it, then each increment after that up to it. Pure. */
    internal fun chain(points: List<Point>, chosen: Point): List<Point> {
        val upTo = points.filter { it.stamp <= chosen.stamp }
        val start = upTo.indexOfLast { it.full }
        require(start >= 0) { "No full backup before it in the folder" }
        return upTo.drop(start)
    }

    /** The files to delete once a new full backup is written: every chain but the newest two. Pure. */
    internal fun stale(points: List<Point>): List<Point> {
        val fulls = points.filter { it.full }
        if (fulls.size <= 2) return emptyList()
        val keepFrom = fulls[fulls.size - 2].stamp
        return points.filter { it.stamp < keepFrom }
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

    private fun parentOf(tree: Uri): Uri? =
        runCatching { DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)) }.getOrNull()

    /** The folder's files by name. A content query: IO only. */
    private fun list(context: Context, tree: Uri): Map<String, Uri> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val found = HashMap<String, Uri>()
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                found[cursor.getString(1)] = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
            }
        }
        return found
    }

    /**
     * One scheduled backup: a full one first and after [dev.droidtop.runtime.BackupIncrements.INCREMENTS_PER_CHAIN]
     * increments, an increment (what changed since the last backup, and the manifest) otherwise. After a full one, the
     * chains before the newest two go.
     */
    private suspend fun save(
        context: Context,
        runtime: dev.droidtop.runtime.ContainerRuntime,
        info: dev.droidtop.runtime.ContainerInfo,
        tree: Uri,
        now: Long,
    ): String {
        val resolver = context.contentResolver
        val id = info.container.id
        val parent = parentOf(tree) ?: return "Not saved: the folder can't be reached. Pick it again"
        val last = lastSaved(context, id)
        val increments = prefs(context).getInt("$id.increments", 0)
        val full = last == 0L || increments >= dev.droidtop.runtime.BackupIncrements.INCREMENTS_PER_CHAIN
        val made = ArrayList<Uri>()
        fun create(mime: String, name: String): Uri =
            DocumentsContract.createDocument(resolver, parent, mime, name)?.also { made += it } ?: error("the folder can't be written to. Pick it again")
        return runCatching {
            if (full) {
                val file = create("application/x-tar", fileName(info.displayName, now))
                resolver.openOutputStream(file, "wt")!!.use { runtime.exportData(info.container, it) }
            } else {
                val (archiveName, manifestName) = incrementNames(info.displayName, now)
                val archive = create("application/x-tar", archiveName)
                val manifest = create("text/plain", manifestName)
                val minutes = (now - last) / 60_000L
                resolver.openOutputStream(archive, "wt")!!.use { a ->
                    resolver.openOutputStream(manifest, "wt")!!.use { m -> runtime.exportIncrement(info.container, minutes, a, m) }
                }
            }
            prefs(context).edit().putLong("$id.saved", now).putInt("$id.increments", if (full) 0 else increments + 1).apply()
            if (full) prune(context, tree, info.displayName)
            (if (full) "Saved in full " else "Saved the changes ") + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(now))
        }.getOrElse {
            Log.w(TAG, "Scheduled backup of $id failed", it)
            // A half-written archive that looks like a backup is worse than none.
            made.forEach { uri -> runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
            "Not saved: ${it.message ?: "it failed"}"
        }
    }

    /** Deletes every chain but the newest two, with their manifests. */
    private fun prune(context: Context, tree: Uri, displayName: String) {
        val files = runCatching { list(context, tree) }.getOrDefault(emptyMap())
        val base = baseName(displayName)
        stale(points(base, files.keys.toList())).forEach { point ->
            val names = if (point.full) listOf(point.name) else listOf(point.name, "$base-${point.stamp}$MANIFEST_SUFFIX")
            names.forEach { name -> files[name]?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } } }
        }
    }

    /**
     * Restores [container] to the backup named [chosenName] in its folder: the full backup before it, then each
     * increment up to it, then the tree made to hold exactly what that point's manifest lists. Stopped containers only.
     */
    suspend fun restore(context: Context, runtime: dev.droidtop.runtime.ContainerRuntime, info: dev.droidtop.runtime.ContainerInfo, chosenName: String): String {
        val tree = folder(context, info.container.id) ?: return "Pick the folder scheduled backups go to first"
        val files = list(context, tree)
        val base = baseName(info.displayName)
        val all = points(base, files.keys.toList())
        val chosen = all.firstOrNull { it.name == chosenName } ?: return "That file isn't one of this container's scheduled backups"
        val steps = runCatching { chain(all, chosen) }.getOrElse { return it.message ?: "No full backup before it" }
        val resolver = context.contentResolver
        for (step in steps) {
            val uri = files.getValue(step.name)
            resolver.openInputStream(uri)!!.use { input ->
                if (step.full) runtime.importData(info.container, input) else runtime.applyIncrement(info.container, input)
            }
        }
        if (!chosen.full) {
            val manifest = files["$base-${chosen.stamp}$MANIFEST_SUFFIX"]?.let { uri -> resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }
                ?: return "Restored, but its manifest is missing, so files deleted since the full backup are still there"
            runtime.pruneToManifest(info.container, manifest)
        }
        return "Restored to " + chosen.stamp
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
