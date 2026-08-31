package dev.droidtop.library.consoles

import android.content.Context

/**
 * Builds a default RetroArch [Player.AmStart] for a [ConsoleSystemDef] that
 * has a [ConsoleSystemDef.retroArchCore] -- RetroArch's own real, documented
 * Android launch convention (used this same way by every RetroArch-
 * integrating frontend, Daijishō included): start
 * [RETROARCH_ACTIVITY] with a `ROM` extra (the file path) and a
 * `LIBRETRO` extra (the core's `.so` path).
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
 * The core `.so` path points at RetroArch's app-private `cores/`
 * directory. That is where RetroArch actually keeps them: this used to
 * name `Android/data/<package>/files/cores/`, and on a real device that
 * directory contains only `retroarch.cfg` -- no `cores/` exists there,
 * or anywhere else on shared storage. RetroArch's own config is
 * explicit about it:
 *
 *     libretro_directory = "/data/user/0/com.retroarch.aarch64/cores/"
 *
 * Passing an app-private path is fine precisely because droidtop never
 * reads it -- only the string is handed over, and RetroArch resolves it
 * inside its own process where it is readable.
 *
 * Still a starting default meant to be edited, the same way Daijishō's
 * own Player entities are user-editable: someone who has moved
 * `libretro_directory` elsewhere, or runs a fork with its own package
 * name, will need to adjust it. droidtop cannot reliably read another
 * app's config to discover the real value, since `Android/data/<other
 * package>/` is not generally readable on modern Android.
 */
object DefaultPlayers {
    private val RETROARCH_PACKAGE_VARIANTS =
        listOf("com.retroarch", "com.retroarch.aarch64", "com.retroarch.ra32")

    /**
     * RetroArch's activity class, which does **not** follow the
     * application id.
     *
     * This used to be written as `"$installedPackage/.browser..."`, and
     * a leading dot resolves against the application id -- correct for
     * `com.retroarch`, wrong for every other variant. On a device with
     * the aarch64 build that produced, for every RetroArch launch:
     *
     *     ActivityNotFoundException: Unable to find explicit activity
     *     class {com.retroarch.aarch64/com.retroarch.aarch64.browser.
     *     retroactivity.RetroActivityFuture}
     *
     * Reading the installed APK's own manifest settles it: the class is
     * `com.retroarch.browser.retroactivity.RetroActivityFuture` in every
     * variant, and only the application id differs. The bundled players
     * database already writes it fully qualified for all three ids.
     */
    private const val RETROARCH_ACTIVITY = "com.retroarch.browser.retroactivity.RetroActivityFuture"

    fun retroArch(context: Context, system: ConsoleSystemDef): Player.AmStart? {
        val core = system.retroArchCore ?: return null
        val installedPackage = RETROARCH_PACKAGE_VARIANTS.firstOrNull { isPackageInstalled(context, it) }
            ?: return null
        val activity = "$installedPackage/$RETROARCH_ACTIVITY"
        val coresDir = "/data/user/0/$installedPackage/cores"
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
