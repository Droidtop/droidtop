package dev.droidtop.library

import java.security.MessageDigest

/**
 * What an installed app's cached icon file is CALLED, and which files in
 * the icon folder are stale.
 *
 * The name carries everything that can change the picture: the package,
 * its `lastUpdateTime` (an update can ship a new icon) and Launcher3's
 * own icon state for it (`IconProvider.getStateForApp`: locale, SDK,
 * themed-icon setting, the app's resource hash, the day for a dynamic
 * calendar). A scan whose file already exists under that name draws and
 * encodes nothing; a changed app gets a new name, so a stale picture is
 * never served from an old path (Coil caches by path, too).
 *
 * Pure, so the rule is unit-tested rather than only visible on a device.
 * See SPEC "Performance on the console".
 */
object AppIconFiles {

    const val EXTENSION = ".png"

    /** `<package>-<lastUpdateTime>-<12 hex of sha-256(icon state)>.png`. */
    fun fileName(packageName: String, lastUpdateTime: Long, iconState: String): String =
        "$packageName-$lastUpdateTime-${stateHash(iconState)}$EXTENSION"

    /**
     * Every file in the folder that no current app names: an uninstalled
     * app, an older version, a previous icon state, the pre-2026-09-24
     * `<package>.png` names, and a write that never finished its rename.
     */
    fun stale(existing: Collection<String>, current: Set<String>): List<String> =
        existing.filter { it !in current }

    private fun stateHash(iconState: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(iconState.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it) }
}
