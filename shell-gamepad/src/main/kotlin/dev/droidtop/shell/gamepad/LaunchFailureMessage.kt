package dev.droidtop.shell.gamepad

import dev.droidtop.library.consoles.EmulatorNeedsFileAccess
import dev.droidtop.library.consoles.NoEmulatorInstalled

/**
 * The one mapping from a launch failure's raw cause to the plain sentence
 * the shell's failure dialog shows (Droidtop/tracker#171). The cause's
 * detail -- exception class, stack, raw message -- stays only in the
 * shell's Log.e, and never reaches the user: a failure on a handheld is
 * one focused moment of "what happened, what now", so every cause gets a
 * short plain sentence, and the one kind the user can actually fix (no
 * emulator installed for the system) is named as such.
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
        cause is EmulatorNeedsFileAccess -> cause.message.orEmpty()
        else -> if (game.isNullOrBlank()) {
            "The game couldn't be started."
        } else {
            "\"$game\" couldn't be started."
        }
    }
}
