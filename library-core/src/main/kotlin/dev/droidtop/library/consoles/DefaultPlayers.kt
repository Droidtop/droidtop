package dev.droidtop.library.consoles

import android.content.Context

/**
 * Builds a default RetroArch [Player.AmStart] for a [ConsoleSystemDef] that
 * has a [ConsoleSystemDef.retroArchCore] -- RetroArch's own real, documented
 * Android launch convention (used this same way by every RetroArch-
 * integrating frontend, Daijishō included): start
 * `<package>/.browser.retroactivity.RetroActivityFuture` with a `ROM`
 * extra (the file path) and a `LIBRETRO` extra (the core's `.so` path).
 *
 * [RETROARCH_PACKAGE_VARIANTS]: real, confirmed distribution difference --
 * RetroArch's Play Store build is `com.retroarch`, but its direct-download/
 * GitHub-release ARM64 build (a real, common install on handhelds, exactly
 * what a real test device this session had installed) is
 * `com.retroarch.aarch64` -- a genuinely different package name, not a
 * typo. Hardcoding only `com.retroarch` silently made every RetroArch-only
 * system (GBA/GBC/GB/NES/N64/NDS/DOS/...) report "no available player" on
 * any device using the aarch64 build, even with RetroArch actually
 * installed and working -- a real, confirmed bug, not a hypothetical edge
 * case. [retroArch] now checks each real variant and builds the launch
 * Intent's own component name against whichever one is actually present.
 *
 * The core `.so` path assumes a standard, non-rooted RetroArch install's
 * app-specific external files directory (`Android/data/<package>/
 * files/cores/`) -- RetroArch's own sandbox, always readable by RetroArch
 * itself regardless of droidtop's own storage permissions, since droidtop
 * only ever passes the path string along, never reads it. Real for a
 * standard install; an unusual RetroArch install location (a different
 * user profile, a fork like RetroArch Plus with its own package name)
 * won't match -- this is a starting default meant to be edited, same as
 * Daijishō's own Player entities are user-editable, not a guarantee.
 */
object DefaultPlayers {
    private val RETROARCH_PACKAGE_VARIANTS = listOf("com.retroarch", "com.retroarch.aarch64")

    fun retroArch(context: Context, system: ConsoleSystemDef): Player.AmStart? {
        val core = system.retroArchCore ?: return null
        val installedPackage = RETROARCH_PACKAGE_VARIANTS.firstOrNull { isPackageInstalled(context, it) }
            ?: return null
        val activity = "$installedPackage/.browser.retroactivity.RetroActivityFuture"
        val coresDir = "/storage/emulated/0/Android/data/$installedPackage/files/cores"
        return Player.AmStart(
            id = "retroarch-${system.id}",
            name = "RetroArch",
            argumentsTemplate = "-n $activity " +
                "--es ROM {file.path} " +
                "--es LIBRETRO $coresDir/${core}_android.so",
            packageName = installedPackage,
        )
    }
}
