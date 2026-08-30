package dev.droidtop.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * The widgets/ambient-info surface for the display the Handheld shell is
 * NOT on (docs/SPEC.md §4, handheld dual-screen roles). Needed as a real
 * Activity — `android.app.Presentation` can only target non-default
 * displays, so when the shell moves to the addon (the default under
 * [DisplayRolePrefs.ShellTarget.SECOND_WHEN_PRESENT]) the BUILT-IN screen
 * needs an Activity to host the widgets panel. Shares the exact same
 * [CompanionContent]/[CompanionState] the Presentation path uses — one
 * panel, two hosts.
 *
 * Launch ordering matters (see MainActivity): this is started BEFORE the
 * shell is (re)fronted on its own display, so window focus — and with it
 * every gamepad event — lands on the shell, not here.
 */
class CompanionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) {
                val entry by CompanionState.focusedEntry.collectAsState()
                CompanionContent(entry)
            }
        }
    }
}
