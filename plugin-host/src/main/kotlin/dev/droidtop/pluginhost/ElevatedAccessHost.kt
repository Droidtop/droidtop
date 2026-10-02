package dev.droidtop.pluginhost

import android.content.Context
import dev.droidtop.runtime.tasks.BackendState
import dev.droidtop.runtime.tasks.ElevatedChoice
import dev.droidtop.runtime.tasks.ElevatedChoicePrefs
import dev.droidtop.runtime.tasks.ElevatedShell
import dev.droidtop.runtime.tasks.TaskManager

/**
 * Builds the one [ElevatedShell] from the two backends and hands it to the task manager, which is what every
 * other caller asks (docs/SPEC.md "The task manager"). Settings reads [shell] for the choices on offer and
 * [requestShizukuPermission] to ask the Shizuku app to allow droidtop.
 */
object ElevatedAccessHost {
    private val app = SystemShizukuOps()

    @Volatile
    private var installed: ElevatedShell? = null

    /** The shell every privileged call goes through; null before [install]. */
    val shell: ElevatedShell? get() = installed

    /** Once, from `Application.onCreate` in every process. No IPC and no file work: the backends do theirs on first use. */
    fun install(context: Context) {
        val application = context.applicationContext
        ShizukuTransport.install(application)
        val built = ElevatedShell(app, PluginPrivilegedOps(application)) { ElevatedChoicePrefs.get(application) }
        installed = built
        TaskManager.install(built)
    }

    fun shizukuAppState(): BackendState = app.state()

    /** Asks the Shizuku app to allow droidtop. A binder call, so it runs on its own thread. */
    fun requestShizukuPermission() {
        Thread { if (app.state() == BackendState.NEEDS_PERMISSION) app.requestPermission() }.apply { name = "droidtop-shizuku-permission"; isDaemon = true; start() }
    }

    fun setChoice(context: Context, choice: ElevatedChoice) {
        ElevatedChoicePrefs.set(context, choice)
        // Picking the Shizuku app is the moment to ask it for access: its own dialog is the whole flow.
        if (choice == ElevatedChoice.SHIZUKU_APP) requestShizukuPermission()
    }
}
