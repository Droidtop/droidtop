package dev.droidtop.shell.standard

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.android.launcher3.LauncherPrefs

/**
 * Settings > Home's "Hide status bar" and "Hide navigation bar" on Standard's home (Droidtop/tracker#703): on a
 * handheld's short panel the two bars take about a fifth of the home screen. Off by default, as Standard keeps the
 * system bars (docs/SPEC.md 2b). Hidden bars come back for a moment with a swipe from the edge. Only the home's own
 * window is changed, so an app opened from it keeps its own bars, and each display's home applies it on resume.
 */
object StandardSystemBars {
    @JvmStatic
    fun apply(activity: Activity) {
        val window = activity.window ?: return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        show(controller, WindowInsetsCompat.Type.statusBars(), hide = LauncherPrefs.HIDE_STATUS_BAR.get(activity))
        show(controller, WindowInsetsCompat.Type.navigationBars(), hide = LauncherPrefs.HIDE_NAVIGATION_BAR.get(activity))
    }

    private fun show(controller: WindowInsetsControllerCompat, types: Int, hide: Boolean) {
        if (hide) controller.hide(types) else controller.show(types)
    }

    /**
     * Whether this device draws a navigation bar at all (on-screen keys or the gesture handle), read from [view]'s
     * window: the setting is left out where there is none to hide. A bar already hidden still counts.
     */
    @JvmStatic
    fun hasNavigationBar(view: View): Boolean {
        val insets = ViewCompat.getRootWindowInsets(view) ?: return true
        return insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars()).let {
            it.left > 0 || it.top > 0 || it.right > 0 || it.bottom > 0
        }
    }
}
