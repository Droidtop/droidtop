package dev.droidtop.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import dev.droidtop.library.settings.Mode
import dev.droidtop.runtime.ContainerApplications
import dev.droidtop.runtime.ContainerExports
import dev.droidtop.runtime.ExportedApp
import dev.droidtop.shell.standard.BackButtonMenu
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one way an app another container exported (docs/SPEC.md 3d "Exported apps") is run: in its own container,
 * started first where the backend needs that, through the desktop session's primary ([DesktopSessionService
 * .runInPrimary]), so its window reaches the shared desktop. The desktop's own menus reach it over the launch request
 * channel ([DesktopLibraryEntries]); from outside the shell (the Desktop widget, Droidtop/tracker#563) [fromOutside]
 * reuses the running session, or brings Desktop up, which starts it, and runs the app once it has connected.
 */
internal object ExportLaunch {
    private const val TAG = "droidtop.ExportLaunch"

    /** How long a launch from outside waits for a session Desktop is starting. */
    private const val CONNECT_WAIT_MS = 3 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Runs [export] in the running session; false when there is none. A failure is the session's launch failure line. */
    fun launch(export: ExportedApp): Boolean {
        val started = DesktopSessionService.runInPrimary { runtime, _ ->
            val info = runtime.listContainers().firstOrNull { it.container.id == export.containerId }
                ?: return@runInPrimary "${export.containerName} no longer exists, so ${export.app.name} can't start"
            if (runtime.siblingsNeedStart && !info.running) runtime.start(info.container)
            ContainerApplications.launch(runtime, info.container, export.app)
        }
        if (!started) Log.w(TAG, "No desktop session to run ${export.title} in")
        return started
    }

    /**
     * Shows Desktop, where the app's window will appear, and runs [export]: at once in a running session, else once the
     * session Desktop starts has connected (Desktop starts it when shown and set up). If it does not connect within
     * [CONNECT_WAIT_MS] (Desktop not set up, or the start failed, which Desktop itself says), nothing is run.
     * [activity] is in front, so it may start Desktop.
     */
    fun fromOutside(activity: Activity, export: ExportedApp) {
        activity.startActivity(
            Intent(Intent.ACTION_MAIN)
                .setClassName(activity.packageName, MainActivity::class.java.name)
                .putExtra(BackButtonMenu.EXTRA_MODE, Mode.DESKTOP.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        if (launch(export)) return
        scope.launch {
            val connected = withTimeoutOrNull(CONNECT_WAIT_MS) {
                DesktopSessionService.state.first { it is DesktopSessionState.Connected }
            }
            if (connected == null) {
                Log.w(TAG, "The desktop did not connect; ${export.title} was not started")
                return@launch
            }
            launch(export)
        }
    }

    /** The exports published now, read from the list droidtop keeps (a few lines). Off the main thread. */
    fun published(context: Context): List<ExportedApp> = ContainerExports.read(context.filesDir)
}

/**
 * A tap on an exported app outside the shell (the Desktop widget's rows): names the export by its token, hands it to
 * [ExportLaunch.fromOutside] and is gone. A token droidtop has not published runs nothing.
 */
class ExportLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent?.getStringExtra(EXTRA_TOKEN)
        val export = token?.let { ContainerExports.forToken(ExportLaunch.published(this), it) }
        if (export == null) Log.w("droidtop.ExportLaunch", "No published export for $token") else ExportLaunch.fromOutside(this, export)
        finish()
    }

    companion object {
        const val EXTRA_TOKEN = "dev.droidtop.app.extra.EXPORT_TOKEN"

        fun intentFor(context: Context, export: ExportedApp): Intent =
            Intent(context, ExportLaunchActivity::class.java).putExtra(EXTRA_TOKEN, export.token)
    }
}
