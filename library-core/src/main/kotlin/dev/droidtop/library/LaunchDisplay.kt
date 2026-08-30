package dev.droidtop.library

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent

/**
 * Launcher-wide launch-display targeting (docs/SPEC.md §4, handheld
 * dual-screen roles): every game/app launch goes through [start] so the
 * whole launcher system honors one configured target display — console
 * ROM players, engine games (enginehost/Kirikiroid2), and native apps
 * alike. Desktop mode never sets a target (its lower screen is an input
 * surface, §4).
 *
 * [targetDisplayId] is process state, set by the shell (`MainActivity`
 * resolves it from [dev.droidtop.app] `DisplayRolePrefs` + the live
 * display list) rather than each library-core call site re-reading
 * preferences it shouldn't know the shape of. Null = launch normally on
 * the default display.
 */
object LaunchDisplay {
    @Volatile
    var targetDisplayId: Int? = null

    fun start(context: Context, intent: Intent) {
        val displayId = targetDisplayId
        if (displayId != null) {
            context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
        } else {
            context.startActivity(intent)
        }
    }
}
