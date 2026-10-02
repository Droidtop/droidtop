package dev.droidtop.library.tasks

import android.content.Context
import android.content.Intent
import dev.droidtop.library.LaunchContext
import dev.droidtop.library.LaunchDisplay

/**
 * The task manager's two moves that are launches, so they live beside [LaunchDisplay]: bringing an app to
 * the front on a screen, which is also how an app is moved to the other one (docs/SPEC.md "The task
 * manager"). Closing lives in `dev.droidtop.runtime.tasks.TaskManager`.
 */
object TaskActions {
    /**
     * Starts [packageName]'s launcher activity on [displayId]. For an app that is already open this
     * resumes its task, as tapping its icon does; whether Android then also moves that task to
     * [displayId] is Android's call, so a caller says "asked", not "moved". Returns one plain sentence
     * when it could not even ask, null otherwise.
     */
    fun bringTo(context: Context, packageName: String, displayId: Int): String? {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return "$packageName has no screen droidtop can open."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        LaunchDisplay.startOnDisplay(context, intent, displayId, LaunchContext(packageName, null))
        return null
    }
}
