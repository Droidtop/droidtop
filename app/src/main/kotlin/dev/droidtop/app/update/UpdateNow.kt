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
 * ([startAndAwait]), and the UPDATE_NOW broadcast (dev.droidtop.app.UpdateNowReceiver). The APK is
 * fetched by droidtop's own downloader as a job in Downloads ([UpdateDownload], Droidtop/tracker#226) and
 * installed by that job's post step; the pass narrates the job and ends with it.
 */
object UpdateNow {
    const val ACTION = "dev.droidtop.UPDATE_NOW"
    const val TAG = "DroidtopUpdateNow"

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
     * pass ends, or at once when an earlier pass is joined. The UPDATE_NOW receiver finishes its broadcast there
     * (or at its own hold limit, [broadcastHoldMs]); the download job goes on in Downloads either way and is
     * resumed from its partial file if the process was lost meanwhile.
     */
    fun start(context: Context, onHandedOff: () -> Unit = {}) {
        val application = context.applicationContext
        launch({ runPass(application) }, onHandedOff)
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
        start(context)
        val done = pass
            .onEach { if (it.line.isNotEmpty()) onStatus(it.line) }
            .first { !it.running }
        return done.outcome ?: done.line
    }

    /**
     * [runNow] as the pass that [start] began: its narration kept in [pass], and always ending with [pass]
     * not running and the outcome set.
     */
    private fun runPass(context: Context): String {
        val outcome = try {
            runNow(context) { line -> passState.update { it.copy(line = line) } }
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
     * The APK comes from droidtop's own downloader ([UpdateDownload]); the pass stays with the download job to its
     * end, which is the installer's answer.
     */
    private fun runNow(context: Context, onStatus: (String) -> Unit): String {
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
            AppSelfUpdate.recordFailure(application, error)
            return log("Update check failed: " + (error.message ?: error.javaClass.simpleName))
        }
        AppSelfUpdate.recordSeen(application, info)
        if (verdict(installed, info.versionCode) == Verdict.ALREADY_CURRENT) {
            return log(
                "already current: installed build " + installed + ", published build " + info.versionCode +
                    " (" + info.versionName + ")",
            )
        }
        log("newer build published: " + info.versionName + " (build " + info.versionCode + "), installed " + installed)
        // A newer build than one already half fetched supersedes it.
        UpdateFiles.clean(application, installed, target = info.versionCode)
        // The relaunch after the install is decided by what the person is doing now (UpdateRelaunch).
        UpdateRelaunch.arm(application)
        val outcome = try {
            AppSelfUpdate.requireInstallAllowed(application)
            when (val found = UpdateDownload.find(application, info)) {
                is UpdateDownload.Found.Ready -> {
                    val session = AppSelfUpdate.install(application, found.apk, info.versionCode, info.versionName, info.apkSha256)
                    onStatus("Waiting for you to confirm the install in Android's installer...")
                    log(
                        AppSelfUpdate.awaitOutcome(session, INSTALL_WAIT_MS)
                            ?: "asked Android's installer to install ${info.versionName}; it has not answered yet. If nothing was asked, press Check now again.",
                    )
                }
                is UpdateDownload.Found.Downloading -> {
                    log("the download of ${info.versionName} is already going; joining it")
                    log(UpdateDownload.await(found.jobId, onStatus))
                }
                UpdateDownload.Found.Missing -> {
                    onStatus("Downloading ${info.versionName}...")
                    log(kotlinx.coroutines.runBlocking { UpdateDownload.download(application, info, onStatus) })
                }
            }
        } catch (error: Exception) {
            log("Update install failed: " + (error.message ?: error.javaClass.simpleName))
        }
        // A failed update leaves nothing to come back from. A merely unanswered confirmation stays armed: the person may
        // still press Install, and the record expires by itself.
        if (outcome.startsWith("Update download failed") || outcome.startsWith("Update install failed")) UpdateRelaunch.disarm(application)
        return outcome
    }

    /** How long a copy of the APK already on disk waits for the installer's answer. */
    private const val INSTALL_WAIT_MS = 10 * 60 * 1000L

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
