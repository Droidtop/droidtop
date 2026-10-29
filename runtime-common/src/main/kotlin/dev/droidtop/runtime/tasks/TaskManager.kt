package dev.droidtop.runtime.tasks

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.util.Log
import dev.droidtop.runtime.util.guarded
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
    var shell: PrivilegedShell = NoPrivilegedOps
        private set

    /** :app installs the plugin-backed helper once, at start. */
    fun install(shell: PrivilegedShell) {
        this.shell = shell
    }

    private fun closer(context: Context): AppCloser {
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return AppCloser(shell, killBackground = { pkg ->
            guarded(TAG, "Asking Android to end $pkg", false) {
                am.killBackgroundProcesses(pkg)
                true
            }
        }, endEnginehostGame = { EnginehostEndGame.request(context.applicationContext) }, sendToBackground = { pkg -> sendToBackground(context.applicationContext, pkg) })
    }

    /** How long the Home press is given to take the app off the screen before the background kill, which spares a visible app. */
    private const val BACKGROUND_SETTLE_MS = 1_200L

    /**
     * Without a helper the only way to end an app is [ActivityManager.killBackgroundProcesses], which leaves an app that
     * is on screen alone (console, build 1773: RetroArch kept running after Kill). So the app droidtop opened is first put
     * in the background the way the person would, by a Home press on the screen it is on, and given a moment to stop.
     * Does nothing for an app droidtop did not open (its screen is unknown) or for droidtop itself. Blocks: IO thread only.
     */
    private fun sendToBackground(context: Context, packageName: String) {
        if (packageName == context.packageName) return
        val display = LaunchLedger.entries().firstOrNull { it.packageName == packageName }?.displayId ?: return
        // Home on that screen, to the mode in use rather than the default one: ending a game from Desktop landed in
        // Gaming (Droidtop/tracker#639). The Home activities still route a companion screen to the companion.
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(dev.droidtop.library.settings.Modes.EXTRA_MODE_IN_USE, true)
        try {
            context.startActivity(home, ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle())
            Thread.sleep(BACKGROUND_SETTLE_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            Log.d(TAG, "Couldn't put $packageName in the background", t)
        }
    }

    private val snapshotFlow = MutableStateFlow<RunningSnapshot?>(null)

    /** The last list read; null until the first [refresh]. */
    val snapshot: StateFlow<RunningSnapshot?> = snapshotFlow

    private val refreshLock = Mutex()

    @Volatile
    private var snapshotAtMs = 0L
    private val labels = ConcurrentHashMap<String, String>()

    /** An installed app's name, cached; null when the package is not installed. A package-manager lookup, so not for the main thread's hot path. */
    fun appLabel(context: Context, packageName: String): String? =
        labels[packageName] ?: try {
            val pm = context.packageManager
            pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString().also { labels[packageName] = it }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

    /** Kills one app: a force-stop at once, or a plain refusal when nothing can force-stop ([AppCloser.kill]). */
    suspend fun kill(context: Context, packageName: String): CloseOutcome =
        withContext(Dispatchers.IO) {
            val outcome = closer(context).kill(packageName)
            if (outcome.sessionOver) refreshNow(context)
            outcome
        }

    /** Closes one app by the strongest path available, and says what that achieved. */
    suspend fun close(context: Context, packageName: String): CloseOutcome =
        withContext(Dispatchers.IO) {
            val outcome = closer(context).close(packageName)
            if (outcome.sessionOver) refreshNow(context)
            outcome
        }

    /**
     * Every app Clear all would close right now: the list as the system gives it, minus [protectedNow] and
     * whatever is open on [keepDisplays]. Reads fresh, so the count a confirm shows is the count that will be closed.
     */
    suspend fun clearAllTargets(context: Context, keepDisplays: Set<Int> = emptySet()): List<RunningApp> =
        withContext(Dispatchers.IO) {
            TaskPolicy.clearAllTargets(refreshNow(context).apps, protectedNow(context), keepDisplays)
        }

    /** Closes [targets] (from [clearAllTargets]) one after another, re-reads the list, and says what came of it. */
    suspend fun clearAll(context: Context, targets: List<RunningApp>): ClearAllSummary =
        withContext(Dispatchers.IO) {
            val summary = closer(context).closeAll(targets.map { it.packageName })
            refreshNow(context)
            summary
        }

    /** The packages Clear all leaves alone: see [TaskPolicy.protectedPackages]. */
    fun protectedNow(context: Context): Set<String> =
        TaskPolicy.protectedPackages(context.packageName, homePackages(context), ProtectedApps.get(context))

    /** What the user can do right now, so a surface can say what is missing. Asks the plugin registry, so not for the main thread. */
    fun privileges(): TaskPrivileges = shell.capabilities()

    /** The list as it was read within [maxAgeMs], else a fresh read: two watchers a few seconds apart then share one task dump. */
    suspend fun snapshotWithin(context: Context, maxAgeMs: Long): RunningSnapshot =
        withContext(Dispatchers.IO) {
            val current = snapshotFlow.value
            if (current != null && System.currentTimeMillis() - snapshotAtMs <= maxAgeMs) current else refreshNow(context)
        }

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
            load(context.applicationContext).also {
                snapshotFlow.value = it
                snapshotAtMs = System.currentTimeMillis()
            }
        }

    private fun load(context: Context): RunningSnapshot {
        val hidden = TaskPolicy.hiddenFromList(context.packageName, homePackages(context))
        val label: (String) -> String? = { appLabel(context, it) }
        // A force-stopped app is gone whoever stopped it (Settings, am force-stop, a provider), and Android
        // says so to any app; the ledger forgets it, so it is not listed until droidtop opens it again.
        val stopped: (String) -> Boolean = { pkg -> isStopped(context, pkg).also { if (it) LaunchLedger.forget(pkg) } }
        val fromLedger = { note: String ->
            RunningSnapshot(RunningListing.fromLedger(LaunchLedger.entries(), hidden, label, stopped), Fidelity.LAUNCHED_ONLY, note)
        }
        if (!shell.capabilities().listTasks) {
            return fromLedger("Without More controls droidtop can list only the apps it opened itself, and cannot tell when one of them closed by itself.")
        }
        val out = guarded(TAG, "Reading the task list", null) {
            shell.exec(ActivityDump.COMMAND)
        }
        if (out != null && out.exit == 0 && out.stdout.contains("Display #")) {
            return RunningSnapshot(RunningListing.fromDump(ActivityDump.parse(out.stdout), hidden, label), Fidelity.EXACT)
        }
        return fromLedger("Android did not give droidtop its task list, so only the apps droidtop opened are listed.")
    }

    /**
     * Whether Android holds [packageName] in the stopped state (`ApplicationInfo.FLAG_STOPPED`): set by a
     * force-stop and cleared when the app is next started, and readable without privilege. It is the one
     * close signal a normal app gets about another app; an app that ended by itself or was reclaimed for
     * memory is not stopped, so the ledger still cannot see those (docs/SPEC.md "The task manager").
     */
    private fun isStopped(context: Context, packageName: String): Boolean = try {
        (context.packageManager.getApplicationInfo(packageName, 0).flags and ApplicationInfo.FLAG_STOPPED) != 0
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Every installed home app: not something a task manager lists or clears. */
    private fun homePackages(context: Context): Set<String> =
        TaskPolicy.homeApps(
            context.packageManager
                .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                .map { it.activityInfo.packageName to it.priority },
        )
}
