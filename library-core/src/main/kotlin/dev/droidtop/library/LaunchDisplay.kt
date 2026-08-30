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

    /**
     * The display the most recent launch was sent to, kept until the shell
     * deliberately reclaims it — the "don't interfere with apps we've
     * launched" half of the home-press display reinit (see MainActivity's
     * role orchestration): a reinit re-asserts droidtop's surfaces on
     * every display EXCEPT one still parked here, so a game running on
     * the addon isn't covered by the shell or the widgets panel. Cleared
     * by an explicit shell entry (BackButtonMenu's Handheld item).
     */
    @Volatile
    var parkedDisplayId: Int? = null

    /** Invoked after every [start] — the shell hooks this to re-run its display-role orchestration (e.g. dismissing the widgets Presentation off a display a game just went to). */
    @Volatile
    var onLaunched: ((Int?) -> Unit)? = null

    fun start(context: Context, intent: Intent) {
        val displayId = targetDisplayId
        if (displayId != null) {
            context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
        } else {
            context.startActivity(intent)
        }
        parkedDisplayId = displayId
        onLaunched?.invoke(displayId)
    }
}
