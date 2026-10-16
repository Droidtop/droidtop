package dev.droidtop.app

import android.app.Application
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import dev.droidtop.library.LaunchAlert
import dev.droidtop.library.LaunchDisplay
import dev.droidtop.library.LaunchWatchdog
import dev.droidtop.runtime.tasks.LaunchLedger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The launch watchdog's alert drawn above the game, on the game's own display (docs/SPEC.md "The launch watchdog"). The
 * shell's dialog is behind the game, and the notification needs droidtop's notification permission and is easy to miss
 * over a full-screen game: on the console both went unseen behind RetroArch (rig, droidtop 0.2.0-dev.1923). The card
 * takes the pad (A for Back to droidtop, B to dismiss) and touch. Back to droidtop brings the shell forward, where the
 * dialog offers Close it and Get the core; Dismiss clears the alert everywhere. It uses [GameOverlay], so it needs
 * droidtop's accessibility service or "Display over other apps"; without either the notification is the only sign.
 */
internal object LaunchAlertOverlay {
    private const val TAG = "droidtop.LaunchAlert"
    private lateinit var app: Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var view: View? = null
    private var windowManager: WindowManager? = null

    fun install(application: Application) {
        val main = Build.VERSION.SDK_INT < 28 || Application.getProcessName() == application.packageName
        if (!main) return
        app = application
        scope.launch {
            LaunchWatchdog.alert.collect { alert -> if (alert == null) hide() else show(alert) }
        }
    }

    private fun show(alert: LaunchAlert) {
        hide()
        val displayId = LaunchDisplay.parkedDisplayId ?: LaunchLedger.last?.displayId ?: return
        val (windowContext, type) = GameOverlay.windowContext(app, displayId) ?: run {
            Log.i(TAG, "no overlay on display $displayId (no accessibility service, no Display over other apps)")
            return
        }
        // droidtop's palette in the current appearance (docs/SPEC.md 7k, "Light and dark"), not a fixed dark card.
        val palette = dev.droidtop.shell.gamepad.GamingTheme.palette
        val dialogTheme = if (palette.colors.isLight) android.R.style.Theme_DeviceDefault_Light_Dialog else android.R.style.Theme_DeviceDefault_Dialog
        val context = ContextThemeWrapper(windowContext, dialogTheme)
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val back = Button(context).apply {
            text = "Back to droidtop"
            setOnClickListener {
                hide()
                LaunchWatchdog.returnToShell(app)
            }
        }
        val dismiss = Button(context).apply {
            text = "Dismiss"
            setOnClickListener { LaunchWatchdog.dismiss() }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
            background = GradientDrawable().apply {
                setColor(palette.overlaySurface.copy(alpha = 0.94f).toArgb())
                cornerRadius = dp(16).toFloat()
            }
            addView(TextView(context).apply {
                text = alert.message
                setTextColor(palette.onSurface.toArgb())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    setPadding(0, dp(12), 0, 0)
                    addView(dismiss)
                    addView(back)
                },
            )
            isFocusable = true
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_UP) return@setOnKeyListener keyCode in KEYS
                when (keyCode) {
                    KeyEvent.KEYCODE_BUTTON_A -> back.performClick()
                    KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> dismiss.performClick()
                    else -> return@setOnKeyListener false
                }
                true
            }
        }
        val width = minOf(context.resources.displayMetrics.widthPixels - dp(32), dp(560))
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // Focusable on purpose: the pad's A and B must reach the card, not the stuck game under it.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        runCatching { wm.addView(card, params) }
            .onSuccess {
                view = card
                windowManager = wm
                back.requestFocus()
                Log.i(TAG, "alert above the game on display $displayId")
            }
            .onFailure { Log.w(TAG, "alert on display $displayId refused", it) }
    }

    private fun hide() {
        val shown = view ?: return
        runCatching { windowManager?.removeView(shown) }
        view = null
        windowManager = null
    }

    private val KEYS = setOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK)
}
