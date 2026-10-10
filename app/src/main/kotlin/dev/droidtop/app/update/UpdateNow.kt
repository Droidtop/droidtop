package dev.droidtop.app.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * The forced update pass: check the release feed right now, and install
 * whatever is newer (docs/SPEC.md 10b, decision 2026-09-11).
 *
 * This is the same machinery as the scheduled pass -- [AppSelfUpdate.fetch]
 * and [AppSelfUpdate.install], one mechanism -- with the two
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
 * Reached two ways, both through [start]: the "Check now" row on Settings > Software updates
 * ([startAndAwait]), and the UPDATE_NOW broadcast (dev.droidtop.app.UpdateNowReceiver). The pass
 * itself is short: it reads the feed and hands the APK transfer to Android's DownloadManager
 * ([UpdateDownload]), which goes on whatever happens to droidtop's process and wakes droidtop to
 * install when it ends (Droidtop/tracker#445). Only the Check now row waits for that end, to narrate it.
 */
object UpdateNow {
    const val ACTION = "dev.droidtop.UPDATE_NOW"
    const val TAG = "DroidtopUpdateNow"

    /** How long the Check now row waits on the installer's answer. */
    private const val OUTCOME_WAIT_MS = 10 * 60 * 1000L

    /** How long a watching pass waits for the receiver that claimed the finished download to ask the installer. */
    private const val CLAIM_WAIT_MS = 2 * 60 * 1000L

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

    /** Ends the running pass with its one-line [outcome]. */
    internal fun finish(outcome: String) {
        passState.value = PassState(running = false, line = outcome, outcome = outcome)
    }

    /**
     * Starts a pass on a thread of its own and returns at once; a pass already running is joined, not
     * doubled. [onHandedOff] runs once this caller no longer needs droidtop's process for it: when the
     * pass ends (for UPDATE_NOW that is as soon as the download is with DownloadManager), or at once
     * when an earlier pass is joined. The UPDATE_NOW receiver finishes its broadcast there.
     */
    fun start(context: Context, waitForOutcome: Boolean = false, onHandedOff: () -> Unit = {}) {
        val application = context.applicationContext
        launch({ runPass(application, waitForOutcome) }, onHandedOff)
    }

    /** [start]'s bookkeeping, apart from the context so it can be tested. False when an earlier pass was joined. */
    internal fun launch(
        work: () -> Unit,
        onHandedOff: () -> Unit,
        spawn: (Runnable) -> Unit = { Thread(it, "UpdateNow").start() },
    ): Boolean {
        if (!begin()) {
            onHandedOff()
            return false
        }
        spawn(Runnable {
            try {
                work()
            } finally {
                onHandedOff()
            }
        })
        return true
    }

    /** Starts a pass (or joins the one running) and waits for it, narrating through [onStatus]: the Check now row. */
    suspend fun startAndAwait(context: Context, onStatus: (String) -> Unit): String {
        start(context, waitForOutcome = true)
        val done = pass
            .onEach { if (it.line.isNotEmpty()) onStatus(it.line) }
            .first { !it.running }
        return done.outcome ?: done.line
    }

    /**
     * [runNow] as the pass that [start] began: its narration kept in [pass], and always ending with [pass]
     * not running and the outcome set.
     */
    private fun runPass(context: Context, waitForOutcome: Boolean): String {
        val outcome = try {
            runNow(context, waitForOutcome) { line -> passState.update { it.copy(line = line) } }
        } catch (error: Throwable) {
            log("Update failed: " + (error.message ?: error.javaClass.simpleName))
        }
        finish(outcome)
        return outcome
    }

    /**
     * Checks now and installs if newer. Blocking: call from a worker
     * thread. Returns the one-line outcome, which is also logged under
     * [TAG] so the adb path can be read back with logcat. Throws nothing;
     * a failed check or download comes back as its message.
     *
     * Without [waitForOutcome] it returns once the APK download is with DownloadManager (or the
     * installer was asked for a copy already on disk); the install then happens when the download
     * ends ([UpdateDownloadReceiver]). With it, it watches the download and waits for the installer's
     * answer.
     */
    private fun runNow(context: Context, waitForOutcome: Boolean, onStatus: (String) -> Unit): String {
        val application = context.applicationContext
        val installed = AppSelfUpdate.installedVersionCode(application)
        onStatus("Checking for a newer build...")
        AppSelfUpdate.noteAttempt(application)
        // Whatever an earlier build left behind goes before anything is fetched: the installed build's
        // own APK and any older partial download, so a stale one is never resumed (tracker#445).
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
        val label = info.versionName + " (build " + info.versionCode + ")"
        val result = try {
            AppSelfUpdate.requireInstallAllowed(application)
            when (val found = UpdateDownload.obtain(application, info)) {
                is UpdateDownload.Found.Ready -> UpdateDownload.install(application, found.pending)
                is UpdateDownload.Found.BeingInstalled ->
                    UpdateDownload.awaitInstall(found.id, CLAIM_WAIT_MS)
                        ?: return log("the download of $label has finished and is being handed to Android's installer")
                is UpdateDownload.Found.Downloading -> {
                    if (!waitForOutcome) {
                        val how = if (found.joined) "is already downloading" else "is downloading"
                        return log("Android's download manager $how $label; droidtop asks the installer when it completes")
                    }
                    onStatus("Downloading ${info.versionName}...")
                    UpdateDownload.watch(application, found.id, info.versionName, onStatus)
                    UpdateDownload.complete(application, found.id, CLAIM_WAIT_MS)
                        ?: return log("the download of $label ended; droidtop asked Android's installer when it did")
                }
            }
        } catch (error: Exception) {
            return log("Update install failed: " + (error.message ?: error.javaClass.simpleName))
        }
        val sessionId = result.first ?: return result.second
        if (!waitForOutcome) return log("${result.second}; its answer is logged under $TAG")
        // The row reports what the installer really did, not that it
        // was asked: "handed to the installer" read the same after the
        // person had pressed Cancel (rig, dq-shell2-01).
        onStatus("Waiting for you to confirm the install in Android's installer...")
        return AppSelfUpdate.awaitOutcome(sessionId, OUTCOME_WAIT_MS)?.let { log(it) }
            ?: log("${result.second}; it has not answered yet. If nothing was asked, press Check now again.")
    }

    private fun log(message: String): String {
        Log.i(TAG, message)
        return message
    }

    /**
     * How long the UPDATE_NOW receiver may hold its broadcast open (goAsync) while the pass reads the feed
     * and hands the download over: under the broadcast timeout, which is 10 s for a foreground broadcast and
     * 60 s for a background one (ActivityManager's BROADCAST_FG_TIMEOUT / BROADCAST_BG_TIMEOUT). `am broadcast`
     * sends a background one unless asked otherwise (ActivityManagerShellCommand.runSendBroadcast adds only
     * FLAG_RECEIVER_FROM_SHELL). While the broadcast is held the process is not cached, so it is not frozen.
     */
    fun broadcastHoldMs(foreground: Boolean): Long = if (foreground) 8_000L else 50_000L
}
