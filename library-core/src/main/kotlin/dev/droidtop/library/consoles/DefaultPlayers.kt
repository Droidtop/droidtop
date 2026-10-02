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
 * The command is ES-DE Android's own, extra for extra
 * (resources/systems/android/es_systems.xml, e.g. its Nintendo 64 entry):
 *
 *     CONFIGFILE=/storage/emulated/0/Android/data/<package>/files/retroarch.cfg
 *     LIBRETRO=/data/user/0/<package>/cores/<core>_libretro_android.so
 *     ROM=<path>
 *
 * Two differences from that left every game black on the console
 * (Droidtop/tracker#271). The file name: RetroArch's own core downloader
 * saves `<core>_libretro_android.so` (the buildbot's zip names), and this
 * wrote `<core>_android.so`, a file that does not exist. And no
 * CONFIGFILE: RetroArch's native side (frontend/drivers/platform_unix.c,
 * frontend_unix_get_env) takes its config path only from the extras its
 * own launcher passes, so a bare ROM+LIBRETRO launch started without the
 * person's settings. [ConsoleSystemDef.retroArchCore] is ES-DE's Android
 * core name for the same reason (Nintendo 64 is `mupen64plus_next_gles3`
 * there, not the Linux `mupen64plus_next`).
 *
 * Still a starting default meant to be edited, the same way Daijishō's
 * own Player entities are user-editable: someone who has moved
 * `libretro_directory` elsewhere, or runs a fork with its own package
 * name, will need to adjust it. droidtop cannot reliably read another
 * app's config to discover the real value, since `Android/data/<other
 * package>/` is not generally readable on modern Android.
 */
object DefaultPlayers {
    internal val RETROARCH_PACKAGE_VARIANTS =
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
        return Player.AmStart(
            id = "retroarch-${system.id}",
            name = "RetroArch",
            argumentsTemplate = retroArchArguments(installedPackage, core),
            packageName = installedPackage,
        )
    }

    /** The launch arguments for [core] under [installedPackage]; pure, see the class comment. */
    internal fun retroArchArguments(installedPackage: String, core: String): String =
        "-n $installedPackage/$RETROARCH_ACTIVITY " +
            "--es CONFIGFILE /storage/emulated/0/Android/data/$installedPackage/files/retroarch.cfg " +
            "--es LIBRETRO /data/user/0/$installedPackage/cores/${core}_libretro_android.so " +
            "--es ROM {file.path}"
}
