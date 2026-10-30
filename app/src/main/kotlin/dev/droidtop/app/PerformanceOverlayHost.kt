package dev.droidtop.app

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import dev.droidtop.runtime.systemstatus.OverlayCorner
import dev.droidtop.runtime.systemstatus.OverlayLevel
import dev.droidtop.runtime.systemstatus.OverlaySize
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
                window.show(
                    app,
                    game.displayId,
                    PerformanceOverlayModel.lines(level, readings).joinToString("\n"),
                    PerformanceOverlay.corner.value,
                    PerformanceOverlay.size.value,
                )
            }
        }
    }
}

/** One text window on one display, kept while the display stays the same. Main thread only. */
private class OverlayWindow {
    private var view: TextView? = null
    private var windowManager: WindowManager? = null
    private var shownOn: Int? = null
    private var shownAt: Pair<OverlayCorner, OverlaySize>? = null

    fun show(app: Context, displayId: Int, text: String, corner: OverlayCorner, size: OverlaySize) {
        val existing = view
        // A new corner or size is a new window; the next refresh (a second at most) brings it.
        if (existing != null && shownOn == displayId && shownAt == (corner to size)) {
            if (existing.text.toString() != text) existing.text = text
            return
        }
        hide()
        val (context, type) = GameOverlay.windowContext(app, displayId) ?: return
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val label = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size.sp)
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
        ).apply {
            gravity = when (corner) {
                OverlayCorner.TOP_START -> Gravity.TOP or Gravity.START
                OverlayCorner.TOP_END -> Gravity.TOP or Gravity.END
                OverlayCorner.BOTTOM_START -> Gravity.BOTTOM or Gravity.START
                OverlayCorner.BOTTOM_END -> Gravity.BOTTOM or Gravity.END
            }
        }
        runCatching { wm.addView(label, params) }
            .onSuccess {
                view = label
                windowManager = wm
                shownOn = displayId
                shownAt = corner to size
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
        shownAt = null
    }
}
