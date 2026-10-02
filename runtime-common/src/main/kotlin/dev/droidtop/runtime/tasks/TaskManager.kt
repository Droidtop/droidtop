package dev.droidtop.runtime.tasks

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The task manager: the one place that ends another app (docs/SPEC.md, "The task manager"). The Quick
 * Menu, the companion and Standard's home all call this; none has a path of its own.
 *
 * Every call is a suspend function that works on `Dispatchers.IO`: a provider's force-stop blocks on its
 * process, so nothing here may run on the main thread.
 */
object TaskManager {
    private const val TAG = "droidtop.TaskManager"

    @Volatile
    var ops: PrivilegedOps = NoPrivilegedOps
        private set

    /** :app installs the plugin-backed helper once, at start. */
    fun install(ops: PrivilegedOps) {
        this.ops = ops
    }

    private fun closer(context: Context): AppCloser {
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return AppCloser(ops, killBackground = { pkg ->
            try {
                am.killBackgroundProcesses(pkg)
                true
            } catch (t: Throwable) {
                Log.d(TAG, "Couldn't ask Android to end $pkg", t)
                false
            }
        })
    }

    /** Closes one app by the strongest path available, and says what that achieved. */
    suspend fun close(context: Context, packageName: String): CloseOutcome =
        withContext(Dispatchers.IO) { closer(context).close(packageName) }
}
