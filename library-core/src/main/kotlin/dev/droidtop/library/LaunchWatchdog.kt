package dev.droidtop.library

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.Display
import dev.droidtop.library.consoles.RetroArchCores
import dev.droidtop.runtime.tasks.CloseOutcome
import dev.droidtop.runtime.tasks.Fidelity
import dev.droidtop.runtime.tasks.TaskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the watchdog found wrong with a launch. */
enum class LaunchTrouble {
    /** The shell came back within seconds of the launch and the app is not open. */
    EXITED_AT_ONCE,

    /** Android reports the app's process as not responding. */
    NOT_RESPONDING,

    /** The app is no longer open, yet the screen it was sent to did not hand back. */
    GONE_WHILE_AWAY,

    /** A RetroArch launch whose process has done almost nothing since it started: a core it could not load. */
    STALLED,
}

/** A launch that went wrong, in the words the person is shown. */
data class LaunchAlert(
    val packageName: String,
    val appName: String,
    val trouble: LaunchTrouble,
    val message: String,
    /** Where droidtop wrote down what it saw, for someone who has to ask for help. */
    val logPath: String,
    /** For a stuck RetroArch launch, the core it was given unless that core is confirmed installed. */
    val retroArchCore: RetroArchCores.Need? = null,
)

/** One look at a launched app, as [LaunchWatchPolicy] reads it. */
internal data class WatchObservation(
    val elapsedMs: Long,
    /** droidtop's shell was started again after the launch began (the existing bounce signal). */
    val shellCameBack: Boolean,
    /** Android lists the app's process as not responding (read through the privileged helper; false without one). */
    val notResponding: Boolean,
    /** Whether the system's task list holds the app; null when droidtop cannot read that list (no privileged helper). */
    val taskListed: Boolean?,
    /**
     * Processor time the app's processes have used, read once at [LaunchWatchPolicy.STALL_CHECK_MS] for a RetroArch
     * launch through the privileged helper; null at every other look, and without a helper.
     */
    val cpuMs: Long? = null,
)

internal sealed interface WatchVerdict {
    data object Keep : WatchVerdict
    data object Stop : WatchVerdict

    /**
     * The app is listed and alive past [LaunchWatchPolicy.SETTLED_MS]: the task list has nothing more
     * to say, but the watch goes on for the not-responding state until [LaunchWatchPolicy.WATCH_MS],
     * because an app that hangs with nobody touching it only becomes "not responding" once a key or
     * a focus change reaches it, which may be well after it settled (console, build 1397: RetroArch's
     * ANR came 44 s after the launch).
     */
    data object Settled : WatchVerdict
    data class Trouble(val trouble: LaunchTrouble) : WatchVerdict
}

/**
 * The watchdog's whole decision, pure so it can be tested without a device
 * (docs/SPEC.md "The launch watchdog"). It claims only what droidtop can see:
 * whether the shell was started again and, when the privileged helper is
 * running, Android's not-responding state for the app and whether its task is
 * still open. A silent black window of a live, responsive app is
 * indistinguishable from a game that is simply running, so it is never claimed.
 */
internal object LaunchWatchPolicy {
    const val POLL_MS = 3_000L

    /** A shell that returns inside this long after a launch means the app did not stay open. */
    const val EXIT_WINDOW_MS = 10_000L

    /** How long a launch is given to show up in the task list before its absence counts. */
    const val APPEAR_GRACE_MS = 9_000L

    /** An app listed and still alive this long after launch is no longer checked against the task list. */
    const val SETTLED_MS = 30_000L

    /** The most the watchdog ever watches; after this a stuck app is the person's to notice. */
    const val WATCH_MS = 90_000L

    /** When a RetroArch launch's processor time is read once. */
    const val STALL_CHECK_MS = 15_000L

    /**
     * Less processor time than this by [STALL_CHECK_MS] is a RetroArch that never got going. A running game or
     * RetroArch's own menu (which it shows when content fails to load) draws every frame and uses seconds; the hung
     * GBC and N64 launches had used 0.14 s after 90 s (console, build 1649, `ps` TIME 0:00.14) while a GBA game ran at
     * 26% of a core.
     */
    const val STALL_CPU_MS = 1_000L

    fun judge(o: WatchObservation): WatchVerdict = when {
        o.notResponding -> WatchVerdict.Trouble(LaunchTrouble.NOT_RESPONDING)
        o.cpuMs != null && o.cpuMs < STALL_CPU_MS && o.elapsedMs >= STALL_CHECK_MS && !o.shellCameBack ->
            WatchVerdict.Trouble(LaunchTrouble.STALLED)
        o.shellCameBack ->
            if (o.taskListed != true && o.elapsedMs <= EXIT_WINDOW_MS) WatchVerdict.Trouble(LaunchTrouble.EXITED_AT_ONCE)
            else WatchVerdict.Stop
        o.elapsedMs >= WATCH_MS -> WatchVerdict.Stop
        o.taskListed == false && o.elapsedMs >= APPEAR_GRACE_MS -> WatchVerdict.Trouble(LaunchTrouble.GONE_WHILE_AWAY)
        o.taskListed == true && o.elapsedMs >= SETTLED_MS -> WatchVerdict.Settled
        else -> WatchVerdict.Keep
    }

    /**
     * Whether `dumpsys activity processes <package>` reports one of the package's processes as not
     * responding: ProcessErrorStateRecord.dump prints " mNotResponding=true" for such a process
     * (AOSP android13-release). Pure, for tests.
     */
    fun dumpShowsNotResponding(dump: String): Boolean = "mNotResponding=true" in dump

    /**
     * The processor time in [stats], one `/proc/<pid>/stat` line per process: utime plus stime (fields 14 and 15,
     * counted after the parenthesised name, which may hold spaces), in clock ticks of [ticksPerSecond] (USER_HZ, 100
     * on Android). Null when no line parses. Pure, for tests.
     */
    fun cpuMsFromStat(stats: String, ticksPerSecond: Long = 100): Long? {
        var ticks = 0L
        var any = false
        for (line in stats.lineSequence()) {
            val fields = line.substringAfterLast(')', "").trim().split(' ').filter { it.isNotEmpty() }
            // After the name: state is field 3, so utime (14) and stime (15) are at 11 and 12.
            val utime = fields.getOrNull(11)?.toLongOrNull() ?: continue
            val stime = fields.getOrNull(12)?.toLongOrNull() ?: continue
            ticks += utime + stime
            any = true
        }
        return if (any) ticks * 1000 / ticksPerSecond else null
    }

    /** The plain sentence for [trouble], naming the app and what to do. */
    fun message(appName: String, trouble: LaunchTrouble): String = when (trouble) {
        LaunchTrouble.EXITED_AT_ONCE ->
            "$appName closed straight after it started. Try again; if it keeps happening, check that its game file and settings are still there."
        LaunchTrouble.NOT_RESPONDING ->
            "$appName has stopped responding. You can close it and try again, or go back to droidtop."
        LaunchTrouble.GONE_WHILE_AWAY ->
            "$appName is no longer running, but its screen did not return to droidtop. Go back to droidtop and try again."
        LaunchTrouble.STALLED ->
            "$appName started but has done nothing since, so its screen stays black. Close it and try again once the cause below is fixed."
    }
}

/**
 * Started by [LaunchDisplay]'s one dispatch point for every app droidtop
 * launches. It looks at the launched app every few seconds, off the main
 * thread, for at most [LaunchWatchPolicy.WATCH_MS], and stops as soon as the
 * policy says the launch is fine or settled. A problem is published once in
 * [alert], for the shell's dialog and the notification to show, instead of
 * leaving a black screen. Nothing runs while no launch is being watched.
 */
object LaunchWatchdog {
    private const val TAG = "droidtop.LaunchWatchdog"
    private const val SHELL_ACTIVITY = "dev.droidtop.app.MainActivity"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val alertFlow = MutableStateFlow<LaunchAlert?>(null)

    /** The launch problem waiting for the person, or null. */
    val alert: StateFlow<LaunchAlert?> = alertFlow

    fun dismiss() {
        alertFlow.value = null
    }

    /** Stops watching: the launch ended on purpose (a quit), so its silence is not a problem. */
    @Synchronized
    fun cancel() {
        job?.cancel()
        job = null
    }

    /**
     * Watches [packageName], launched at [launchedAtMs]; replaces any launch still being watched.
     * [retroArchCorePath] is a RetroArch launch's LIBRETRO extra: a stuck RetroArch is most often a
     * core that is not installed, which RetroArch never reports, so the alert then says so.
     */
    @Synchronized
    fun start(context: Context, packageName: String, launchedAtMs: Long, retroArchCorePath: String? = null) {
        val appContext = context.applicationContext
        job?.cancel()
        alertFlow.value = null
        job = scope.launch {
            val appName = TaskManager.appLabel(appContext, packageName) ?: packageName
            val started = System.currentTimeMillis()
            var settled = false
            // A RetroArch launch's processor time is read once (LaunchWatchPolicy.STALL_CHECK_MS): a missing or
            // broken core leaves RetroArch black and idle, and RetroArch reports nothing (Droidtop/tracker#271).
            var stallChecked = retroArchCorePath == null
            while (isActive) {
                delay(LaunchWatchPolicy.POLL_MS)
                val elapsed = System.currentTimeMillis() - started
                val shellCameBack = LaunchDisplay.shellStartedMs >= launchedAtMs
                val cpuMs = if (!stallChecked && elapsed >= LaunchWatchPolicy.STALL_CHECK_MS) {
                    stallChecked = true
                    cpuMs(packageName)
                } else {
                    null
                }
                val observation = WatchObservation(
                    elapsedMs = elapsed,
                    shellCameBack = shellCameBack,
                    notResponding = isNotResponding(packageName),
                    taskListed = if (!settled && (shellCameBack || elapsed >= LaunchWatchPolicy.APPEAR_GRACE_MS)) {
                        taskListed(appContext, packageName)
                    } else {
                        null
                    },
                    cpuMs = cpuMs,
                )
                when (val verdict = LaunchWatchPolicy.judge(observation)) {
                    WatchVerdict.Keep -> Unit
                    WatchVerdict.Settled -> settled = true
                    WatchVerdict.Stop -> return@launch
                    is WatchVerdict.Trouble -> {
                        ScanLog.write("launch watchdog: $packageName ${verdict.trouble} after $elapsed ms")
                        val core = retroArchCorePath?.let { runCatching { RetroArchCores.suspect(packageName, it) }.getOrNull() }
                        alertFlow.value = LaunchAlert(
                            packageName,
                            appName,
                            verdict.trouble,
                            listOfNotNull(LaunchWatchPolicy.message(appName, verdict.trouble), core?.let(RetroArchCores::troubleHint))
                                .joinToString(" "),
                            ScanLog.logPath(appContext),
                            core,
                        )
                        return@launch
                    }
                }
            }
        }
    }

    /** The alert's "Close it": ends the app by the task manager's one close path, and clears the alert only when that really closed it. */
    suspend fun closeIt(context: Context, alert: LaunchAlert): CloseOutcome {
        val outcome = TaskManager.close(context.applicationContext, alert.packageName)
        if (outcome is CloseOutcome.Closed) {
            LaunchDisplay.clearRunning()
            dismiss()
        }
        return outcome
    }

    /**
     * The alert's "Get the core" for a stuck RetroArch launch ([LaunchAlert.retroArchCore]): closes the
     * stuck RetroArch by the one close path, then hands the core to [RetroArchCores.ensure], which
     * installs it without opening RetroArch where the root helper can place it, and otherwise opens
     * RetroArch for its Core Downloader (RetroArch has no command that installs a core). Returns the
     * line to show; null when there is nothing to say.
     */
    suspend fun getCore(context: Context, alert: LaunchAlert): String? {
        val need = alert.retroArchCore ?: return null
        val closed = TaskManager.close(context.applicationContext, alert.packageName)
        if (closed is CloseOutcome.Closed) LaunchDisplay.clearRunning()
        dismiss()
        return when (val outcome = RetroArchCores.ensure(context.applicationContext, need, asked = true)) {
            RetroArchCores.Outcome.Ready -> "${need.core} is installed. Start the game again."
            is RetroArchCores.Outcome.Manual -> outcome.line
            is RetroArchCores.Outcome.Failed -> outcome.line
        }
    }

    /** The alert's "Return to droidtop": brings the shell back to the built-in screen. */
    fun returnToShell(context: Context) {
        val intent = Intent().setClassName(context.packageName, SHELL_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching {
            context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle())
        }.onFailure { Log.w(TAG, "Could not bring the shell back", it) }
    }

    /**
     * Android's not-responding state for the app's processes, read by the privileged helper; false
     * without one. Not ActivityManager.getProcessesInErrorState: it returns only the caller's own
     * processes unless the caller holds DUMP (ActivityManagerService.getProcessesInErrorState,
     * `if (!hasDumpPermission && app.info.uid != callingUid) return;`, AOSP android13-release), so
     * droidtop never saw another app's ANR through it (console, build 1397: RetroArch's system
     * not-responding dialog, no alert). The shell user the helper runs as holds DUMP.
     */
    private fun isNotResponding(packageName: String): Boolean {
        val shell = TaskManager.shell
        if (!shell.capabilities().shell) return false
        val out = runCatching { shell.exec(listOf("dumpsys", "activity", "processes", packageName)) }.getOrNull()
        return out != null && out.exit == 0 && LaunchWatchPolicy.dumpShowsNotResponding(out.stdout)
    }

    /**
     * The processor time [packageName]'s processes have used, read as the privileged helper's user (the shell user can
     * read every app's `/proc/<pid>/stat`, as `ps` does); null without a helper or a running process.
     */
    private fun cpuMs(packageName: String): Long? {
        val shell = TaskManager.shell
        if (!shell.capabilities().shell) return null
        val pids = runCatching { shell.exec(listOf("pidof", packageName)) }.getOrNull()
            ?.takeIf { it.exit == 0 }?.stdout?.split(' ', '\n')?.filter { it.isNotBlank() && it.all(Char::isDigit) }
            ?.takeIf { it.isNotEmpty() } ?: return null
        val stats = runCatching { shell.exec(listOf("cat") + pids.map { "/proc/$it/stat" }) }.getOrNull() ?: return null
        return LaunchWatchPolicy.cpuMsFromStat(stats.stdout)
    }

    /** Whether the task list holds the package; null when it cannot be read exactly (no privileged helper running). */
    private suspend fun taskListed(context: Context, packageName: String): Boolean? {
        if (!TaskManager.privileges().shell) return null
        TaskManager.refresh(context)
        val snapshot = TaskManager.snapshot.value ?: return null
        return if (snapshot.fidelity == Fidelity.EXACT) snapshot.apps.any { it.packageName == packageName } else null
    }
}
