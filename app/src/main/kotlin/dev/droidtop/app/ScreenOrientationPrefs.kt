package dev.droidtop.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Bundle
import dev.droidtop.library.settings.CatalogPrefs
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.Modes

/** Per-mode screen orientation, applied to every droidtop Activity centrally. */
object ScreenOrientationPrefs {
    const val KEY_PREFIX = "pref_screen_orientation_"
    const val FOLLOW = "follow"
    const val PORTRAIT = "portrait"
    const val PORTRAIT_FLIPPED = "portrait_flipped"
    const val LANDSCAPE = "landscape"
    const val LANDSCAPE_FLIPPED = "landscape_flipped"

    fun options(mode: Mode): List<Pair<String, String>> = buildList {
        add(FOLLOW to "Follow device")
        if (mode != Mode.GAMING) {
            add(PORTRAIT to "Portrait")
            add(PORTRAIT_FLIPPED to "Portrait (flipped)")
        }
        add(LANDSCAPE to "Landscape")
        add(LANDSCAPE_FLIPPED to "Landscape (flipped)")
    }

    fun requestedOrientation(mode: Mode, choice: String?): Int = when (choice) {
        PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        PORTRAIT_FLIPPED -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        LANDSCAPE_FLIPPED -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }.let { mapped -> if (options(mode).any { it.first == choice }) mapped else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }

    fun choice(context: Context, mode: Mode): String = CatalogPrefs.prefs(context)
        .getString(KEY_PREFIX + mode.id, FOLLOW) ?: FOLLOW

    fun apply(activity: Activity) {
        // The companion screen's own lock wins over the mode's choice there (tracker#213); landscape either way
        // round, as the device allows.
        if (activity is CompanionActivity && dev.droidtop.library.settings.CompanionOrientation.lockLandscape(activity)) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            return
        }
        val mode = Mode.byId(Modes.lastMode(activity)) ?: Mode.LAUNCHER
        activity.requestedOrientation = requestedOrientation(mode, choice(activity, mode))
    }

    fun install(app: Application) {
        val activities = LinkedHashSet<Activity>()
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                activities += activity
                apply(activity)
            }
            override fun onActivityDestroyed(activity: Activity) { activities -= activity }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "droidtop_last_mode" || key == dev.droidtop.library.settings.CompanionOrientation.KEY_LOCK_LANDSCAPE ||
                Mode.entries.any { key == KEY_PREFIX + it.id }
            ) {
                activities.toList().forEach(::apply)
            }
        }
        CatalogPrefs.prefs(app).registerOnSharedPreferenceChangeListener(listener)
    }
}
