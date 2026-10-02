package dev.droidtop.shell.gamepad

import dev.droidtop.library.consoles.NoEmulatorInstalled

/**
 * Maps launch failures to one short sentence (Droidtop/tracker#171).
 * Raw exception details stay in the shell log.
 */
object LaunchFailureMessage {
    /**
     * The plain sentence for [cause]. [game] names the entry the person
     * pressed A on, when the failing path knows it; the display hook
     * does not, and passes null.
     */
    fun userMessage(game: String?, cause: Throwable?): String = when {
        cause is NoEmulatorInstalled ->
            "No ${cause.systemName} emulator is installed yet."
        else -> if (game.isNullOrBlank()) {
            "The game couldn't be started."
        } else {
            "\"$game\" couldn't be started."
        }
    }
}
