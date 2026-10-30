package dev.droidtop.runtime.tasks

/**
 * Whether Android's phantom process limit ended one of droidtop's programs (docs/SPEC.md "Child processes and
 * Android's limit", Droidtop/tracker#816). Android kills the excess with signal 9, so the program only sees exit 137,
 * and says why once, in its own log: `ActivityManager: Killing PhantomProcessRecord {… <pid>:<uid>/u0a… <name>}`. Reading
 * that log needs the privileged shell (Shizuku or wireless debugging); without it the answer is unknown, never no.
 */
object PhantomKills {
    /** A process ended by signal 9 exits with 128 + 9. */
    const val KILLED_STATUS = 137

    data class Kill(val atMs: Long, val pid: Int, val uid: Int, val name: String)

    private val LINE = Regex("""^\s*(\d+)\.(\d{1,3})\s.*Killing PhantomProcessRecord \{\S+ (\d+):(\d+)/\S+ ([^}]*)\}""")

    /** One `logcat -v epoch` line, or null when it is not a phantom kill. Pure. */
    internal fun parse(line: String): Kill? {
        val m = LINE.find(line) ?: return null
        val (seconds, fraction, pid, uid, name) = m.destructured
        val millis = fraction.padEnd(3, '0').toLong()
        return Kill(seconds.toLong() * 1000 + millis, pid.toInt(), uid.toInt(), name.trim())
    }

    /** The kills of [uid]'s processes in [log] at or after [sinceMs]. Pure. */
    internal fun among(log: String, uid: Int, sinceMs: Long): List<Kill> =
        log.lineSequence().mapNotNull(::parse).filter { it.uid == uid && it.atMs >= sinceMs }.toList()

    /**
     * The phantom kills of [uid]'s processes since [sinceMs], read from Android's log through [shell]; null when the log
     * could not be read (no privileged shell). Blocking: IO only.
     */
    fun since(shell: PrivilegedShell, uid: Int, sinceMs: Long): List<Kill>? {
        if (!runCatching { shell.capabilities().shellCommand }.getOrDefault(false)) return null
        val out = runCatching {
            shell.exec(listOf("logcat", "-d", "-b", "system", "-b", "main", "-v", "epoch", "-s", "ActivityManager:I"))
        }.getOrNull()?.takeIf { it.exit == 0 } ?: return null
        return among(out.stdout, uid, sinceMs)
    }

    /**
     * What to say about a program that ended with [status]: Android's limit when the log shows it ([killed] true), a
     * likely cause on Android 12+ when it could not be read ([killed] null), or null when the status says nothing about
     * it. [where] names the row that lifts the limit. Pure.
     */
    fun explain(status: Int, killed: Boolean?, sdk: Int, where: String): String? = when {
        status != KILLED_STATUS -> null
        killed == true -> "Android ended it: too many child processes across your apps. $where can lift that limit"
        killed == null && sdk >= PhantomProcesses.FIRST_SDK ->
            "Something ended it (exit 137). Android ends extra child processes; see $where"
        else -> null
    }
}
