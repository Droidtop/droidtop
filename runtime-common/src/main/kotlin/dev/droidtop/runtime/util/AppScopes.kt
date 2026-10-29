package dev.droidtop.runtime.util

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * droidtop's one process-wide place for work that outlives the screen or call that starts it (Droidtop/tracker#763):
 * [io] for disk, network and blocking calls, [main] for what must run on the main thread. A failure in one piece of
 * work is logged under [TAG] and touches nothing else; neither scope is ever cancelled. Work whose lifetime its
 * owner ends (a session, a screen) keeps a scope of its own.
 */
object AppScopes {
    const val TAG = "droidtop.work"

    private val logged = CoroutineExceptionHandler { _, failure -> Log.w(TAG, "background work failed", failure) }

    val io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logged)

    val main: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logged) }
}
