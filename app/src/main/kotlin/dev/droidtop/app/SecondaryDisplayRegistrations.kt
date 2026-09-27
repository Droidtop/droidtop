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
 * Gaming and Desktop each draw either the companion surface or the input
 * surface, per the user's role choice for that mode; Standard draws its
 * own launcher-style surface (StandardSecondScreenSurface) -- shown on the
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

    /** Standard is the always-on default (docs/SPEC.md 2c): it is never gated behind a ModePiece the way Gaming/Desktop's second screens are. */
    fun registerStandard() {
        SecondaryDisplayContent.register(SecondaryDisplayContent.Mode.STANDARD) {
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) {
                StandardSecondScreenSurface()
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
        val context = androidx.compose.ui.platform.LocalContext.current
        if (SecondScreenInputPrefs.role(context, mode) == SecondScreenInputPrefs.Role.INPUT) {
            SecondScreenInputSurface(mode)
        } else {
            val entry = settledFocusedEntry()
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) {
                CompanionSurfaceHost(entry)
            }
        }
    }
}
