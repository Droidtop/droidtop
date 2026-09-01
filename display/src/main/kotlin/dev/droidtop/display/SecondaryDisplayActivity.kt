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
 * finishes. droidtop previously did that job by hand for the Handheld
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        // The mode can change while this sits on the other screen (the
        // user switches shells on the main one), and a home activity is
        // resumed rather than recreated, so the content is re-resolved
        // here rather than only at creation.
        render()
    }

    private fun render() {
        val mode = SecondaryDisplayContent.currentMode(this)

        // A mode that answers with its own Activity gets first refusal:
        // Standard hands off to Launcher3's secondary-display UI, which
        // is an Activity and cannot be composed into this one.
        if (SecondaryDisplayContent.handoffFor(mode)?.invoke(this) == true) {
            finish()
            return
        }

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
