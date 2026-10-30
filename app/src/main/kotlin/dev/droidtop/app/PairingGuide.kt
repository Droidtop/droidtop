package dev.droidtop.app

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.droidtop.pluginhost.ElevatedAccessHost
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The floating pairing guide (DroidDeck's Settings guide, Droidtop/tracker#363; the pairing is #816's). Wireless
 * debugging's code shows in Settings and goes when Settings leaves the screen, so the code is typed somewhere that
 * floats over it. The notification's reply ([WirelessPairing]) does that on a phone, but a pad cannot reach the
 * notification shade. This small window over Settings can: it says the steps, and while the person moves through
 * Settings it takes no focus, so the pad drives Settings. Once Settings shows a code (Android advertises the pairing
 * service only then, [dev.droidtop.pluginhost.WirelessDebuggingOps.pairingCodeShowing]) it takes the focus with its
 * code field, and pairs through the same [dev.droidtop.pluginhost.WirelessDebuggingOps.pair] the notification uses:
 * one pairing, two ways to type the code. Drawn only when droidtop may draw over other apps; otherwise the notification
 * is the way. Nothing here blocks the main thread: the code is looked for, and the pairing done, on droidtop's process
 * scope.
 */
object PairingGuide {
    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var watching: Job? = null

    /** Shows the guide over whatever is in front (Settings, opened by [WirelessPairing.start]). False when it cannot be drawn. */
    fun show(context: Context): Boolean {
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) return false
        main.post { attach(app) }
        return true
    }

    fun hide() {
        main.post {
            watching?.cancel()
            watching = null
            val view = root ?: return@post
            root = null
            params = null
            runCatching { view.context.getSystemService(WindowManager::class.java)?.removeView(view) }
        }
    }

    private fun attach(app: Context) {
        if (root != null) return
        val manager = app.getSystemService(WindowManager::class.java) ?: return
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), app.resources.displayMetrics).toInt()
        // The app theme's own roles: the window is drawn outside every shell, over Settings.
        val themed = android.view.ContextThemeWrapper(app, R.style.Theme_DroidTop)
        val roles = themed.obtainStyledAttributes(
            intArrayOf(android.R.attr.colorBackground, android.R.attr.textColorPrimary, android.R.attr.textColorSecondary),
        )
        val plate = roles.getColor(0, 0)
        val ink = roles.getColor(1, 0)
        val muted = roles.getColor(2, ink)
        roles.recycle()
        val status = TextView(themed).apply {
            setTextColor(muted)
            textSize = 14f
            text = "Waiting for Settings to show a pairing code"
        }
        val code = EditText(themed).apply {
            hint = "Six-digit code"
            setTextColor(ink)
            setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            imeOptions = EditorInfo.IME_ACTION_DONE
            isEnabled = false
        }
        val pair = Button(themed).apply {
            text = "Pair"
            isEnabled = false
        }
        val close = Button(themed).apply { text = "Close" }
        val panel = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(plate)
                cornerRadius = dp(12).toFloat()
            }
            addView(TextView(themed).apply {
                text = "Pair droidtop with wireless debugging"
                setTextColor(ink)
                textSize = 16f
            })
            addView(TextView(themed).apply {
                text = "1. Turn on Wireless debugging.\n2. Open it and choose Pair device with pairing code.\n3. Type the six digits it shows here."
                setTextColor(ink)
                textSize = 14f
                setPadding(0, dp(6), 0, dp(6))
            })
            addView(status)
            addView(code)
            addView(LinearLayout(themed).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(close)
                addView(pair)
            })
        }
        val layout = WindowManager.LayoutParams(
            dp(340),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable until a code shows: the pad and the keyboard keep driving Settings underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(16)
            y = dp(16)
        }
        if (runCatching { manager.addView(panel, layout) }.isFailure) return
        root = panel
        params = layout

        fun setFocusable(on: Boolean) {
            val p = params ?: return
            val flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (flags == p.flags) return
            p.flags = flags
            runCatching { manager.updateViewLayout(panel, p) }
            code.isEnabled = on
            pair.isEnabled = on
            if (on) {
                status.text = "Type the six digits Settings shows"
                code.requestFocus()
                app.getSystemService(InputMethodManager::class.java)?.showSoftInput(code, 0)
            } else {
                status.text = "Waiting for Settings to show a pairing code"
            }
        }

        fun submit() {
            val digits = code.text.toString().filter(Char::isDigit)
            if (digits.length != 6) {
                status.text = "That wasn't six digits"
                return
            }
            status.text = "Pairing…"
            pair.isEnabled = false
            dev.droidtop.runtime.util.AppScopes.io.launch {
                val outcome = runCatching { ElevatedAccessHost.wireless(app).pair(digits) }
                main.post {
                    if (outcome.isSuccess) {
                        WirelessPairing.paired(app)
                        status.text = "Paired. droidtop can use it while Wireless debugging is on"
                        main.postDelayed({ hide() }, DONE_SHOWN_MS)
                    } else {
                        status.text = outcome.exceptionOrNull()?.message ?: "That code didn't pair. Check it and try again"
                        pair.isEnabled = true
                        code.text.clear()
                    }
                }
            }
        }

        pair.setOnClickListener { submit() }
        close.setOnClickListener { hide() }
        code.setOnEditorActionListener { _, action, _ -> (action == EditorInfo.IME_ACTION_DONE).also { if (it) submit() } }
        // B or Back closes the guide while it has the focus.
        panel.isFocusableInTouchMode = false
        panel.setOnKeyListener { _, keyCode, event ->
            val back = keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B
            if (back && event.action == KeyEvent.ACTION_UP) hide()
            back
        }

        // Watches for the code: it takes the focus while one shows and gives it back when the dialog closes.
        val wireless = ElevatedAccessHost.wireless(app)
        watching = dev.droidtop.runtime.util.AppScopes.io.launch {
            while (isActive) {
                val showing = runCatching { wireless.pairingCodeShowing(LOOK_MS) }.getOrDefault(false)
                main.post { if (root === panel) setFocusable(showing) }
                delay(LOOK_EVERY_MS)
            }
        }
    }

    /** How long one look for the pairing service waits, and the pause between looks. */
    private const val LOOK_MS = 1_500L
    private const val LOOK_EVERY_MS = 1_000L

    /** How long "Paired" stays before the guide goes. */
    private const val DONE_SHOWN_MS = 2_500L
}
