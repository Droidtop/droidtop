package dev.droidtop.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.droidtop.display.SecondaryDisplayContent

/**
 * What each mode draws on a secondary screen, registered per mode rather
 * than all at once: a mode that is off registers nothing, so the platform
 * placing :display's SecondaryDisplayActivity can never compose a disabled
 * mode's surface (docs/SPEC.md section 4c and "Modes and what each
 * contributes").
 *
 * Gaming and Desktop each draw either the companion surface or the input
 * surface, per the user's role choice for that mode; Launcher hands off to
 * Launcher3's own secondary-display Activity. The role is read at
 * composition rather than captured once, so a role changed in settings
 * takes effect the next time that screen comes up.
 */
object SecondaryDisplayRegistrations {

    fun registerGaming() = register(SecondaryDisplayContent.Mode.GAMING)

    fun registerDesktop() = register(SecondaryDisplayContent.Mode.DESKTOP)

    fun registerLauncherHandoff() {
        SecondaryDisplayContent.registerHandoff(SecondaryDisplayContent.Mode.STANDARD) { context ->
            runCatching {
                context.startActivity(
                    android.content.Intent().setClassName(
                        context.packageName,
                        "com.android.launcher3.secondarydisplay.SecondaryDisplayLauncher",
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
        }
    }

    private fun register(mode: SecondaryDisplayContent.Mode) {
        SecondaryDisplayContent.register(mode) { Surface(mode) }
    }

    @Composable
    private fun Surface(mode: SecondaryDisplayContent.Mode) {
        val context = androidx.compose.ui.platform.LocalContext.current
        if (SecondScreenInputPrefs.role(context, mode) == SecondScreenInputPrefs.Role.INPUT) {
            SecondScreenInputSurface(mode)
        } else {
            val entry by CompanionState.focusedEntry.collectAsState()
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) {
                CompanionSurfaceHost(entry)
            }
        }
    }
}
