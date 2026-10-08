package dev.droidtop.app

import androidx.compose.runtime.Composable
import dev.droidtop.display.SecondaryDisplayContent

/**
 * What each mode draws on a secondary screen, registered per mode rather
 * than all at once: a mode that is off registers nothing, so the platform
 * placing :display's SecondaryDisplayActivity can never compose a disabled
 * mode's surface (docs/SPEC.md section 4c and "Modes and what each
 * contributes").
 *
 * Every mode draws the companion tabs (CompanionTabs), opening on the input
 * surface or on Home per the user's role choice for that mode. Standard's
 * registration is always there -- shown on the
 * secondary display whenever droidtop holds the HOME role, whether Home
 * itself renders Standard's own Launcher3 fork or forwards to a different
 * chosen launcher via the Alternative implementation (docs/SPEC.md section
 * 4c, "The Alternative forwarder keeps the second screen"): holding the
 * role is what places [dev.droidtop.display.SecondaryDisplayActivity] on
 * the secondary display, independent of which activity is answering Home
 * on the primary one. The role is read at composition rather than
 * captured once, so a role changed in settings takes effect the next time
 * that screen comes up.
 *
 * Real bug this replaced (owner, 2026-09-27, "it seems we use the same
 * dual screen mode for standard and gaming"): Standard used to hand off
 * to Launcher3's own bare `SecondaryDisplayLauncher` -- a near-empty
 * system stub, not a droidtop surface at all -- while [SecondScreenPresentation]
 * (the LIVE surface, shown whenever MainActivity itself drives the second
 * screen) never branched on mode to begin with and always drew the game
 * companion regardless. Standard now registers real content the same way
 * Gaming and Desktop do, and [SecondScreenPresentation] now reads this
 * SAME registry instead of hardcoding the companion, so the idle and live
 * surfaces can never disagree about what a mode's second screen is.
 */
object SecondaryDisplayRegistrations {

    fun setGaming(enabled: Boolean) = set(SecondaryDisplayContent.Mode.GAMING, enabled)

    fun setDesktop(enabled: Boolean) = set(SecondaryDisplayContent.Mode.DESKTOP, enabled)

    /**
     * Standard is the always-on default (docs/SPEC.md 2c): it is never gated behind a ModePiece the way
     * Gaming/Desktop's second screens are. It draws the same companion tabs as the other modes
     * (Droidtop/tracker#347); its own launcher-style surface used to stand in for them, without Social,
     * Tasks, Performance, System or Keys, and its quick-launch row is now Home's Recent apps section.
     * No shell publishes a focused game in Standard, so Home's Now shows only a running game.
     */
    fun registerStandard() {
        SecondaryDisplayContent.register(SecondaryDisplayContent.Mode.STANDARD) {
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) {
                CompanionTabs(SecondaryDisplayContent.Mode.STANDARD) { CompanionSurfaceHost(null) }
            }
        }
    }

    private fun set(mode: SecondaryDisplayContent.Mode, enabled: Boolean) {
        if (enabled) {
            SecondaryDisplayContent.register(mode) { Surface(mode) }
        } else {
            SecondaryDisplayContent.unregister(mode)
        }
    }

    @Composable
    private fun Surface(mode: SecondaryDisplayContent.Mode) {
        // One tab host for every mode: Home is the companion, the input surface is the default tab
        // where the mode's role says so (Desktop by default), and Tasks, Performance and System are
        // beside them (docs/SPEC.md "The companion's tabs").
        dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) {
            CompanionTabs(mode) { CompanionSurfaceHost(settledFocusedEntry()) }
        }
    }
}
