package dev.droidtop.app.update

import android.content.Context
import android.util.Log

/**
 * The forced update pass: check the release feed right now, and install
 * whatever is newer (docs/SPEC.md 10b, decision 2026-09-11).
 *
 * This is the same machinery as the scheduled pass -- [AppSelfUpdate.fetch]
 * and [AppSelfUpdate.downloadAndInstall], one mechanism -- with the two
 * gates that hold the scheduled pass back deliberately not consulted: the
 * frequency the user chose (including Never), the interval since the last
 * attempt, and the unmetered-only option. Both gates live in one place,
 * [AppSelfUpdate.mayCheck], which the scheduled pass calls with
 * forced = false and this one with forced = true; that is what "bypasses
 * the schedule" means in code rather than in prose.
 *
 * Nothing here bypasses Android: the download is still verified against the
 * digest published with the release, the install still goes through a
 * PackageInstaller session, and the system's own confirmation (and its
 * signing-key check) is the only prompt that remains.
 *
 * Reached two ways: the "Check now" row on Settings > Software
 * updates, and the UPDATE_NOW broadcast (dev.droidtop.app.UpdateNowReceiver).
 */
object UpdateNow {
    const val ACTION = "dev.droidtop.UPDATE_NOW"
    const val TAG = "DroidtopUpdateNow"

    /** The Android shell (what adb runs as) and root. Fixed platform uids. */

    /** What a forced pass decides, once it knows what is published. */
    enum class Verdict { ALREADY_CURRENT, INSTALL }

    fun verdict(installedVersionCode: Long, publishedVersionCode: Long): Verdict =
        if (publishedVersionCode > installedVersionCode) Verdict.INSTALL else Verdict.ALREADY_CURRENT

    /**
     * Checks now and installs if newer. Blocking: call from a worker
     * thread. Returns the one-line outcome, which is also logged under
     * [TAG] so the adb path can be read back with logcat. Throws nothing;
     * a failed check or download comes back as its message.
     */
    fun runNow(context: Context, onStatus: (String) -> Unit = {}): String {
        val application = context.applicationContext
        val installed = AppSelfUpdate.installedVersionCode(application)
        onStatus("Checking for a newer build...")
        AppSelfUpdate.noteAttempt(application)
        val info = try {
            AppSelfUpdate.fetch(application)
        } catch (error: Exception) {
            return log("Update check failed: " + (error.message ?: error.javaClass.simpleName))
        }
        if (verdict(installed, info.versionCode) == Verdict.ALREADY_CURRENT) {
            return log(
                "already current: installed build " + installed + ", published build " + info.versionCode +
                    " (" + info.versionName + ")",
            )
        }
        log("newer build published: " + info.versionName + " (build " + info.versionCode + "), installed " + installed)
        return try {
            AppSelfUpdate.downloadAndInstall(application, info, onStatus)
            log(
                "handed " + info.versionName + " (build " + info.versionCode + ") to the Android installer; " +
                    "the system's own confirmation is the only remaining prompt",
            )
        } catch (error: Exception) {
            log("Update install failed: " + (error.message ?: error.javaClass.simpleName))
        }
    }

    private fun log(message: String): String {
        Log.i(TAG, message)
        return message
    }
}
