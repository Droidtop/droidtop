package dev.droidtop.library.settings

import android.content.Context
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive

/**
 * "Rescan library", as one action every screen that offers it runs: Gaming's
 * Settings, Game folders (in Gaming, in the launcher's settings and inside
 * the Launcher's Games grid) and the Games section's options menu.
 *
 * The library itself lives in :app, which this module cannot depend on, so
 * :app registers [handler] at process start. The handler walks the library
 * again, waits for the walk to finish and returns the sentence the person
 * reads. The row used to relaunch the Gaming shell with a rescan flag and
 * say nothing at all, so a rescan could not be told from a tap that missed
 * (rig, dq-shell2-02).
 *
 * A rescan can be stopped: [cancel] ends the one in flight, and the row
 * calls it when it is selected again while the rescan runs, so a walk over
 * a slow card is never something a person has to sit out
 * (Droidtop/tracker#275).
 *
 * "Rescan PC game folders" ([runPcFolders], Droidtop/tracker#397 slice E) is
 * the same action over the PC walks only (PC and engine games, never the ROM
 * walk): Game sources > Folders and PC Games' list options offer it. One
 * rescan runs at a time, whichever it is, and either row cancels it.
 */
object LibraryRescan {
    @Volatile
    var handler: (suspend (Context, (String) -> Unit) -> String)? = null

    /** The PC-only walk, registered by `:app` beside [handler]; its answer says how many games are new. */
    @Volatile
    var pcFoldersHandler: (suspend (Context, (String) -> Unit) -> String)? = null

    private val active = AtomicReference<Deferred<String>?>(null)

    /** Whether a rescan started by [run] is in flight. */
    val isRunning: Boolean get() = active.get()?.isActive == true

    /** Stops the rescan in flight; false when there was none. [run] then returns "Rescan cancelled.". */
    fun cancel(): Boolean {
        val running = active.get()?.takeIf { it.isActive } ?: return false
        running.cancel()
        return true
    }

    private val detached = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * A rescan that belongs to the library and not to any screen: returns at
     * once, and the walk runs to its end whether or not the caller's screen
     * is still there. For a change nobody can name the files of (a store's
     * sign-in or sync); a change whose files are known is a
     * [LibraryPaths] report, which walks nothing.
     */
    fun requestInBackground(context: Context) {
        val app = context.applicationContext
        detached.launch { runCatching { run(app) {} } }
    }

    suspend fun run(context: Context, onStatus: (String) -> Unit): String = runWith(handler, context, onStatus)

    /** Walks the PC game folders only (and reads the stores' rows again), never a ROM folder. */
    suspend fun runPcFolders(context: Context, onStatus: (String) -> Unit): String = runWith(pcFoldersHandler, context, onStatus)

    private suspend fun runWith(
        handler: (suspend (Context, (String) -> Unit) -> String)?,
        context: Context,
        onStatus: (String) -> Unit,
    ): String {
        val rescan = handler ?: return "The library cannot be rescanned from here."
        return coroutineScope {
            val answer = async { rescan(context, onStatus) }
            active.set(answer)
            try {
                answer.await()
            } catch (cancelled: CancellationException) {
                // Our own caller going away is a cancellation to pass on;
                // only the rescan having been cancelled is an answer.
                if (isActive) "Rescan cancelled." else throw cancelled
            } finally {
                active.compareAndSet(answer, null)
            }
        }
    }
}
