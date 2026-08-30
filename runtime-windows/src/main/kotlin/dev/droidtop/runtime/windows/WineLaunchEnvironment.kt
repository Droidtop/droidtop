package dev.droidtop.runtime.windows

import java.util.TimeZone

/**
 * The real Wine/Box64 guest environment, transcribed from
 * vendor/gamenative's `GuestProgramLauncherComponent` (`exec`'s base env
 * block, `addBox64EnvVars`, `addBox86EnvVars` — the foundation of how
 * gamenative actually runs PC games, per direction) and adapted to
 * droidtop's container model:
 *
 *  - `DISPLAY=:0` (gamenative's Android-side XServer) is replaced by
 *    `WAYLAND_DISPLAY` — droidtop's Wine is a Wayland client of the
 *    primary container's compositor (docs/SPEC.md §2/§5), there is no
 *    XServer in this model.
 *  - `ANDROID_SYSVSHM_SERVER`/`libandroid-sysvshm.so` (gamenative's
 *    Android-host SysV-shm shim) are omitted: inside a real container a
 *    real kernel provides SysV shm; the shim exists because gamenative's
 *    guest runs against Android's libc environment.
 *  - PATH/LD_LIBRARY_PATH keep gamenative's shape but rooted at the
 *    container's own filesystem (wine lives in the container, whether from
 *    the distro's package or an imported ImageFS layout).
 *
 * Everything else is kept as the real upstream values, not re-derived:
 * BOX64_DYNAREC/AVX/NORCFILES/X11GLX, the WINEESYNC=0 force (upstream
 * forces it for the guest env after reading the caller's value), TZ/HOME/
 * USER/TMPDIR/LC_ALL. BOX64_X11GLX is kept even under Wayland: Wine's
 * X11-on-XWayland path still exists for prefixes/components that end up
 * on XWayland rather than the native Wayland driver.
 */
object WineLaunchEnvironment {
    /**
     * [homePath] is the in-container home for the Wine user; [winePath] is
     * the in-container Wine installation root (its `bin` is prepended to
     * PATH, same as `ImageFs.getWinePath()` upstream) or null when wine is
     * on the standard PATH already (a distro-packaged wine).
     */
    fun build(
        prefixPath: String,
        waylandDisplay: String = "wayland-0",
        homePath: String = "/root",
        winePath: String? = null,
        enableBox64Logs: Boolean = false,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val env = linkedMapOf<String, String>()
        // Base guest env (GuestProgramLauncherComponent.exec).
        env["TZ"] = TimeZone.getDefault().id
        env["HOME"] = homePath
        env["USER"] = "root"
        env["TMPDIR"] = "/tmp"
        env["LC_ALL"] = "en_US.utf8"
        env["WAYLAND_DISPLAY"] = waylandDisplay
        env["XDG_RUNTIME_DIR"] = "/run/droidtop"
        env["PATH"] = buildString {
            if (winePath != null) append("$winePath/bin:")
            append("/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        }
        env["LD_LIBRARY_PATH"] = "/usr/lib/aarch64-linux-gnu:/usr/lib/arm-linux-gnueabihf"
        // Box64 set (addBox64EnvVars), real upstream values.
        env["BOX64_NOBANNER"] = if (enableBox64Logs) "0" else "1"
        env["BOX64_DYNAREC"] = "1"
        env["BOX64_AVX"] = "1"
        if (enableBox64Logs) {
            env["BOX64_LOG"] = "1"
            env["BOX64_DYNAREC_MISSING"] = "1"
        }
        env["BOX64_X11GLX"] = "1"
        env["BOX64_NORCFILES"] = "1"
        // Wine.
        env["WINEPREFIX"] = prefixPath
        env.putAll(extra)
        // Real upstream ordering: WINEESYNC is forced to 0 for the guest
        // env AFTER the caller's extras are merged (upstream reads the
        // caller's value first for its shm-binding decision, then forces).
        env["WINEESYNC"] = "0"
        return env
    }
}
