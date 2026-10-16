package dev.droidtop.display

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/**
 * The one place droidtop decides what the secondary screen shows.
 *
 * Before this module there were two implementations of the same job:
 * `shell-default` carried Launcher3's own `SecondaryDisplayLauncher`
 * registered for `android.intent.category.SECONDARY_HOME`, and the
 * Gaming shell separately pushed a `Presentation` onto the second
 * display and relocated itself with `setLaunchDisplayId`. Both wanted the
 * same display, and the platform — which places a `SECONDARY_HOME`
 * activity on secondary displays itself — was a third party to the
 * argument. That competition is documented in docs/SPEC.md §4c along with
 * the symptoms it produced.
 *
 * Now: one activity holds the category, and the active mode selects what
 * it renders. Per direction — no duplication, a single controlling
 * configuration read by every mode.
 */
object SecondaryDisplayContent {

    /** Which shell is currently active; the one input this module's behaviour keys on. */
    enum class Mode { STANDARD, GAMING, DESKTOP }

    /**
     * What a mode draws on the secondary screen. Registered by whoever
     * owns that mode's UI rather than pulled in here, so this module never
     * needs to depend on the shells — the same seam pattern
     * `PcGameRuntimeRegistry` and `SettingsScreenRegistry` already use.
     */
    private val contents = mutableMapOf<Mode, @Composable () -> Unit>()

    fun register(mode: Mode, content: @Composable () -> Unit) {
        contents[mode] = content
    }

    /**
     * Takes a mode's surface back off the secondary screen. A mode
     * switched OFF mid-session used to leave its registration behind, so
     * the platform could still compose a disabled mode's surface until the
     * process next started; [dev.droidtop.app.ModeStartup] now calls this
     * from the same pass that stops everything else the mode contributes.
     * Standard is never unregistered — see
     * [dev.droidtop.app.SecondaryDisplayRegistrations.registerStandard].
     */
    fun unregister(mode: Mode) {
        contents -= mode
    }

    // Public, not internal: SecondScreenPresentation (:app) reads the
    // same registry SecondaryDisplayActivity (this module) does, so the
    // idle and live surfaces can never disagree about a mode's content.
    fun contentFor(mode: Mode): (@Composable () -> Unit)? = contents[mode]

    /**
     * The active mode, from the one place that owns mode state
     * ([dev.droidtop.library.settings.Modes]). This module keeps its own
     * [Mode] enum because what a secondary screen draws is its own
     * concern -- but it no longer reads the preference by key behind
     * Modes' back, which was a second mechanism for one job.
     */
    fun currentMode(context: Context): Mode =
        when (dev.droidtop.library.settings.Modes.lastMode(context)) {
            dev.droidtop.library.settings.Mode.GAMING.id -> Mode.GAMING
            dev.droidtop.library.settings.Mode.DESKTOP.id -> Mode.DESKTOP
            else -> Mode.STANDARD
        }

    /**
     * [currentMode], followed: a second screen stays up across a mode switch, so every host reads the mode here and
     * redraws for the new one (rig, droidtop 0.2.0-dev.1931: after Desktop to Gaming the companion kept Desktop's
     * keyboard pad and Input tab, read once when it came up).
     */
    @Composable
    fun followedMode(context: Context): Mode {
        val switched by dev.droidtop.library.settings.Modes.lastModeFlow.collectAsState()
        return remember(switched) { currentMode(context) }
    }
}
