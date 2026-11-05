package dev.droidtop.app

import android.content.Context
import dev.droidtop.net.DownloadPolicy
import dev.droidtop.net.NetworkClass
import dev.droidtop.pluginhost.DownloadGate
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.runtime.DesktopFonts
import kotlinx.coroutines.delay

/**
 * Fonts for all languages as a job in Downloads and installs
 * (Droidtop/tracker#390, docs/SPEC.md 3d): visible, pausable, held by the
 * download policy's network rule like every download (docs/SPEC.md "Download
 * rules"; it is about 100 MB, so the default keeps it off mobile data), and resuming
 * where the download stopped ([DesktopFonts.installScript]). It needs the
 * running desktop, since the fonts are installed inside it; while there is
 * none it waits and says so. One job at a time: [ensure] returns the one
 * already there.
 */
object DesktopFontsJob {
    const val KIND = "desktop_fonts"
    private const val TAG = "droidtop.DesktopFonts"
    private const val TITLE = "Fonts for all languages (about 100 MB)"
    private const val WAIT_MS = 5_000L
    private const val SIZE_BYTES = 100L * 1024 * 1024

    /** Registers the runner; once, at process start. A job left by a previous process carries on by itself. */
    fun register(context: Context) {
        val app = context.applicationContext
        PluginJobsCenter.registerNative(kind = KIND, reattachOnRestart = true, download = true) { _, _, report -> run(app, report) }
    }

    /**
     * Starts the job, or returns the one already running or paused. It is not an automatic download: the fonts are part
     * of the desktop the person set up, so the update window and a running game do not hold it, only the network rule
     * (the 2026-11-04 rig run found a fresh desktop with none, and nothing on screen said why). Where it stands is
     * logged, so a desktop without the fonts can be told from one whose job is waiting.
     */
    fun ensure(context: Context) {
        val id = PluginJobsCenter.startNative(
            context.applicationContext, KIND, TITLE,
            args = mapOf(DownloadGate.ARG_BYTES to SIZE_BYTES.toString()),
            owner = "Desktop",
        )
        val entry = id?.let(PluginJobsCenter::find)
        android.util.Log.i(TAG, "Fonts for all languages: job ${id ?: "not registered"}, ${entry?.statusLine ?: if (entry?.paused == true) "paused" else "running"}")
    }

    private suspend fun run(context: Context, report: (Int, String, String?) -> Unit): String {
        var lastWait: String? = null
        while (true) {
            if (!DesktopSetupPrefs.allLanguageFonts(context)) return "Turned off, nothing installed"
            val session = DesktopSessionService.state.value as? DesktopSessionState.Connected
            val waiting = when {
                session == null -> "Waiting for the desktop to run"
                offline(context) -> "Waiting for a network"
                else -> null
            }
            if (session == null || waiting != null) {
                if (waiting != lastWait) android.util.Log.i(TAG, "Fonts for all languages: $waiting")
                lastWait = waiting
                report(-1, waiting ?: "Waiting", null)
                delay(WAIT_MS)
                continue
            }
            report(-1, "Downloading and installing", null)
            android.util.Log.i(TAG, "Fonts for all languages: installing")
            // A desktop stopped under the install throws rather than exiting: that is waited out too, not a failure.
            val attempt = runCatching {
                session.runtime.exec(session.container, listOf("/bin/sh", "-c", DesktopFonts.installScript()), emptyMap())
            }
            val result = attempt.getOrNull()
            if (result == null) {
                val e = attempt.exceptionOrNull()
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w(TAG, "Fonts for all languages: the install stopped (${e?.message}); trying again")
                delay(WAIT_MS)
                continue
            }
            if (result.exitCode == 0) {
                android.util.Log.i(TAG, "Fonts for all languages: installed")
                return "Installed"
            }
            android.util.Log.w(TAG, "Fonts for all languages: the install exited with ${result.exitCode}: ${result.stderr.takeLast(400)}")
            // The desktop stopped or the network went under it: the next try resumes the download.
            if (DesktopSessionService.state.value !== session || offline(context)) continue
            error(result.stderr.lines().lastOrNull { it.isNotBlank() } ?: "The install exited with ${result.exitCode}")
        }
    }

    /** Only a lost network is this job's to wait out; which network it may use is the download policy's call. */
    private fun offline(context: Context): Boolean = DownloadPolicy.networkNow(context) == NetworkClass.OFFLINE
}
