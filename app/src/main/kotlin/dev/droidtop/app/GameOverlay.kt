package dev.droidtop.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.WindowManager

/**
 * The one way droidtop draws over a game it launched (docs/SPEC.md "Performance overlay", "The launch watchdog"): droidtop's
 * accessibility overlay when that service is on (no grant), else an app overlay with "Display over other apps". Main
 * process only.
 */
internal object GameOverlay {
    /** A context for a window on [displayId] and the window type it takes, or null when droidtop may not draw there. */
    fun windowContext(app: Context, displayId: Int): Pair<Context, Int>? =
        AccessibilityTyping.overlayContext(displayId)?.let { it to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY }
            ?: appOverlayContext(app, displayId)?.let { it to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY }

    private fun appOverlayContext(app: Context, displayId: Int): Context? {
        if (!Settings.canDrawOverlays(app)) return null
        val display = app.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return null
        val displayContext = app.createDisplayContext(display)
        return if (Build.VERSION.SDK_INT >= 30) {
            displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            displayContext
        }
    }
}
