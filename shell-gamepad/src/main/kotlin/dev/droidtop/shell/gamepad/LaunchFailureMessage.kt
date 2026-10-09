package dev.droidtop.shell.gamepad

import dev.droidtop.library.ProgramNotIdentified
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
        // Droidtop/tracker#271: what droidtop saw in RetroArch's cores folder, with Get the core beside it.
        cause is dev.droidtop.library.consoles.RetroArchCores.Missing -> cause.message ?: "RetroArch does not have this game's core."
        // Droidtop/tracker#308: what is wrong, never emulator advice.
        cause is ProgramNotIdentified ->
            "droidtop can't tell which program starts \"${game?.takeIf { it.isNotBlank() } ?: cause.title}\"."
        else -> if (game.isNullOrBlank()) {
            "The game couldn't be started."
        } else {
            "\"$game\" couldn't be started."
        }
    }
}
