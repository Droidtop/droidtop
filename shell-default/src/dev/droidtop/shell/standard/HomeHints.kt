package dev.droidtop.shell.standard

import android.app.Activity
import com.android.launcher3.R
import dev.droidtop.library.settings.Hints
import dev.droidtop.ui.shared.HintViews

/**
 * Mobile's in-place hints (docs/SPEC.md 2c, Droidtop/tracker#829). The one today: long-press Home is the way to the
 * mode switcher, and the long press alone is invisible to a newcomer, so the home screen says so ("Press and hold Home
 * to switch modes") at its foot until the person has opened the switcher or tapped Got it. Not before setup is done:
 * onboarding teaches its own steps.
 */
object HomeHints {
    @JvmStatic
    fun onHomeShown(activity: Activity) {
        HintViews.show(activity, Hints.HOLD_HOME, activity.getString(R.string.hint_hold_home)) { OnboardingGate.isComplete(it) }
    }
}
