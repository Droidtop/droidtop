package dev.droidtop.app.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.droidtop.app.PlaceLinks
import dev.droidtop.app.R
import dev.droidtop.library.integrations.AppCatalogs
import dev.droidtop.library.integrations.AppPackages
import dev.droidtop.library.integrations.PluginCatalog
import dev.droidtop.library.integrations.PluginCatalogSources
import dev.droidtop.library.integrations.PluginRepoUpdates
import dev.droidtop.library.settings.Place
import dev.droidtop.library.stores.StoreAutoUpdates
import dev.droidtop.net.DownloadPolicy
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.pluginhost.UserOriginKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What each kind of update does once a check finds one (docs/SPEC.md 10b "The Updates page", Droidtop/tracker#570; the
 * owner, 2026-10-16: "WE don't have an option to ask the user if they want to install an update (that's an issue)").
 * Ask before installing lists it in Updates and says so in one notification; Install automatically installs it after
 * the scheduled check. Plugins and store games keep the settings they already had ("Update plugins from these
 * repositories automatically", on; "Keep installed store games up to date", off), so nothing changes for them by
 * default; droidtop and Android apps ask unless the person says otherwise.
 */
object UpdateKinds {
    enum class Kind(val label: String) { DROIDTOP("droidtop"), PLUGINS("Plugins"), APPS("Android apps"), GAMES("Store games") }

    enum class Policy(val label: String) { ASK("Ask before installing"), AUTO("Install automatically") }

    private const val PREFS = "update_kinds"

    /** Reads preferences (and, for games, the download rules): not for the main thread. */
    fun policy(context: Context, kind: Kind): Policy = when (kind) {
        Kind.PLUGINS -> if (PluginRepoUpdates.autoUpdate(context)) Policy.AUTO else Policy.ASK
        Kind.GAMES -> {
            DownloadPolicy.load(context)
            if (DownloadPolicy.settings.value.autoUpdateDefault) Policy.AUTO else Policy.ASK
        }
        else -> context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(kind.name, null)
            ?.let { stored -> Policy.entries.firstOrNull { it.name == stored } } ?: Policy.ASK
    }

    fun setPolicy(context: Context, kind: Kind, policy: Policy) {
        when (kind) {
            Kind.PLUGINS -> PluginRepoUpdates.setAutoUpdate(context, policy == Policy.AUTO)
            Kind.GAMES -> DownloadPolicy.update(context) { it.copy(autoUpdateDefault = policy == Policy.AUTO) }
            else -> context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(kind.name, policy.name).apply()
        }
    }
}

/**
 * The one check of every kind of update (Droidtop/tracker#570): the Updates page's Check now runs it, and so does a job
 * on droidtop's own schedule ("Check for updates": every day by default), which replaced the check that ran only when
 * droidtop's process started, seldom for a launcher. After a scheduled check each kind does what [UpdateKinds] says.
 */
object UpdateChecks {
    private const val PREFS = "updates_page"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val JOB_ID = 0x555044 // "UPD"
    private const val CHANNEL_ID = "updates_available"
    private const val NOTIFICATION_ID = 0x555044

    fun lastCheck(context: Context): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0L).takeIf { it > 0 }

    /** The check as a job in the one jobs center, so it shows where every running thing does (Home's activity spot). */
    const val JOB_KIND = dev.droidtop.library.integrations.HappeningNow.UPDATE_CHECK
    private const val JOB_TITLE = "Check for updates"

    /** Once, at process start (SettingsCatalogInitProvider). */
    fun register(context: Context) {
        val app = context.applicationContext
        dev.droidtop.pluginhost.PluginJobsCenter.registerNative(JOB_KIND) { _, _, report ->
            report(-1, "Starting", null)
            checkNow(app) { line -> report(-1, line, null) }
        }
    }

    /**
     * The check, as a job (Droidtop/tracker#742): starts it, or follows the one already running, telling [onStatus] its
     * line, and returns how it ended. Both the Updates page's Check now and the scheduled pass run it this way.
     */
    suspend fun checkAll(context: Context, onStatus: (String) -> Unit): String {
        val jobs = dev.droidtop.pluginhost.PluginJobsCenter
        val jobId = jobs.entries().value.firstOrNull { it.nativeKind == JOB_KIND && !it.done }?.jobId
            ?: jobs.startNative(null, JOB_KIND, JOB_TITLE, owner = "Updates", pausable = false)
            ?: return checkNow(context, onStatus)
        val end = jobs.follow(jobId, onStatus)
        return when {
            end == null -> "Check cancelled"
            end.result?.ok == false -> end.result?.error ?: "The check failed"
            else -> end.result?.values?.get("summary") ?: "Checked"
        }
    }

    /**
     * Asks every kind's source: droidtop's newest build (recorded, never installed here), every plugin catalog, the
     * trusted plugin repositories (which install by themselves when Plugins installs automatically), every app catalog,
     * the platform databases and the stores' automatic updates. Blocking network calls, off the main thread.
     */
    private suspend fun checkNow(context: Context, onStatus: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val metered = isMetered(app)
        onStatus("Checking droidtop...")
        val line = AppSelfUpdate.check(app)
        runCatching { dev.droidtop.library.consoles.PlatformDatabases.refresh(app) }
        onStatus("Checking the plugin catalogs...")
        PluginCatalogSources.all(app).forEach { source -> runCatching { PluginCatalog.refresh(app, source) } }
        runCatching {
            PluginRepoUpdates.runDue(app, metered) { repo, result -> PluginRepoUpdateNotification.show(app, repo, result) }
        }
        val providers = AppCatalogs.providers(app)
        if (providers.isNotEmpty()) onStatus("Checking the app catalogs...")
        providers.forEach { record -> runCatching { AppCatalogs.refresh(app, record, null) {} } }
        // Store games: the stores' own sweep, which updates what the person keeps up to date (Download rules).
        StoreAutoUpdates.request(app)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        line
    }

    /**
     * Checks now when the last check is older than [STALE_MS], for a place that shows what can update: the Updates
     * list, Settings > About and a plugin's page (Droidtop/tracker#792). The list read what the last scheduled check
     * had fetched, so a plugin published since (F95zone 0.7.1 on the console, a day after the daily check) did not show,
     * and the owner found "no plugin, or DROIDTOP even, update path in the UI". Looking is asking: the check starts as
     * the place opens and the place redraws as it ends. Not on a metered network when the person asked for Wi-Fi only.
     * At most one started per [STALE_MS], so a place that redraws does not start another. Returns at once.
     */
    fun refreshIfStale(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val last = maxOf(lastCheck(app) ?: 0L, lastStartedMs)
        if (!isStale(last, now)) return
        if (AppSelfUpdate.unmeteredOnly(app) && isMetered(app)) return
        lastStartedMs = now
        dev.droidtop.runtime.util.AppScopes.io.launch { checkAll(app) {} }
    }

    /** Whether a check that ended (or started) at [lastMs] is too old to show at [nowMs]. Pure. */
    internal fun isStale(lastMs: Long, nowMs: Long): Boolean = nowMs - lastMs >= STALE_MS

    /** How old the last check may be before a place that shows updates checks again. */
    internal const val STALE_MS = 15 * 60 * 1000L

    @Volatile private var lastStartedMs = 0L

    /** The scheduled pass: the check, then each kind's policy; what is left to ask about is one notification. */
    suspend fun scheduled(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        // Android may run a periodic job early, and the network may have turned metered since it was scheduled.
        val due = AppSelfUpdate.mayCheck(
            forced = false,
            frequency = AppSelfUpdate.frequency(app),
            lastAttemptMs = AppSelfUpdate.lastAttempt(app) ?: 0L,
            nowMs = System.currentTimeMillis(),
            unmeteredOnly = AppSelfUpdate.unmeteredOnly(app),
            metered = isMetered(app),
        )
        if (!due) return@withContext
        checkAll(app) {}
        var asking = 0
        if (AppSelfUpdate.newerSeenVersionName(app) != null) {
            // Installing droidtop always ends in Android's own confirmation; Install automatically fetches it first.
            if (UpdateKinds.policy(app, UpdateKinds.Kind.DROIDTOP) == UpdateKinds.Policy.AUTO) UpdateNow.start(app) else asking++
        }
        val plugins = PluginCatalog.offersFor(
            PluginStore.installed(app), PluginCatalog.listings(app), UserOriginKeys.load(UserOriginKeys.storeFile(app)),
        )
        if (plugins.isNotEmpty()) {
            if (UpdateKinds.policy(app, UpdateKinds.Kind.PLUGINS) == UpdateKinds.Policy.AUTO) {
                runCatching { PluginCatalog.updateAll(app, {}) }
            } else {
                asking += plugins.size
            }
        }
        val apps = AppCatalogs.updates(AppCatalogs.cachedOffers(app), AppPackages.allInstalledFacts(app), app.packageName)
            .filterIsInstance<AppCatalogs.UpdateState.Available>()
        if (apps.isNotEmpty()) {
            if (UpdateKinds.policy(app, UpdateKinds.Kind.APPS) == UpdateKinds.Policy.AUTO) {
                apps.forEach { state ->
                    val record = AppCatalogs.providers(app).firstOrNull { it.manifest.id == state.pluginId } ?: return@forEach
                    runCatching { AppCatalogs.install(app, record, state.offer.id, state.offer.versionCode, state.offer.name) {} }
                }
            } else {
                asking += apps.size
            }
        }
        notifyAsking(app, asking)
    }

    /**
     * Keeps the scheduled job in step with "Check for updates" and "Only on Wi-Fi": every day, week or month, on any
     * network or only an unmetered one, none at all on Never. Called when droidtop's process starts (the job is not
     * persisted across reboots) and when either setting changes.
     */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return
        val frequency = AppSelfUpdate.frequency(app)
        if (frequency == AppSelfUpdate.Frequency.OFF) {
            scheduler.cancel(JOB_ID)
            return
        }
        val network = if (AppSelfUpdate.unmeteredOnly(app)) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
        val pending = scheduler.getPendingJob(JOB_ID)
        if (pending != null && pending.intervalMillis == frequency.intervalMs && pending.networkType == network) return
        scheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(app, UpdateCheckJobService::class.java))
                .setPeriodic(frequency.intervalMs)
                .setRequiredNetworkType(network)
                .setPersisted(false)
                .build(),
        )
    }

    /** One notification for what waits to be asked, replaced by the next check's; none when nothing waits. */
    private fun notifyAsking(context: Context, count: Int) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (count <= 0) {
                manager.cancel(NOTIFICATION_ID)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                context, 0, PlaceLinks.intent(context, Place.UPDATES),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            manager.notify(
                NOTIFICATION_ID,
                Notification.Builder(context, CHANNEL_ID)
                    .setContentTitle(if (count == 1) "1 update is ready" else "$count updates are ready")
                    .setContentText("Open Updates to install them")
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build(),
            )
        }
    }

    private fun isMetered(context: Context): Boolean =
        context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false
}

/** Runs [UpdateChecks.scheduled] when Android's job scheduler says it is time. */
class UpdateCheckJobService : JobService() {
    private val scope = dev.droidtop.runtime.util.AppScopes.io
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            runCatching { UpdateChecks.scheduled(applicationContext) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }
}
