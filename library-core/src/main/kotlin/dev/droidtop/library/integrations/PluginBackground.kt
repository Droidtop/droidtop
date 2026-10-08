package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.pluginhost.BackgroundProtocol
import dev.droidtop.pluginhost.GrantState
import dev.droidtop.pluginhost.LegacyManifest
import dev.droidtop.pluginhost.PluginGrants
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginResult
import dev.droidtop.pluginhost.ProvidedPoint
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/*
 * Plugin work that outlives a screen (docs/plugin-api.md 3 E8, E9): background services and scheduled tasks, started
 * as ordinary plugin jobs. Which plugins have any is read from manifests and grants, never by loading a plugin.
 */

/** Starts [op] on [point] as a contract 2 job and returns its id at once (null when the plugin refused); progress is in Jobs. */
internal suspend fun startPluginJob(
    context: Context,
    record: PluginRecord,
    point: String,
    op: String,
    args: JSONObject,
    surface: String,
    title: String,
    onComplete: (PluginResult) -> Unit = {},
): String? = PluginJobsCenter.start(
    context = context,
    record = record,
    capability = LegacyManifest.jobCapabilityFor(point),
    args = mapOf("call" to newCall(point, op, surface, args, 0L).toJson().toString()),
    title = title,
    onComplete = onComplete,
)

/**
 * Background services (`jobs.service@1`, E8) and scheduled tasks (`jobs.schedule@1`, E9): droidtop starts them as
 * ordinary plugin jobs (shown in Jobs), only for a running plugin whose point the person allowed and whose entry's own
 * switch is on. Services are kept running by [syncServices] while :app's foreground service ([host]) holds the
 * process; schedules are run by [runDueSchedules] from :app's periodic system job.
 */
object PluginBackground {
    /** :app's side: the foreground service that keeps services alive, and the periodic job that runs schedules. */
    interface Host {
        fun servicesRunning(context: Context, count: Int)
        fun schedulesWanted(context: Context, wanted: Boolean)
    }

    @Volatile var host: Host? = null

    data class Entry(val record: PluginRecord, val entry: ProvidedPoint) {
        val key: String get() = record.manifest.id + "/" + BackgroundProtocol.entryId(entry)
        val label: String get() = entry.label ?: record.manifest.label
    }

    private fun allowed(context: Context, point: String): List<Entry> {
        val grants = PluginGrants.forContext(context)
        return providersOf(context, point).filter { (record, entry) ->
            val snapshot = grants.read(record.manifest.id)
            PluginGrants.provideState(record, snapshot, point) == GrantState.GRANTED && BackgroundProtocol.entryOn(snapshot, entry)
        }.map { (record, entry) -> Entry(record, entry) }
    }

    fun services(context: Context): List<Entry> = allowed(context, BackgroundProtocol.SERVICE_POINT)

    fun schedules(context: Context): List<Entry> = allowed(context, BackgroundProtocol.SCHEDULE_POINT).filter { BackgroundProtocol.everyMs(it.entry) != null }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Brings services and the schedule job in line with what is installed and switched on; call after any change. Never blocks. */
    fun changed(context: Context) {
        val app = context.applicationContext
        scope.launch {
            syncServices(app)
            host?.schedulesWanted(app, schedules(app).isNotEmpty())
        }
    }

    // ---- services ----

    private val running = ConcurrentHashMap<String, String>()
    private val failures = ConcurrentHashMap<String, Int>()
    private val restarts = ConcurrentHashMap<String, Job>()

    /** What each service last said when it stopped on its own, for its row on the plugin page. */
    val lastStop = ConcurrentHashMap<String, String>()

    fun isRunning(key: String): Boolean = running.containsKey(key)

    private suspend fun syncServices(context: Context) {
        val wanted = services(context).associateBy { it.key }
        (running.keys - wanted.keys).forEach { key -> running.remove(key)?.let { PluginJobsCenter.cancel(it) } }
        (restarts.keys - wanted.keys).forEach { key -> restarts.remove(key)?.cancel() }
        wanted.values.filter { !running.containsKey(it.key) && !restarts.containsKey(it.key) }.forEach { start(context, it) }
        host?.servicesRunning(context, running.size)
    }

    private suspend fun start(context: Context, service: Entry) {
        val args = JSONObject().put("serviceId", BackgroundProtocol.entryId(service.entry))
        val jobId = startPluginJob(context, service.record, BackgroundProtocol.SERVICE_POINT, "run", args, "background", service.label) { result ->
            stopped(context, service, result)
        }
        if (jobId != null) running[service.key] = jobId else lastStop[service.key] = "${service.record.manifest.label} could not start it"
    }

    private fun stopped(context: Context, service: Entry, result: PluginResult) {
        // Cancelled by droidtop (switched off): already out of [running], nothing to do.
        running.remove(service.key) ?: return
        host?.servicesRunning(context, running.size)
        lastStop[service.key] = if (result.ok) "Stopped" else (result.error ?: "Stopped with an error")
        if (result.ok || !BackgroundProtocol.restartsOnFailure(service.entry)) return
        val count = (failures[service.key] ?: 0) + 1
        failures[service.key] = count
        val wait = BackgroundProtocol.restartDelayMs(count) ?: return
        restarts[service.key] = scope.launch {
            delay(wait)
            restarts.remove(service.key)
            syncServices(context)
        }
    }

    // ---- schedules ----

    private const val PREFS = "plugin_schedules"

    /**
     * Starts every schedule that is due and whose constraints hold, and waits for them. Returns how many ran. Called
     * from :app's periodic job, off the main thread.
     */
    suspend fun runDueSchedules(context: Context, charging: Boolean, unmetered: Boolean, now: Long = System.currentTimeMillis()): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val due = schedules(context).filter { schedule ->
            val every = BackgroundProtocol.everyMs(schedule.entry) ?: return@filter false
            val last = prefs.getLong(schedule.key, -1L).takeIf { it >= 0 }
            BackgroundProtocol.due(last, every, now) &&
                BackgroundProtocol.allowedNow(BackgroundProtocol.constraints(schedule.entry), charging, unmetered)
        }
        coroutineScope {
            due.map { schedule ->
                async {
                    prefs.edit().putLong(schedule.key, now).apply()
                    val done = kotlinx.coroutines.CompletableDeferred<PluginResult>()
                    val args = JSONObject().put("scheduleId", BackgroundProtocol.entryId(schedule.entry))
                    startPluginJob(context, schedule.record, BackgroundProtocol.SCHEDULE_POINT, "run", args, "schedule", schedule.label) { done.complete(it) }
                        ?: return@async
                    done.await()
                }
            }.awaitAll()
        }
        return due.size
    }

    /** When [entry] last ran, for its row; null when it never has. */
    fun lastRun(context: Context, entry: Entry): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(entry.key, -1L).takeIf { it >= 0 }
}
