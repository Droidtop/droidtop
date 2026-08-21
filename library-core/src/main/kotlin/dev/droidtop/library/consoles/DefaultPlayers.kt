package dev.droidtop.library.consoles

/**
 * Builds a default RetroArch [Player.AmStart] for a [ConsoleSystemDef] that
 * has a [ConsoleSystemDef.retroArchCore] -- RetroArch's own real, documented
 * Android launch convention (used this same way by every RetroArch-
 * integrating frontend, Daijishō included): start
 * `com.retroarch/.browser.retroactivity.RetroActivityFuture` with a `ROM`
 * extra (the file path) and a `LIBRETRO` extra (the core's `.so` path).
 *
 * The core `.so` path assumes a standard, non-rooted RetroArch install's
 * app-specific external files directory (`Android/data/com.retroarch/
 * files/cores/`) -- RetroArch's own sandbox, always readable by RetroArch
 * itself regardless of droidtop's own storage permissions, since droidtop
 * only ever passes the path string along, never reads it. Real for a
 * standard install; an unusual RetroArch install location (a different
 * user profile, a fork like RetroArch Plus with its own package name)
 * won't match -- this is a starting default meant to be edited, same as
 * Daijishō's own Player entities are user-editable, not a guarantee.
 */
object DefaultPlayers {
    private const val RETROARCH_PACKAGE = "com.retroarch"
    private const val RETROARCH_ACTIVITY = "$RETROARCH_PACKAGE/.browser.retroactivity.RetroActivityFuture"
    private const val RETROARCH_CORES_DIR = "/storage/emulated/0/Android/data/com.retroarch/files/cores"

    fun retroArch(system: ConsoleSystemDef): Player.AmStart? {
        val core = system.retroArchCore ?: return null
        return Player.AmStart(
            id = "retroarch-${system.id}",
            name = "RetroArch",
            argumentsTemplate = "-n $RETROARCH_ACTIVITY " +
                "--es ROM {file.path} " +
                "--es LIBRETRO $RETROARCH_CORES_DIR/${core}_android.so",
        )
    }
}
