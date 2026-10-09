package dev.droidtop.display

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.droidtop.runtime.DisplayOutputRepository
import dev.droidtop.runtime.DualScreenOrchestration

/**
 * A companion surface never stands in for the main UI (docs/SPEC.md 4c, "Second display: the Main
 * screen choice moves the shell both ways", Droidtop/tracker#182).
 *
 * The shell's orchestration only runs while a shell instance exists, so it cannot be the one thing that
 * keeps the companion activity and the idle cover off a lone screen: when the add-on display goes away,
 * Android moves the tasks that were on it to the built-in display, the idle cover included, and a
 * companion left there with no shell alive is all that screen shows. So each surface checks for itself,
 * when it is started and whenever a display is removed, and finishes when it has no role: one display
 * left, or (for the idle cover, [secondaryOnly]) it now sits on the built-in display. Finishing shows
 * whatever is under it there: the shell, or Android's home.
 */
object CompanionSurfaceLifetime {
    private const val TAG = "droidtop.SecondScreen"

    /** Call from the activity's onCreate, after super.onCreate. */
    fun bind(activity: ComponentActivity, secondaryOnly: Boolean) {
        val displayManager = activity.getSystemService(DisplayManager::class.java) ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = retireIfUnwanted(activity, secondaryOnly)
        }
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_CREATE ->
                        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
                    Lifecycle.Event.ON_START -> retireIfUnwanted(activity, secondaryOnly)
                    Lifecycle.Event.ON_DESTROY -> displayManager.unregisterDisplayListener(listener)
                    else -> Unit
                }
            },
        )
    }

    private fun retireIfUnwanted(activity: ComponentActivity, secondaryOnly: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return
        val displayCount = DisplayOutputRepository(activity).currentOutputsSnapshot().size
        val displayId = displayIdOf(activity)
        if (!DualScreenOrchestration.companionSurfaceRetires(displayCount, displayId, secondaryOnly)) return
        Log.i(TAG, "${activity.javaClass.simpleName} on display $displayId with $displayCount display(s): finishing, the main UI shows there")
        activity.finish()
    }

    private fun displayIdOf(activity: ComponentActivity): Int? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            activity.display?.displayId
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay?.displayId
        }
}
