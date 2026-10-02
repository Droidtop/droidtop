package dev.droidtop.shell.standard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.droidtop.runtime.tasks.RunningApp
import dev.droidtop.runtime.tasks.TaskManager
import dev.droidtop.runtime.tasks.TaskPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Standard's Clear all apps (docs/SPEC.md "The task manager", Droidtop/tracker#252): every app except
 * droidtop, Enginehost and what the user protected, closed by the same [TaskManager] the Quick Menu
 * and the companion use. Reached from the mode switcher's own menu (the one dialog every mode and
 * the home screen's long-press already open) and from the gesture slot action "Close all apps".
 *
 * It is an activity of its own, translucent over whatever was on screen, because the confirm for more
 * than a few apps has to be shown after the list is read, and the menu that started it has gone by
 * then. The close itself outlives the screen: it runs in a process scope, and its result is a toast.
 */
object ClearAllApps {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @JvmStatic
    fun open(context: Context) {
        context.startActivity(
            Intent(context, ClearAllAppsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

class ClearAllAppsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        ClearAllApps.scope.launch {
            val targets = TaskManager.clearAllTargets(app)
            runOnUiThread { decide(targets) }
        }
    }

    private fun decide(targets: List<RunningApp>) {
        if (isFinishing || isDestroyed) return
        when {
            targets.isEmpty() -> say("Nothing to close.")
            TaskPolicy.needsClearAllConfirm(targets.size) ->
                AlertDialog.Builder(this, com.android.launcher3.R.style.DroidtopDialog)
                    .setTitle("Close ${targets.size} apps?")
                    .setMessage("Unsaved progress in them is lost. droidtop, Enginehost and the apps you protected stay open.")
                    .setPositiveButton("Close all") { _, _ -> closeAll(targets) }
                    .setNegativeButton("Cancel", null)
                    .setOnDismissListener { finish() }
                    .show()
            else -> closeAll(targets)
        }
    }

    private fun closeAll(targets: List<RunningApp>) {
        val app = applicationContext
        ClearAllApps.scope.launch {
            val summary = TaskManager.clearAll(app, targets)
            runOnUiThread { say(summary.message) }
        }
    }

    private fun say(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        if (!isFinishing) finish()
    }
}
