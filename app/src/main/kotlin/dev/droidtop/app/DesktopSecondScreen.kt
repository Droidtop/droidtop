package dev.droidtop.app

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.droidtop.hostbridge.DesktopOutputs
import dev.droidtop.runtime.DisplayOutput
import dev.droidtop.shell.desktop.DesktopSecondOutput
import java.lang.ref.WeakReference

/**
 * The Linux desktop on a second Android display (Droidtop/tracker#908, step D3): [show] gives the display an output
 * of the desktop's ([DesktopSessionService.extendTo], step D2) and opens [DesktopSecondScreenActivity] there, which
 * draws that output with a bar of its own and takes touch for it; [hide] closes it and removes the output
 * ([DesktopSessionService.retract]).
 *
 * [DesktopScreens] calls them (step D5): the person is asked once per display whether the desktop extends to it, and
 * the answer, changeable in Settings > Displays, decides. Main thread.
 */
object DesktopSecondScreen {
    private const val TAG = "droidtop.DesktopSecond"

    /** The host open on each display, by display id. Main thread. */
    private val hosts = mutableMapOf<Int, WeakReference<Activity>>()

    /**
     * Extends the running desktop to [display] and opens it there. False when no desktop is running, [display] is
     * the one the desktop's primary output is on, or Android refused to open a window there. Calling it again for a
     * display already showing the desktop only sizes and places its output again.
     */
    fun show(context: Context, display: DisplayOutput): Boolean {
        val session = DesktopSessionService.state.value as? DesktopSessionState.Connected ?: return false
        if (display.androidDisplayId == session.primaryOutput.androidDisplayId) return false
        if (!DesktopSessionService.extendTo(display)) return false
        if (shownOn(display.androidDisplayId)) return true
        return runCatching {
            context.startActivity(
                Intent(context, DesktopSecondScreenActivity::class.java)
                    .putExtra(DesktopSecondScreenActivity.EXTRA_DISPLAY_ID, display.androidDisplayId)
                    // A task of its own on that display, one per display.
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(display.androidDisplayId).toBundle(),
            )
            true
        }.onFailure { Log.w(TAG, "Opening the desktop on display ${display.androidDisplayId} was refused", it) }
            .getOrDefault(false)
    }

    /** Closes the desktop on the display [displayId] and removes its output; nothing when it is not shown there. */
    fun hide(displayId: Int) {
        hosts.remove(displayId)?.get()?.finish()
        DesktopSessionService.retract(displayId)
    }

    /** Whether the desktop is open on the display [displayId]. */
    fun shownOn(displayId: Int): Boolean = hosts[displayId]?.get()?.let { !it.isFinishing && !it.isDestroyed } == true

    internal fun attached(displayId: Int, host: Activity) {
        hosts[displayId] = WeakReference(host)
    }

    internal fun detached(displayId: Int, host: Activity) {
        if (hosts[displayId]?.get() === host) hosts.remove(displayId)
    }

    /**
     * The host's surface on [displayId] is [width] x [height] pixels at [densityDpi]: its output is extended again at
     * that size (the bar takes the rest of the screen), sized as [DesktopSessionService.extendTo] sizes it from the
     * Desktop scale and resolution settings. Kept with the output, so a later re-place keeps this size too.
     */
    internal fun surfaceSized(displayId: Int, width: Int, height: Int, densityDpi: Int) {
        val session = DesktopSessionService.state.value as? DesktopSessionState.Connected ?: return
        val output = DesktopOutputs.outputFor(session.outputs, displayId) ?: return
        val display = output.display
        if (display.widthPx == width && display.heightPx == height && display.densityDpi == densityDpi) return
        DesktopSessionService.extendTo(display.copy(widthPx = width, heightPx = height, densityDpi = densityDpi))
    }
}

/**
 * The window [DesktopSecondScreen.show] opens on a second display: the desktop's output for that display
 * ([DesktopSecondOutput]). Touch-only, as the companion's window is (SecondScreenWindowFlags): a tap here never takes
 * the system's focused display from the primary, so the pad and the keys stay with the primary surface, which feeds
 * the same seat. It finishes when the session ends, when its output is removed, and, as the companion does, when its
 * display goes (CompanionSurfaceLifetime).
 */
class DesktopSecondScreenActivity : ComponentActivity() {
    private var displayId = android.view.Display.INVALID_DISPLAY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, android.view.Display.INVALID_DISPLAY)
        val here = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display?.displayId
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay?.displayId
        }
        // Android put it somewhere else (the display went while it opened): it has nothing to show there.
        if (here != displayId) {
            Log.w("droidtop.DesktopSecond", "Opened on display $here, not $displayId: closing")
            finish()
            return
        }
        window.addFlags(dev.droidtop.display.SecondScreenWindowFlags.touchOnly())
        dev.droidtop.display.CompanionSurfaceLifetime.bind(this, secondaryOnly = true)
        DesktopSecondScreen.attached(displayId, this)
        // Back has nothing to go back to here; closing the desktop's second screen is [DesktopSecondScreen.hide].
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        val shownDisplay = displayId
        setContent {
            val state by DesktopSessionService.state.collectAsState()
            val session = state as? DesktopSessionState.Connected
            val outputName = session?.let { DesktopOutputs.surfaceOutput(it.outputs, shownDisplay) }
            var hadOutput by remember { mutableStateOf(false) }
            LaunchedEffect(session == null) { if (session == null) finish() }
            // The output can arrive after this window (the session is still making it); once it was here and is
            // gone, the display no longer has the desktop.
            LaunchedEffect(outputName) {
                if (outputName != null) hadOutput = true else if (hadOutput) finish()
            }
            if (session != null) {
                DesktopSecondOutput(
                    hostBridge = session.hostBridge,
                    outputName = outputName,
                    outputCount = session.outputs.size,
                    windows = session.windows,
                    onSurfaceSize = { width, height, dpi -> DesktopSecondScreen.surfaceSized(shownDisplay, width, height, dpi) },
                )
            }
        }
    }

    // Touch-only, except while Android has made it the top activity: then the pad goes back to the shell
    // (TouchOnlySurfaceFocus), as from the companion.
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        dev.droidtop.display.TouchOnlySurfaceFocus.onTopResumedChanged(this, isTopResumedActivity, displayId)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        dev.droidtop.display.TouchOnlySurfaceFocus.consumesKey(event) || super.dispatchKeyEvent(event)

    override fun onDestroy() {
        DesktopSecondScreen.detached(displayId, this)
        super.onDestroy()
    }

    companion object {
        /** The display this window is for: it closes when Android opens it anywhere else. */
        const val EXTRA_DISPLAY_ID = "dev.droidtop.app.extra.DESKTOP_DISPLAY_ID"
    }
}
