package dev.droidtop.app

import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.droidtop.shell.gamepad.QuickSection
import dev.droidtop.shell.gamepad.hosted.QuickMenuStandalone
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every Quick Menu tab drawn in the in-game overlay's host, a window with no activity (Droidtop/tracker#788: the
 * Downloads tab over RetroArch crashed droidtop with "No ActivityResultRegistryOwner"). Each tab is composed through
 * [InGameOverlays.overlayView], the host the HOME chords use, in a draw-over-apps window on the main screen, and left
 * up for a moment; a tab that throws while composing kills this process and fails the run. Run by hand with
 * `am instrument`, never in CI:
 *
 *     adb shell am instrument -w -e class dev.droidtop.app.OverlayQuickMenuRigTest \
 *         dev.droidtop.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class OverlayQuickMenuRigTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext

    @Test
    fun everyQuickMenuTabComposesInAnActivityLessWindow() {
        // The overlay's window: draw over other apps, granted here as the shell can.
        instrumentation.uiAutomation.executeShellCommand("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow").close()
        Thread.sleep(500)
        assertTrue("draw over other apps was not granted", Settings.canDrawOverlays(app))
        val library = LibraryCore.library(app)
        val wm = app.getSystemService(WindowManager::class.java)
        for (section in QuickSection.entries) {
            var added: Pair<android.view.View, androidx.lifecycle.LifecycleRegistry>? = null
            instrumentation.runOnMainSync {
                val (view, lifecycle) = InGameOverlays.overlayView(app) {
                    QuickMenuStandalone(library = library, onOpenStartMenu = {}, onDismiss = {}, startSection = section)
                }
                wm.addView(
                    view,
                    WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT,
                    ),
                )
                added = view to lifecycle
            }
            // Long enough for the tab's first frames and the data it reads off the main thread.
            Thread.sleep(SHOW_MS)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                added?.let { (view, lifecycle) ->
                    lifecycle.currentState = Lifecycle.State.DESTROYED
                    wm.removeView(view)
                }
            }
            android.util.Log.i("droidtop.InGameOverlay", "rig: $section composed in the overlay host on display ${Display.DEFAULT_DISPLAY}")
        }
    }

    private companion object {
        const val SHOW_MS = 1_500L
    }
}
