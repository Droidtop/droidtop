package dev.droidtop.shell.standard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.android.launcher3.R
import dev.droidtop.runtime.tasks.RunningApp
import dev.droidtop.runtime.util.AppScopes
import dev.droidtop.runtime.tasks.TaskManager
import dev.droidtop.runtime.tasks.TaskPolicy
import kotlinx.coroutines.launch

/**
 * Standard's Clear all apps (docs/SPEC.md "The task manager", Droidtop/tracker#252): every app except
 * droidtop, Enginehost and what the user protected, closed by the same [TaskManager] the Quick Menu
 * and the companion use. Reached from the mode switcher's own menu (the one dialog every mode and
 * the home screen's long-press already open) and from the gesture slot action "Close all apps".
 *
 * It is an activity of its own, translucent over whatever was on screen, because the confirm for more
 * than a few apps has to be shown after the list is read, and the menu that started it has gone by
 * then. The close itself outlives the screen: it runs in droidtop's process-wide scope ([AppScopes]; the task
 * manager's calls suspend), and its result is a flash.
 */
object ClearAllApps {

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
        AppScopes.io.launch {
            val targets = TaskManager.clearAllTargets(app)
            runOnUiThread { decide(targets) }
        }
    }

    private fun decide(targets: List<RunningApp>) {
        if (isFinishing || isDestroyed) return
        when {
            targets.isEmpty() -> say(getString(R.string.clear_all_nothing))
            TaskPolicy.needsClearAllConfirm(targets.size) ->
                AlertDialog.Builder(this, R.style.DroidtopDialog)
                    .setTitle(resources.getQuantityString(R.plurals.clear_all_confirm_title, targets.size, targets.size))
                    .setMessage(R.string.clear_all_confirm_body)
                    .setPositiveButton(R.string.clear_all_confirm) { _, _ -> closeAll(targets) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .setOnDismissListener { finish() }
                    .show()
            else -> closeAll(targets)
        }
    }

    private fun closeAll(targets: List<RunningApp>) {
        val app = applicationContext
        AppScopes.io.launch {
            val summary = TaskManager.clearAll(app, targets)
            runOnUiThread { say(summary.message) }
        }
    }

    private fun say(message: String) {
        dev.droidtop.library.settings.Notices.flash(message, context = applicationContext)
        if (!isFinishing) finish()
    }
}
