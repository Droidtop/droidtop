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
 * What the app asks of a privileged helper (Shizuku or Sui, through the official provider plugin).
 * The task manager lives below the plugin host, so :app installs the implementation
 * ([TaskManager.install]) and everything here stays a plain interface. [forceStop] and [exec] block on
 * a provider process: callers run them off the main thread. [available] is cheap and does no IPC.
 * [exec] is null when no provider could run the command.
 */
interface PrivilegedShell {
    /** Capabilities are cheap to inspect and must not perform provider IPC. */
    fun capabilities(): TaskPrivileges = available()

    /** Compatibility entry point for existing provider adapters. */
    fun available(): TaskPrivileges = TaskPrivileges.NONE

    fun forceStop(packageName: String): ForceStopResult

    fun exec(argv: List<String>): ShellOutput?

    /** Grant a runtime permission to an installed package when the provider supports it. */
    fun grantPermission(packageName: String, permission: String): Boolean = false

    /**
     * Starts [argv] as a long-lived process of the helper, its standard
     * streams the returned [Process]'s, for a command that outlives one call
     * or takes input ([dev.droidtop.runtime.RootProcess], the rooted desktop
     * stack). Null when this helper cannot start one. Blocks on the helper:
     * not for the main thread.
     */
    fun spawn(argv: List<String>): Process? = null
}

/** No helper installed: every call is "nothing to ask". */
object NoPrivilegedOps : PrivilegedShell {
    override fun available(): TaskPrivileges = TaskPrivileges.NONE

    override fun forceStop(packageName: String): ForceStopResult = ForceStopResult.NoProvider

    override fun exec(argv: List<String>): ShellOutput? = null
}
