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
 * download policy like every download (docs/SPEC.md "Download rules"; it is
 * about 100 MB, so the default keeps it off mobile data), and resuming
 * where the download stopped ([DesktopFonts.installScript]). It needs the
 * running desktop, since the fonts are installed inside it; while there is
 * none it waits and says so. One job at a time: [ensure] returns the one
 * already there.
 */
object DesktopFontsJob {
    const val KIND = "desktop_fonts"
    private const val TITLE = "Fonts for all languages (about 100 MB)"
    private const val WAIT_MS = 5_000L
    private const val SIZE_BYTES = 100L * 1024 * 1024

    /** Registers the runner; once, at process start. A job left by a previous process carries on by itself. */
    fun register(context: Context) {
        val app = context.applicationContext
        PluginJobsCenter.registerNative(kind = KIND, reattachOnRestart = true, download = true) { _, _, report -> run(app, report) }
    }

    /** Starts the job, or returns the one already running or paused. */
    fun ensure(context: Context) {
        PluginJobsCenter.startNative(
            context.applicationContext, KIND, TITLE,
            args = mapOf(DownloadGate.ARG_BYTES to SIZE_BYTES.toString(), DownloadGate.ARG_AUTOMATIC to "1"),
            owner = "Desktop",
        )
    }

    private suspend fun run(context: Context, report: (Int, String, String?) -> Unit): String {
        while (true) {
            if (!DesktopSetupPrefs.allLanguageFonts(context)) return "Turned off, nothing installed"
            val session = DesktopSessionService.state.value as? DesktopSessionState.Connected
            val waiting = when {
                session == null -> "Waiting for the desktop to run"
                offline(context) -> "Waiting for a network"
                else -> null
            }
            if (session == null || waiting != null) {
                report(-1, waiting ?: "Waiting", null)
                delay(WAIT_MS)
                continue
            }
            report(-1, "Downloading and installing", null)
            val result = session.runtime.exec(session.container, listOf("/bin/sh", "-c", DesktopFonts.installScript()), emptyMap())
            if (result.exitCode == 0) return "Installed"
            // The desktop stopped or the network went under it: the next try resumes the download.
            if (DesktopSessionService.state.value !== session || offline(context)) continue
            error(result.stderr.lines().lastOrNull { it.isNotBlank() } ?: "The install exited with ${result.exitCode}")
        }
    }

    /** Only a lost network is this job's to wait out; which network it may use is the download policy's call. */
    private fun offline(context: Context): Boolean = DownloadPolicy.networkNow(context) == NetworkClass.OFFLINE
}
