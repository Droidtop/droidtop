package dev.droidtop.runtime

/**
 * How a backend runs an interactive login shell in a container on droidtop's own terminal (docs/SPEC.md 3d, "A
 * terminal on Android"; Droidtop/tracker#909). [ContainerRuntime.interactiveShell] answers it; the terminal screen in
 * `:app` runs it. Built from the same argv and environment the backend's [ContainerRuntime.exec] uses, with
 * [ContainerTerminal.LOGIN_SHELL_COMMAND] as the command and [ContainerTerminal.SHELL_ENVIRONMENT] added, so the two
 * cannot drift apart.
 */
sealed interface InteractiveShell {
    /**
     * [argv] is started by droidtop itself, as the app, on a pseudo-terminal whose slave is its controlling terminal:
     * the non-root backend (proot), whose whole command line runs as the app anyway. [environment] is the process's
     * whole environment (nothing else is inherited) and [workingDir] its working folder. [end] ends whatever the
     * session left running, after the terminal has hung up and killed what it started; it blocks, so it runs off the
     * main thread.
     */
    class OnTerminal(
        val argv: List<String>,
        val environment: Map<String, String>,
        val workingDir: String,
        val end: () -> Unit,
    ) : InteractiveShell

    /**
     * The command is started as root through the elevated helper ([RootProcess.spawn]), which hands back pipes, not
     * a terminal: the rooted backend (droidspaces). The terminal is made inside the container instead
     * ([ContainerTerminal.onContainerTerminal]), sized once at the start, and droidtop relays its bytes. [argvFor]
     * gives the argv for a terminal [rows] by [columns] characters.
     */
    class Elevated(val argvFor: (rows: Int, columns: Int) -> List<String>) : InteractiveShell
}
