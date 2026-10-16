package dev.droidtop.library

import android.content.Context
import android.util.Log
import dev.droidtop.runtime.tasks.Fidelity
import dev.droidtop.runtime.tasks.LaunchLedger
import dev.droidtop.runtime.tasks.TaskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** One look at the running game's app, as [SessionLiveness] reads it. */
internal data class SessionObservation(
    /** How long ago the game's app was last started. */
    val ageMs: Long,
    /** Android marks the app force-stopped, or it is gone from the device. */
    val packageGone: Boolean,
    /** Whether the system's task list holds the app; null when droidtop cannot read that list (no privileged helper). */
    val taskListed: Boolean?,
    /** The game is a screen of droidtop's own (a Windows game): the task list hides droidtop, and the screen reports its own end. */
    val ownPackage: Boolean,
)

/**
 * When a running-game session is over, with no Android in it so it is tested (docs/SPEC.md "The running game's
 * session"). Only what droidtop can see counts: the force-stopped flag (Android says it to any app), and with the
 * privileged helper the system's task list, where an app whose last task is gone has ended however it ended (crash,
 * Cancel on Enginehost's warning, the person closing it); for Enginehost's games, its own answer to the same question,
 * which needs no helper. For any other app without a helper there is no evidence of a death, so no claim is made;
 * Quit settles it ([Library.quitRunning]).
 */
internal object SessionLiveness {
    /** How often a young session is looked at: a launch that was cancelled or crashed dies in its first minute. */
    const val FAST_MS = 3_000L

    /** How often an established session is looked at: a game running for an hour is not worth a task-list read every 3 s. */
    const val SLOW_MS = 15_000L

    /** How long after a start the session counts as young. */
    const val FAST_WINDOW_MS = 60_000L

    /** An app is given this long to show up in the task list after it was started (the launch watchdog's grace). */
    const val APPEAR_GRACE_MS = LaunchWatchPolicy.APPEAR_GRACE_MS

    /** Looks in a row that must miss the app before the session ends, so one odd read cannot end a game. */
    const val MISSES_TO_END = 2

    fun intervalMs(ageMs: Long): Long = if (ageMs < FAST_WINDOW_MS) FAST_MS else SLOW_MS

    /**
     * The count of looks in a row that found the app gone, after this one. Gone is missing from the task list past the
     * grace, or Android's stopped flag while the task list does not hold the app. A listed task outweighs the flag: a
     * launch into an emulator process that outlived a force-stop runs with the flag still set (rig, droidtop
     * 0.2.0-dev.1931: 007 relaunched into NetherSX2's background process after Kill ran untracked, no Game tab, no Kill).
     */
    fun misses(o: SessionObservation, before: Int): Int {
        if (o.ownPackage) return 0
        val missingFromList = o.taskListed == false && o.ageMs >= APPEAR_GRACE_MS
        val stopped = o.packageGone && o.taskListed != true
        return if (missingFromList || stopped) before + 1 else 0
    }

    /** Ended only on [MISSES_TO_END] looks in a row: one odd read, of either signal, never ends a game. */
    fun ended(misses: Int): Boolean = misses >= MISSES_TO_END
}

/**
 * The one watch on a running game's lifetime. While [LaunchDisplay.running] names a game it looks at the game's app
 * ([SessionLiveness]) and, when the evidence says it is gone, ends the session ([LaunchDisplay.clearRunning]), which
 * every surface (the left menu's Resume, the Quick Menu's Game section, the companion's Game tab and Now card, the
 * game-awake lock) already observes. It replaces three separate force-stopped polls, none of which saw a launch that
 * was cancelled or crashed (Droidtop/tracker#289). Off the main thread; nothing runs while no game is running.
 */
object SessionMonitor {
    private const val TAG = "droidtop.SessionMonitor"
    private var started = false

    /** Starts watching, once per process. */
    @Synchronized
    fun install(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            LaunchDisplay.running.collectLatest { session ->
                if (session != null) watch(app, session)
            }
        }
    }

    private suspend fun watch(app: Context, session: LaunchDisplay.RunningSession) {
        var misses = 0
        while (true) {
            val packageName = LaunchDisplay.runningPackageName
            val launchedAt = packageName?.let { pkg -> LaunchLedger.entries().firstOrNull { it.packageName == pkg }?.atMs } ?: session.sinceEpochMs
            val age = System.currentTimeMillis() - launchedAt
            delay(SessionLiveness.intervalMs(age))
            // Null until the launch is dispatched (the screen question may still be open).
            val pkg = LaunchDisplay.runningPackageName ?: continue
            val observation = SessionObservation(
                ageMs = System.currentTimeMillis() - launchedAt,
                packageGone = LaunchDisplay.isRunningPackageForceStopped(app),
                taskListed = taskListed(app, pkg),
                ownPackage = pkg == app.packageName,
            )
            misses = SessionLiveness.misses(observation, misses)
            if (SessionLiveness.ended(misses)) {
                Log.i(TAG, "$pkg is gone (stopped=${observation.packageGone}, task list misses=$misses): the session ends")
                // The launch watchdog goes on: a game gone this young failed to start, and the watchdog is what says so
                // (rig, 1931: Vita3K and Eden exited at once on the second display and nothing was shown).
                LaunchDisplay.clearRunning(stopWatching = false)
                return
            }
        }
    }

    /**
     * Whether [packageName] still holds a task: from the system's task list with the privileged helper; without one, from
     * Enginehost itself for Enginehost's games ([EnginehostCapabilities.holdsTask]), the one app that can say; null when
     * nothing can say.
     */
    private suspend fun taskListed(app: Context, packageName: String): Boolean? {
        if (!TaskManager.privileges().shell) {
            return if (packageName == EngineHost.PACKAGE_NAME) EnginehostCapabilities.holdsTask(app) else null
        }
        val snapshot = TaskManager.snapshotWithin(app, maxAgeMs = 2_000L)
        return if (snapshot.fidelity == Fidelity.EXACT) snapshot.apps.any { it.packageName == packageName } else null
    }
}
