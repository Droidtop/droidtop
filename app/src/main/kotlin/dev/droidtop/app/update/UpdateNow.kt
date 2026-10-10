package dev.droidtop.app.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

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
 * Both start it through [UpdateService], the foreground service the whole pass
 * runs in so that Android keeps the process alive for the download
 * (Droidtop/tracker#445); [runPass] is what the service runs.
 */
object UpdateNow {
    const val ACTION = "dev.droidtop.UPDATE_NOW"
    const val TAG = "DroidtopUpdateNow"

    /** How long the Check now row waits on the installer's answer. */
    private const val OUTCOME_WAIT_MS = 10 * 60 * 1000L

    /** What a forced pass decides, once it knows what is published. */
    enum class Verdict { ALREADY_CURRENT, INSTALL }

    fun verdict(installedVersionCode: Long, publishedVersionCode: Long): Verdict =
        if (publishedVersionCode > installedVersionCode) Verdict.INSTALL else Verdict.ALREADY_CURRENT

    /** Where the running pass is, for whoever started or joined it. [outcome] is set once [running] is false. */
    data class PassState(val running: Boolean, val line: String = "", val outcome: String? = null)

    private val passState = MutableStateFlow(PassState(running = false))
    val pass: StateFlow<PassState> = passState

    /** Marks a pass as running. False when one already is: the caller joins it instead of starting another. */
    fun begin(): Boolean {
        var started = false
        passState.update { current ->
            started = !current.running
            if (started) PassState(running = true, line = "Starting...") else current
        }
        return started
    }

    /**
     * The pass [UpdateService] runs (after [begin]): [runNow], with its narration kept in [pass] and
     * passed to [onStatus] (the notification). Always ends with [pass] not running and the outcome set.
     */
    fun runPass(context: Context, waitForOutcome: Boolean = false, onStatus: (String) -> Unit = {}): String {
        val outcome = try {
            runNow(context, waitForOutcome) { line ->
                passState.update { it.copy(line = line) }
                onStatus(line)
            }
        } catch (error: Throwable) {
            log("Update failed: " + (error.message ?: error.javaClass.simpleName))
        }
        finish(outcome)
        return outcome
    }

    /** Ends the running pass with its one-line [outcome]. */
    internal fun finish(outcome: String) {
        passState.value = PassState(running = false, line = outcome, outcome = outcome)
    }

    /**
     * Starts [work] (always [runPass]) on a thread of its own and returns immediately: how
     * [UpdateService] runs the pass, and what [UpdateService.start] falls back to when Android
     * refuses a foreground service start. [spawn] is how the work is started; the default is
     * a new thread.
     */
    fun startDetached(
        work: () -> Unit,
        spawn: (Runnable) -> Unit = { Thread(it, "UpdateNow").start() },
    ) {
        spawn(Runnable { work() })
    }

    /**
     * Checks now and installs if newer. Blocking: call from a worker
     * thread. Returns the one-line outcome, which is also logged under
     * [TAG] so the adb path can be read back with logcat. Throws nothing;
     * a failed check or download comes back as its message.
     */
    fun runNow(context: Context, waitForOutcome: Boolean = false, onStatus: (String) -> Unit = {}): String {
        val application = context.applicationContext
        val installed = AppSelfUpdate.installedVersionCode(application)
        onStatus("Checking for a newer build...")
        AppSelfUpdate.noteAttempt(application)
        // Whatever an earlier build left behind goes before anything is fetched: the installed build's
        // own APK and any older partial download, so a stale .part is never resumed (tracker#445).
        UpdateFiles.clean(application, installed, target = null)
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
        // A newer build than one already half fetched supersedes it.
        UpdateFiles.clean(application, installed, target = info.versionCode)
        return try {
            val sessionId = AppSelfUpdate.downloadAndInstall(application, info, onStatus)
            val handed = "asked Android's installer to install " + info.versionName + " (build " + info.versionCode + ")"
            if (!waitForOutcome) return log("$handed; its answer is logged under $TAG")
            // The row reports what the installer really did, not that it
            // was asked: "handed to the installer" read the same after the
            // person had pressed Cancel (rig, dq-shell2-01).
            onStatus("Waiting for you to confirm the install in Android's installer...")
            AppSelfUpdate.awaitOutcome(sessionId, OUTCOME_WAIT_MS)?.let { log(it) }
                ?: log("$handed; it has not answered yet. If nothing was asked, press Check now again.")
        } catch (error: Exception) {
            log("Update install failed: " + (error.message ?: error.javaClass.simpleName))
        }
    }

    private fun log(message: String): String {
        Log.i(TAG, message)
        return message
    }
}
