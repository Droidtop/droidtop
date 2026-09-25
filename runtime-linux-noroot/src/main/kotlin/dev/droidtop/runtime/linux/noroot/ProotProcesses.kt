package dev.droidtop.runtime.linux.noroot

import java.io.File

/**
 * Finds and ends the processes of a proot session or a whole container,
 * by what they carry rather than by the one `java.lang.Process` droidtop
 * holds for each session.
 *
 * That handle is not enough to stop anything. proot ignores SIGTERM
 * (vendor/proot `src/tracee/event.c`, `prepare_event_loop`: every
 * terminating signal but SIGQUIT and the fault signals is set to
 * SIG_IGN), so `Process.destroy()` did nothing, and the SIGKILL of
 * `destroyForcibly()` that followed killed proot alone: a tracer that
 * dies detaches its tracees (proot sets no PTRACE_O_EXITKILL), and sway,
 * swaybar and foot carried on untraced. The rig saw exactly that after
 * Containers > Stop and after leaving Desktop, and the next start booted
 * a second sway beside the first (dq-coordinator-23, F9). A handle also
 * dies with the app process, while the processes it started need not.
 *
 * So every proot droidtop starts, and every guest process under it,
 * carries two environment entries ([CONTAINER_VARIABLE] and
 * [SESSION_VARIABLE]): proot through its own environment, the guest
 * through the `env -i` list, which every program it starts inherits.
 * Ending a session or a container kills every process of this app's uid
 * whose `/proc/<pid>/environ` names it, and every descendant of those (a
 * program that cleared its environment is still a child of one that did
 * not), with SIGKILL, and repeats until none is left, because a process
 * can fork between a scan and its kill. SIGKILL reaches a ptrace-stopped
 * tracee, and proot exits once its last tracee has gone.
 *
 * Only this app's own processes are readable here (Android mounts /proc
 * with hidepid, and another uid's `environ` is refused anyway), so nothing
 * else on the device can match.
 */
internal class ProotProcesses(
    private val procRoot: File = File("/proc"),
    private val kill: (Int) -> Unit = { pid -> android.os.Process.sendSignal(pid, android.os.Process.SIGNAL_KILL) },
) {
    /** Every live process of [container], proot included. */
    fun ofContainer(container: String): Set<Int> = matching("$CONTAINER_VARIABLE=$container")

    /** Every live process of one session ([sessionId], as given to [sessionEnvironment]). */
    fun ofSession(sessionId: String): Set<Int> = matching("$SESSION_VARIABLE=$sessionId")

    /** Kills [container]'s processes; true when none is left. */
    fun killContainer(container: String): Boolean = killAll { ofContainer(container) }

    /** Kills one session's processes; true when none is left. */
    fun killSession(sessionId: String): Boolean = killAll { ofSession(sessionId) }

    private fun killAll(find: () -> Set<Int>): Boolean {
        val deadline = System.currentTimeMillis() + KILL_TIMEOUT_MS
        while (true) {
            val pids = find()
            if (pids.isEmpty()) return true
            if (System.currentTimeMillis() > deadline) return false
            pids.forEach { pid -> runCatching { kill(pid) } }
            Thread.sleep(KILL_POLL_MS)
        }
    }

    /** Processes whose environment holds [entry] exactly, plus all of their descendants. */
    private fun matching(entry: String): Set<Int> {
        val parents = HashMap<Int, Int>()
        val marked = HashSet<Int>()
        procRoot.list()?.forEach { name ->
            val pid = name.toIntOrNull() ?: return@forEach
            parentOf(pid)?.let { parents[pid] = it } ?: return@forEach
            if (environmentHas(pid, entry)) marked += pid
        }
        if (marked.isEmpty()) return marked
        val result = HashSet(marked)
        var grew = true
        while (grew) {
            grew = false
            for ((pid, parent) in parents) {
                if (pid !in result && parent in result) {
                    result += pid
                    grew = true
                }
            }
        }
        return result
    }

    private fun environmentHas(pid: Int, entry: String): Boolean {
        val bytes = runCatching { File(procRoot, "$pid/environ").readBytes() }.getOrNull() ?: return false
        return String(bytes, Charsets.UTF_8).split('\u0000').any { it == entry }
    }

    /** The parent pid from `/proc/<pid>/stat`, read after the command name, which may itself hold spaces and parentheses. */
    private fun parentOf(pid: Int): Int? {
        val stat = runCatching { File(procRoot, "$pid/stat").readText() }.getOrNull() ?: return null
        return parentFromStat(stat)
    }

    companion object {
        const val CONTAINER_VARIABLE = "DROIDTOP_CONTAINER"
        const val SESSION_VARIABLE = "DROIDTOP_SESSION"

        private const val KILL_TIMEOUT_MS = 5_000L
        private const val KILL_POLL_MS = 100L

        /** The two entries one session's proot and guest processes carry. */
        fun sessionEnvironment(container: String, sessionId: String): Map<String, String> =
            mapOf(CONTAINER_VARIABLE to container, SESSION_VARIABLE to sessionId)

        internal fun parentFromStat(stat: String): Int? =
            stat.substringAfterLast(')', "").trim().split(' ').getOrNull(1)?.toIntOrNull()
    }
}
