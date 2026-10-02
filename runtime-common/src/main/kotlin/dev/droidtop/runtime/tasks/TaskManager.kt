package dev.droidtop.runtime.tasks

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The task manager: the one place that lists what is running and ends it (docs/SPEC.md, "The task
 * manager"). The Quick Menu, the companion and Standard's home all call this; none has a path of its own.
 *
 * Every call is a suspend function that works on `Dispatchers.IO`: a provider's force-stop and its task
 * list block on its process, so nothing here may run on the main thread. [watch] is the one poll, and
 * runs only for as long as a surface that shows the list is collecting it.
 */
object TaskManager {
    private const val TAG = "droidtop.TaskManager"

    @Volatile
    var ops: PrivilegedOps = NoPrivilegedOps
        private set

    /** :app installs the plugin-backed helper once, at start. */
    fun install(ops: PrivilegedOps) {
        this.ops = ops
    }

    private fun closer(context: Context): AppCloser {
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return AppCloser(ops, killBackground = { pkg ->
            try {
                am.killBackgroundProcesses(pkg)
                true
            } catch (t: Throwable) {
                Log.d(TAG, "Couldn't ask Android to end $pkg", t)
                false
            }
        })
    }

    private val snapshotFlow = MutableStateFlow<RunningSnapshot?>(null)

    /** The last list read; null until the first [refresh]. */
    val snapshot: StateFlow<RunningSnapshot?> = snapshotFlow

    private val refreshLock = Mutex()
    private val labels = ConcurrentHashMap<String, String>()

    /** An installed app's name, cached; null when the package is not installed. A package-manager lookup, so not for the main thread's hot path. */
    fun appLabel(context: Context, packageName: String): String? =
        labels[packageName] ?: try {
            val pm = context.packageManager
            pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString().also { labels[packageName] = it }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

    /** Closes one app by the strongest path available, and says what that achieved. */
    suspend fun close(context: Context, packageName: String): CloseOutcome =
        withContext(Dispatchers.IO) {
            val outcome = closer(context).close(packageName)
            if (outcome is CloseOutcome.Closed) refreshNow(context)
            outcome
        }

    /** What the user can do right now, so a surface can say what is missing. Asks the plugin registry, so not for the main thread. */
    fun privileges(): TaskPrivileges = ops.available()

    /** Reads the list once and publishes it. */
    suspend fun refresh(context: Context) {
        withContext(Dispatchers.IO) { refreshNow(context) }
    }

    /**
     * Refreshes every [intervalMs] until cancelled. A surface runs this from the scope that owns its
     * visibility (a `LaunchedEffect` in the tab or panel that shows the list), so nothing polls while the
     * list is not on screen.
     */
    suspend fun watch(context: Context, intervalMs: Long = 4_000L) {
        while (true) {
            refresh(context)
            delay(intervalMs)
        }
    }

    /** The displays droidtop can see, built-in first. */
    fun displayIds(context: Context): List<Int> =
        (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).displays.map { it.displayId }.sorted()

    private suspend fun refreshNow(context: Context): RunningSnapshot =
        refreshLock.withLock {
            load(context.applicationContext).also { snapshotFlow.value = it }
        }

    private fun load(context: Context): RunningSnapshot {
        val hidden = TaskPolicy.hiddenFromList(context.packageName, homePackages(context))
        val label: (String) -> String? = { appLabel(context, it) }
        val fromLedger = { note: String ->
            RunningSnapshot(RunningListing.fromLedger(LaunchLedger.entries(), hidden, label), Fidelity.LAUNCHED_ONLY, note)
        }
        if (!ops.available().shell) {
            return fromLedger("Without the Shizuku plugin droidtop can list only the apps it opened itself, and cannot tell which of them you have since closed.")
        }
        val out = try {
            ops.exec(ActivityDump.COMMAND)
        } catch (t: Throwable) {
            Log.d(TAG, "The task list could not be read", t)
            null
        }
        if (out != null && out.exit == 0 && out.stdout.contains("Display #")) {
            return RunningSnapshot(RunningListing.fromDump(ActivityDump.parse(out.stdout), hidden, label), Fidelity.EXACT)
        }
        return fromLedger("The privileged helper could not give droidtop the system's task list, so only the apps droidtop opened are listed.")
    }

    /** Every installed home app: not something a task manager lists or clears. */
    private fun homePackages(context: Context): Set<String> =
        context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            .mapTo(HashSet()) { it.activityInfo.packageName }
}
