package dev.droidtop.shell.standard

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * Explicitly places droidtop's own SECONDARY_HOME surface
 * (`dev.droidtop.display.SecondaryDisplayActivity`) on every attached
 * secondary display, rather than waiting on the platform's own
 * SECONDARY_HOME placement to notice on its own.
 *
 * Real gap this closes (rig, owner correction 2026-09-27, "The Alternative
 * forwarder keeps the second screen"): confirmed live that after droidtop
 * self-updates (a fresh process start with no droidtop Activity resumed
 * anywhere) and the person is on the Alternative-forwarded launcher, the
 * secondary display stayed on Android's OWN mirror-of-the-default-display
 * fallback rather than picking up droidtop's newly-registered Standard
 * content -- the platform's automatic SECONDARY_HOME placement does not
 * proactively re-evaluate an already-established mirror on its own; it
 * needs a nudge. `LaunchDisplay.coverVacatedDisplays` already established
 * this exact pattern for the same underlying platform behaviour (its own
 * doc comment: "The platform's own SECONDARY_HOME placement can't be
 * relied on for this ... so droidtop places its own"), for game launches;
 * this reuses it for the OTHER place droidtop can lose the second screen
 * to a mirror -- the Home/Alternative-forward path, where no droidtop
 * Activity is left running anywhere to run the periodic self-heal
 * (docs/SPEC.md 4c, "Self-detection, built") that would otherwise catch
 * this a few seconds later.
 */
internal fun reassertSecondaryDisplays(context: Context) {
    val displayManager = context.getSystemService(DisplayManager::class.java) ?: return
    displayManager.displays
        .filter { it.displayId != Display.DEFAULT_DISPLAY && (it.flags and Display.FLAG_PRESENTATION) != 0 }
        .forEach { display ->
            runCatching {
                // A string component name, not a class reference: :shell-default
                // does not (and should not) depend on :display, the same
                // reason HomeTrampolineActivity names dev.droidtop.app.MainActivity
                // as a string rather than importing it.
                context.startActivity(
                    Intent()
                        .setClassName(context.packageName, "dev.droidtop.display.SecondaryDisplayActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.ActivityOptions.makeBasic()
                        .setLaunchDisplayId(display.displayId)
                        .toBundle(),
                )
            }
        }
}
