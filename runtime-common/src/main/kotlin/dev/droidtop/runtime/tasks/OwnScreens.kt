package dev.droidtop.runtime.tasks

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One of droidtop's own screens open in a task of its own (Settings, a place, the container manager), by [taskId]. */
data class OwnScreen(val taskId: Int, val title: String, val displayId: Int)

/**
 * droidtop's own screens that are open as windows of their own (Droidtop/tracker#750, rig 2638): the running-apps
 * list leaves droidtop out ([TaskPolicy.hiddenFromList]), so without this Settings opened from Desktop's Start menu
 * was on screen and nowhere on the bar. Each screen adds itself while it exists; the list is in-process and needs
 * no privilege. Main thread.
 */
object OwnScreens {
    private val flow = MutableStateFlow<List<OwnScreen>>(emptyList())

    val open: StateFlow<List<OwnScreen>> = flow

    fun opened(screen: OwnScreen) {
        flow.value = flow.value.filterNot { it.taskId == screen.taskId } + screen
    }

    fun closed(taskId: Int) {
        flow.value = flow.value.filterNot { it.taskId == taskId }
    }

    /** Brings [screen]'s task to the front; false when Android no longer has it. */
    fun bringToFront(context: Context, screen: OwnScreen): Boolean =
        task(context, screen.taskId)?.let { it.moveToFront(); true } ?: false.also { closed(screen.taskId) }

    /** Ends [screen]'s task, as Back at its root would. */
    fun close(context: Context, screen: OwnScreen) {
        task(context, screen.taskId)?.finishAndRemoveTask()
        closed(screen.taskId)
    }

    private fun task(context: Context, taskId: Int): ActivityManager.AppTask? =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).appTasks
            .firstOrNull { runCatching { idOf(it.taskInfo) }.getOrNull() == taskId }

    // Activity.getTaskId's number: RecentTaskInfo.taskId from Android 10, its persistentId before.
    @Suppress("DEPRECATION")
    private fun idOf(info: ActivityManager.RecentTaskInfo): Int =
        if (android.os.Build.VERSION.SDK_INT >= 29) info.taskId else info.persistentId
}
