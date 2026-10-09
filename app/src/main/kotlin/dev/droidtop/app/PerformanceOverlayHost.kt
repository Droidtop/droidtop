package dev.droidtop.app

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import dev.droidtop.runtime.systemstatus.OverlayLevel
import dev.droidtop.runtime.systemstatus.PerformanceOverlay
import dev.droidtop.runtime.systemstatus.PerformanceOverlayModel
import dev.droidtop.runtime.tasks.LaunchLedger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Draws the performance overlay over the game (docs/SPEC.md, "Performance overlay"): a small, never-focused,
 * never-touchable text window in a corner of the display the game was launched on. It exists only while the chosen
 * level is not Off and a game droidtop launched is believed to be running ([LaunchLedger]); [PerformanceOverlay.watch]
 * samples only then. droidtop's accessibility overlay carries it when that service is on (no grant), else an app
 * overlay with "Display over other apps". Main process only.
 */
object PerformanceOverlayHost {
    private lateinit var app: Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val window = OverlayWindow()

    fun install(application: Application) {
        val main = Build.VERSION.SDK_INT < 28 || Application.getProcessName() == application.packageName
        if (!main) return
        app = application
        scope.launch {
            withContext(Dispatchers.IO) { PerformanceOverlay.load(app) }
            PerformanceOverlay.level.collectLatest { level ->
                if (level == OverlayLevel.OFF) {
                    window.hide()
                } else {
                    try {
                        showWhile(level)
                    } finally {
                        window.hide()
                    }
                }
            }
        }
    }

    private suspend fun showWhile(level: OverlayLevel): Nothing = coroutineScope {
        launch(Dispatchers.Default) { PerformanceOverlay.watch(app, level) { LaunchLedger.last?.packageName } }
        PerformanceOverlay.readings.collect { readings ->
            val game = LaunchLedger.last
            if (game == null) {
                window.hide()
            } else {
                window.show(app, game.displayId, PerformanceOverlayModel.lines(level, readings).joinToString("\n"))
            }
        }
    }
}

/** One text window on one display, kept while the display stays the same. Main thread only. */
private class OverlayWindow {
    private var view: TextView? = null
    private var windowManager: WindowManager? = null
    private var shownOn: Int? = null

    fun show(app: Context, displayId: Int, text: String) {
        val existing = view
        if (existing != null && shownOn == displayId) {
            if (existing.text.toString() != text) existing.text = text
            return
        }
        hide()
        val (context, type) = AccessibilityTyping.overlayContext(displayId)?.let { it to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY }
            ?: appOverlayContext(app, displayId)?.let { it to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY }
            ?: return
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val label = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            val pad = (6 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { wm.addView(label, params) }
            .onSuccess {
                view = label
                windowManager = wm
                shownOn = displayId
                Log.i("droidtop.perfoverlay", "overlay on display $displayId")
            }
            .onFailure { Log.w("droidtop.perfoverlay", "overlay on display $displayId refused", it) }
    }

    fun hide() {
        val label = view ?: return
        runCatching { windowManager?.removeView(label) }
        view = null
        windowManager = null
        shownOn = null
    }

    private fun appOverlayContext(app: Context, displayId: Int): Context? {
        if (!Settings.canDrawOverlays(app)) return null
        val display: Display = app.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return null
        val displayContext = app.createDisplayContext(display)
        return if (Build.VERSION.SDK_INT >= 30) {
            displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            displayContext
        }
    }
}
