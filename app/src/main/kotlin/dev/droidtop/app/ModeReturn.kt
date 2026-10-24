package dev.droidtop.app

import android.content.Context
import android.content.Intent
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.Modes
import dev.droidtop.shell.standard.BackButtonMenu
import dev.droidtop.shell.standard.HomeRolePrefs

/**
 * Back to the mode the person is in (docs/SPEC.md 2c, "A mode never opens another"; owner, 2026-10-24,
 * Droidtop/tracker#639: "we absolutely should NOT be seeing jumps between modes"): Gaming's or Desktop's own screen
 * brought to the front, or the Android home screen droidtop holds. A notification that means only "back to droidtop"
 * uses it. MainActivity with no mode named opens the DEFAULT mode, so a person in Desktop with Gaming as their default
 * was sent to Gaming.
 */
object ModeReturn {
    fun intent(context: Context): Intent = when (val mode = Mode.byId(Modes.lastMode(context))) {
        Mode.GAMING, Mode.DESKTOP -> Intent(context, MainActivity::class.java)
            .putExtra(BackButtonMenu.EXTRA_MODE, mode.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        Mode.LAUNCHER, null -> BackButtonMenu.homeIntent(context, HomeRolePrefs.activeHomeImplementation(context))
    }
}
