package dev.droidtop.runtime.tasks

/** The answer of a privileged `priv.packages` force-stop. */
sealed interface ForceStopResult {
    data object Stopped : ForceStopResult

    /** No running plugin provides it: nothing was tried. */
    data object NoProvider : ForceStopResult

    data class Failed(val message: String) : ForceStopResult
}

/** The output of a privileged `priv.shell` command. */
data class ShellOutput(val exit: Int, val stdout: String, val stderr: String)

/**
 * What the task manager asks of a privileged helper (Shizuku, or a root provider plugin).
 * The task manager lives below the plugin host, so :app installs the implementation
 * ([TaskManager.install]) and everything here stays a plain interface. [forceStop] and [exec] block on
 * a provider process: callers run them off the main thread. [available] is cheap and does no IPC.
 * [exec] is null when no provider could run the command.
 */
interface PrivilegedOps {
    fun available(): TaskPrivileges

    fun forceStop(packageName: String): ForceStopResult

    fun exec(argv: List<String>): ShellOutput?
}

/** No helper installed: every call is "nothing to ask". */
object NoPrivilegedOps : PrivilegedOps {
    override fun available(): TaskPrivileges = TaskPrivileges.NONE

    override fun forceStop(packageName: String): ForceStopResult = ForceStopResult.NoProvider

    override fun exec(argv: List<String>): ShellOutput? = null
}
