package dev.droidtop.runtime.tasks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Picture-in-picture video moves to the companion screen (docs/SPEC.md "The companion's tabs", Droidtop/tracker#430):
 * when an app on the main screen goes into picture-in-picture, its task is moved to the companion's display through
 * the helper app's shell (`am display move-stack`), and moved back to the main screen when the companion goes. An
 * app cannot move another app's task, so without a `priv.shell` provider nothing happens.
 *
 * Nothing polls while no companion shows: the companion's host runs [watch] while it is started. droidtop's
 * accessibility service, when on, [nudge]s it on every window change, and then it reads the task list only then;
 * without the service it reads every [FALLBACK_MS].
 */
object PipMover {
    const val FALLBACK_MS = 5_000L

    /** The shell command that moves [taskId] (a pinned task is its own root task) to [displayId]. */
    fun moveCommand(taskId: Int, displayId: Int): List<String> =
        listOf("am", "display", "move-stack", taskId.toString(), displayId.toString())

    /** The picture-in-picture tasks on [mainDisplay] to move to the companion's display. Pure. */
    fun toMove(tasks: List<DumpedTask>, mainDisplay: Int, companionDisplay: Int): List<DumpedTask> =
        if (mainDisplay == companionDisplay) emptyList() else tasks.filter { it.pinned && it.displayId == mainDisplay }

    /** Of the tasks moved earlier ([moved]), those still on [companionDisplay], to take back. Pure. */
    fun toMoveBack(tasks: List<DumpedTask>, moved: Set<Int>, companionDisplay: Int): List<DumpedTask> =
        tasks.filter { it.taskId in moved && it.displayId == companionDisplay }

    private val nudges = Channel<Unit>(Channel.CONFLATED)
    private val lock = Mutex()
    private val moved = HashSet<Int>()

    /** A window changed somewhere (droidtop's accessibility service): look now rather than at the next tick. */
    fun nudge() {
        nudges.trySend(Unit)
    }

    /**
     * Runs while a companion host is started: looks at the task list when nudged (or every [FALLBACK_MS] without the
     * accessibility service) and moves what went into picture-in-picture on [mainDisplay] to [companionDisplay].
     * [enabled] is read each time, so the setting takes effect at once. Returns only by cancellation.
     */
    suspend fun watch(enabled: () -> Boolean, mainDisplay: () -> Int, companionDisplay: Int) {
        while (true) {
            withTimeoutOrNull(if (dev.droidtop.runtime.keyboard.AccessibilityKeyboard.connected) Long.MAX_VALUE else FALLBACK_MS) { nudges.receive() }
            if (enabled()) check(mainDisplay(), companionDisplay)
        }
    }

    private suspend fun check(mainDisplay: Int, companionDisplay: Int) = withContext(Dispatchers.IO) {
        val shell = TaskManager.shell
        if (!runCatching { shell.capabilities().shellCommand }.getOrDefault(false)) return@withContext
        val tasks = readTasks(shell) ?: return@withContext
        lock.withLock {
            toMove(tasks, mainDisplay, companionDisplay).forEach { task ->
                val out = runCatching { shell.exec(moveCommand(task.taskId, companionDisplay)) }.getOrNull()
                if (out != null && out.exit == 0) moved += task.taskId
            }
        }
    }

    /** The companion went: what was moved to it goes back to the main screen. */
    suspend fun moveBack(mainDisplay: Int, companionDisplay: Int) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (moved.isEmpty()) return@withLock
            val shell = TaskManager.shell
            val tasks = readTasks(shell)
            if (tasks != null) {
                toMoveBack(tasks, moved, companionDisplay).forEach { task ->
                    runCatching { shell.exec(moveCommand(task.taskId, mainDisplay)) }
                }
            }
            moved.clear()
        }
    }

    private fun readTasks(shell: PrivilegedShell): List<DumpedTask>? {
        val out = runCatching { shell.exec(ActivityDump.COMMAND) }.getOrNull() ?: return null
        return if (out.exit == 0) ActivityDump.parse(out.stdout) else null
    }
}
