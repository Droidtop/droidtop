package dev.droidtop.app

import android.content.Context
import android.net.ConnectivityManager
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.runtime.DesktopFonts
import kotlinx.coroutines.delay

/**
 * Fonts for all languages as a job in Downloads and installs
 * (Droidtop/tracker#390, docs/SPEC.md 3d): visible, pausable, waiting for an
 * unmetered network unless the person allowed mobile data, and resuming
 * where the download stopped ([DesktopFonts.installScript]). It needs the
 * running desktop, since the fonts are installed inside it; while there is
 * none it waits and says so. One job at a time: [ensure] returns the one
 * already there.
 */
object DesktopFontsJob {
    const val KIND = "desktop_fonts"
    private const val TITLE = "Fonts for all languages (about 100 MB)"
    private const val WAIT_MS = 5_000L

    /** Registers the runner; once, at process start. A job left by a previous process carries on by itself. */
    fun register(context: Context) {
        val app = context.applicationContext
        PluginJobsCenter.registerNative(kind = KIND, reattachOnRestart = true) { _, _, report -> run(app, report) }
    }

    /** Starts the job, or returns the one already running or paused. */
    fun ensure(context: Context) {
        PluginJobsCenter.startNative(context.applicationContext, KIND, TITLE, owner = "Desktop")
    }

    private suspend fun run(context: Context, report: (Int, String, String?) -> Unit): String {
        while (true) {
            if (!DesktopSetupPrefs.allLanguageFonts(context)) return "Turned off, nothing installed"
            val session = DesktopSessionService.state.value as? DesktopSessionState.Connected
            val waiting = when {
                session == null -> "Waiting for the desktop to run"
                !networkAllows(context) ->
                    if (DesktopSetupPrefs.fontsOnMetered(context)) "Waiting for a network" else "Waiting for Wi-Fi or another unmetered network"
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
            if (DesktopSessionService.state.value !== session || !networkAllows(context)) continue
            error(result.stderr.lines().lastOrNull { it.isNotBlank() } ?: "The install exited with ${result.exitCode}")
        }
    }

    private fun networkAllows(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        if (connectivity.activeNetwork == null) return false
        return DesktopSetupPrefs.fontsOnMetered(context) || !connectivity.isActiveNetworkMetered
    }
}
