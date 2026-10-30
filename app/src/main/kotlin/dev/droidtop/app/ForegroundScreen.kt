package dev.droidtop.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.ViewGroup
import java.lang.ref.WeakReference

/**
 * droidtop's screen in front, for the hidden web views to run behind ([dev.droidtop.pluginhost.HiddenWebViews],
 * Droidtop/tracker#783). Not the surfaces droidtop parks on the other screen (the companion, a cover) and not a game,
 * which a page behind it would slow.
 */
internal object ForegroundScreen {
    @Volatile
    private var resumed: WeakReference<Activity>? = null

    fun track(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity.hosts()) resumed = WeakReference(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (resumed?.get() === activity) resumed = null
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** The content view of the screen in front, or null when none of droidtop's is. On the main thread. */
    fun content(): ViewGroup? = resumed?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }?.findViewById(android.R.id.content)

    private fun Activity.hosts(): Boolean =
        this !is CompanionActivity && this !is dev.droidtop.display.SecondaryDisplayActivity && this !is dev.droidtop.runtime.windows.WineGameActivity
}
