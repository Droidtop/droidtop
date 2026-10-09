package dev.droidtop.runtime

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager

/**
 * The companion on a device with one screen (docs/SPEC.md "The companion's tabs", One screen; Droidtop/tracker#414
 * slice C13): the same tabs in a pull-down sheet over Standard's home screen and in a tray panel over Desktop's
 * taskbar, drawn by `:app`'s sheet activity. The launcher and the Desktop shell cannot name that activity, so they open
 * it by its action here, and offer the way in only while there is no second screen to hold the companion.
 */
object CompanionSheet {
    const val ACTION = "dev.droidtop.app.action.COMPANION_SHEET"
    const val EXTRA_TRAY = "dev.droidtop.app.extra.COMPANION_TRAY"

    /** Whether the one-screen sheet is offered: only with no other screen for the companion. Pure. */
    fun offered(displayCount: Int): Boolean = displayCount <= 1

    /** [offered] for this device now (Android's display list: a quick system call). */
    @JvmStatic
    fun offered(context: Context): Boolean =
        offered(context.getSystemService(DisplayManager::class.java)?.displays?.size ?: 1)

    /** Opens the sheet ([tray] false, Standard) or the tray panel ([tray] true, Desktop) over whatever is in front. */
    @JvmStatic
    fun open(context: Context, tray: Boolean) {
        val intent = Intent(ACTION)
            .setPackage(context.packageName)
            .putExtra(EXTRA_TRAY, tray)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
