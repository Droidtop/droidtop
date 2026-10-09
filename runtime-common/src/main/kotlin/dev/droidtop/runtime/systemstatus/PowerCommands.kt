package dev.droidtop.runtime.systemstatus

import dev.droidtop.runtime.tasks.PrivilegedShell

/**
 * The power rows of the Quick Menu's System section (docs/SPEC.md 7f, "Sleep and return to game"). A plain
 * app can do none of them, so each is the shell's own command through the privilege provider
 * ([PrivilegedShell.exec], Shizuku or Sui); without a provider the rows that need one are not drawn.
 */
enum class PowerAction(val argv: List<String>) {
    /** The screen-off key: Android's own `goToSleep`, so the game is suspended in place and wake returns to it. */
    SLEEP(listOf("input", "keyevent", "KEYCODE_SLEEP")),
    POWER_OFF(listOf("svc", "power", "shutdown")),
    RESTART(listOf("svc", "power", "reboot")),
    ;

    /** Runs the action; "" on success, otherwise the words for the row. Blocks on the provider: not for the main thread. */
    fun run(shell: PrivilegedShell): String {
        val out = shell.exec(argv)
        return if (out != null && out.exit == 0) "" else "Failed: the helper could not do it"
    }
}
