package dev.droidtop.display

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * droidtop's home surface on every secondary display — the single holder
 * of `android.intent.category.SECONDARY_HOME`.
 *
 * This is the platform's own mechanism: Android places a SECONDARY_HOME
 * activity on secondary displays and re-places it when whatever ran there
 * finishes. droidtop previously did that job by hand for the Gaming
 * shell, with a `Presentation` plus `setLaunchDisplayId` relocation and a
 * cooldown guarding a relaunch loop that the code's own comments record as
 * confirmed-live. Reading iiSU (docs/SPEC.md §4c) showed the loop for what
 * it was: the cost of racing the platform for a display the platform was
 * already trying to fill.
 *
 * `singleTop` + `stateNotNeeded` + `excludeFromRecents` match what a home
 * activity needs: it can be re-delivered rather than recreated, it must
 * survive being started with no saved state, and it is not a task the user
 * navigates back through.
 */
class SecondaryDisplayActivity : ComponentActivity() {

    companion object {
        /**
         * Which display this idle SECONDARY_HOME surface is actually
         * resumed on right now -- read by
         * [dev.droidtop.runtime.DualScreenOrchestration.secondScreenNeedsReinit]
         * alongside [SecondScreenPresentation]'s own
         * `display` to tell "the addon has droidtop's idle cover on it"
         * from "nothing of ours is there," the same way
         * `CompanionActivity.visible` already does for the built-in
         * companion. Null while this Activity is not resumed anywhere --
         * a home activity can exist without being the resumed one, e.g.
         * a live Presentation drawn above it on the same display.
         */
        @Volatile
        var resumedDisplayId: Int? = null
            private set
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    /**
     * `singleTop` means an already-resumed instance (this Activity can
     * stay resumed while a Presentation is layered on top of it on the
     * same Display -- Android does not pause the covered Activity just
     * because another window overlaps it) gets `onNewIntent`, not a fresh
     * `onCreate`/`onResume`. Without this, re-asserting this Activity to
     * pick up a mode change (MainActivity dismissing its own Presentation
     * on Home-to-Standard, docs/SPEC.md 4c) was a no-op whenever this
     * Activity had never actually left the resumed state underneath.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        render()
    }

    /** See SecondScreenPresentation.dispatchTouchEvent: the same one-line probe for the idle surface. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            android.util.Log.d("droidtop.SecondScreen", "SecondaryDisplayActivity touch on display ${displayIdCompat()} at ${ev.x.toInt()},${ev.y.toInt()}")
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        // The mode can change while this sits on the other screen (the
        // user switches shells on the main one), and a home activity is
        // resumed rather than recreated, so the content is re-resolved
        // here rather than only at creation.
        render()
        resumedDisplayId = displayIdCompat()
    }

    override fun onPause() {
        if (resumedDisplayId == displayIdCompat()) resumedDisplayId = null
        super.onPause()
    }

    private fun displayIdCompat(): Int? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display?.displayId
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay?.displayId
        }

    private fun render() {
        val mode = SecondaryDisplayContent.currentMode(this)
        val content = SecondaryDisplayContent.contentFor(mode)
        setContent {
            if (content != null) {
                content()
            } else {
                // A mode that registered nothing draws the ground and
                // nothing else. Never a placeholder wordmark -- that was
                // the bug this screen was reported for.
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            }
        }
    }
}
