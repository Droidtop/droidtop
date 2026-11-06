package dev.droidtop.app.terminal

/**
 * The pure parts of starting a container shell on droidtop's terminal ([ContainerShellSession]): the environment in
 * the form the pseudo-terminal's JNI takes, and the relay a shell that comes over pipes runs on it.
 */
object TerminalLaunch {
    /** How many lines the terminal keeps above the screen. */
    const val SCROLLBACK_ROWS = 5_000

    /**
     * [environment] as `NAME=value` lines, the process's whole environment (Termux's JNI clears the inherited one).
     * A name that is empty or holds `=` cannot be one and is left out; so is one holding a NUL, as is a value with one.
     */
    fun environmentLines(environment: Map<String, String>): Array<String> =
        environment.filter { (k, v) -> k.isNotEmpty() && '=' !in k && '\u0000' !in k && '\u0000' !in v }
            .map { (k, v) -> "$k=$v" }.toTypedArray()

    /** Android's own shell, which runs the relay. */
    const val ANDROID_SHELL = "/system/bin/sh"

    /** The relay's environment: Android's own tools only. */
    val RELAY_ENVIRONMENT: Map<String, String> = linkedMapOf("PATH" to "/system/bin:/system/xbin")

    /**
     * What runs on the pseudo-terminal when the shell itself comes over pipes ([dev.droidtop.runtime.InteractiveShell.Elevated]):
     * the local terminal goes raw, so every key (Ctrl+C, the arrows, Tab) reaches the container's own terminal as
     * typed and only that one echoes; the container's output, which droidtop writes into [fromShell], is copied to
     * the screen, and what is typed is copied into [toShell], which droidtop reads. It ends, with status 0, when
     * droidtop closes [fromShell] after the shell has ended. A background command's input is /dev/null in a
     * non-interactive shell, so the terminal's is kept on descriptor 3 for the copy that reads it.
     */
    fun relayArgv(fromShell: String, toShell: String): List<String> = listOf(
        ANDROID_SHELL,
        "-c",
        "stty raw -echo 2>/dev/null; exec 3<&0; cat \"\$1\" & r=\$!; cat <&3 > \"\$2\" & w=\$!; " +
            "wait \$r; kill \$w 2>/dev/null; exit 0",
        "relay",
        fromShell,
        toShell,
    )
}
