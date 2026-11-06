package dev.droidtop.shell.standard

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display

/**
 * Places droidtop's own SECONDARY_HOME surface (`dev.droidtop.display.SecondaryDisplayActivity`) on every attached
 * presentation display that does not already show it, from the Home path into Standard or an Alternative launcher.
 * Android's own SECONDARY_HOME placement does not re-evaluate a display already mirroring the default one, and on
 * this path no droidtop activity runs the periodic self-heal that would (docs/SPEC.md 4c, "The Alternative forwarder
 * keeps the second screen", which has the rig evidence). A start that fails is logged.
 */
internal fun reassertSecondaryDisplays(context: Context) {
    val displayManager = context.getSystemService(DisplayManager::class.java) ?: return
    displayManager.displays
        .filter { it.displayId != Display.DEFAULT_DISPLAY && (it.flags and Display.FLAG_PRESENTATION) != 0 }
        .filter { it.displayId != dev.droidtop.runtime.SecondaryCover.resumedDisplayId }
        .forEach { display ->
            try {
                // A component name, not a class: :shell-default does not depend on :display.
                context.startActivity(
                    Intent()
                        .setClassName(context.packageName, "dev.droidtop.display.SecondaryDisplayActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.ActivityOptions.makeBasic()
                        .setLaunchDisplayId(display.displayId)
                        .toBundle(),
                )
            } catch (e: Exception) {
                Log.w("droidtop.SecondScreen", "could not place the second-screen surface on display ${display.displayId}", e)
            }
        }
}
