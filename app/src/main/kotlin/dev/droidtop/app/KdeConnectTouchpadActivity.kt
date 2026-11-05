package dev.droidtop.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.droidtop.net.kdeconnect.KdeConnect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/**
 * droidtop as the controller of a paired device's pointer (docs/SPEC.md 7q, remote input; Droidtop/tracker#645, "droidtop
 * as both the controlled device and the controller"), the touchpad of the official apps' Remote input:
 * - the screen: one finger moves the pointer, a tap clicks, two fingers scroll, a two-finger tap right-clicks;
 * - a controller: the left stick moves the pointer, the right stick scrolls, A clicks, X right-clicks, Y middle-clicks,
 *   the D-pad sends the arrow keys, Start sends Enter, and B leaves.
 * Moves are gathered and sent at most every 16 ms, through lib-kdeconnect's `input_send`.
 */
class KdeConnectTouchpadActivity : AppCompatActivity() {
    private lateinit var deviceId: String
    private var name = ""

    // What is waiting to be sent, gathered between sends.
    private var pendingX = 0f
    private var pendingY = 0f
    private var pendingScroll = 0f

    // The sticks' positions, read on each tick.
    private var stickX = 0f
    private var stickY = 0f
    private var scrollStick = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = intent.getStringExtra(EXTRA_ID) ?: return finish()
        name = intent.getStringExtra(EXTRA_NAME) ?: ""
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(Pad(this))
        // The tick runs on the main thread, where the touch and stick values are written; only the sends leave it.
        lifecycleScope.launch {
            while (isActive) {
                delay(TICK_MS)
                pendingX += dead(stickX) * STICK_SPEED
                pendingY += dead(stickY) * STICK_SPEED
                pendingScroll += dead(scrollStick) * STICK_SCROLL
                val (x, y, s) = Triple(pendingX, pendingY, pendingScroll)
                pendingX = 0f
                pendingY = 0f
                pendingScroll = 0f
                if (x != 0f || y != 0f) send(JSONObject().put("dx", x.toDouble()).put("dy", y.toDouble()))
                if (s != 0f) send(JSONObject().put("dy", s.toDouble()).put("scroll", true))
            }
        }
    }

    private fun dead(v: Float) = if (abs(v) < 0.15f) 0f else v

    private fun send(input: JSONObject) {
        lifecycleScope.launch(Dispatchers.IO) { KdeConnect.pointer(deviceId, input) }
    }

    private fun click(kind: String) = send(JSONObject().put("click", kind))

    private fun key(special: String) = send(JSONObject().put("special_key", special))

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.action == MotionEvent.ACTION_MOVE) {
            stickX = event.getAxisValue(MotionEvent.AXIS_X)
            stickY = event.getAxisValue(MotionEvent.AXIS_Y)
            scrollStick = event.getAxisValue(MotionEvent.AXIS_RZ)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount > 0 && keyCode !in ARROWS) return true
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> click("single")
            KeyEvent.KEYCODE_BUTTON_X -> click("right")
            KeyEvent.KEYCODE_BUTTON_Y -> click("middle")
            KeyEvent.KEYCODE_BUTTON_START -> key("enter")
            KeyEvent.KEYCODE_DPAD_UP -> key("up")
            KeyEvent.KEYCODE_DPAD_DOWN -> key("down")
            KeyEvent.KEYCODE_DPAD_LEFT -> key("left")
            KeyEvent.KEYCODE_DPAD_RIGHT -> key("right")
            KeyEvent.KEYCODE_BUTTON_B -> finish()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    /** The touch surface, with one line saying what the controls do. */
    @SuppressLint("ViewConstructor")
    private inner class Pad(context: Context) : View(context) {
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
            textAlign = Paint.Align.CENTER
            color = android.graphics.Color.GRAY
        }
        private var lastX = 0f
        private var lastY = 0f
        private var downAt = 0L
        private var travelled = 0f
        private var fingers = 0

        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val lines = listOf(
                getString(R.string.kdeconnect_touchpad_title, name),
                getString(R.string.kdeconnect_touchpad_help),
            )
            lines.forEachIndexed { i, line -> canvas.drawText(line, width / 2f, height / 2f + i * text.textSize * 1.6f, text) }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = SystemClock.uptimeMillis()
                    travelled = 0f
                    fingers = 1
                    lastX = e.x
                    lastY = e.y
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    fingers = maxOf(fingers, e.pointerCount)
                    lastX = e.getX(0)
                    lastY = e.getY(0)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.getX(0) - lastX
                    val dy = e.getY(0) - lastY
                    lastX = e.getX(0)
                    lastY = e.getY(0)
                    travelled += hypot(dx, dy)
                    if (e.pointerCount >= 2) pendingScroll += -dy * TOUCH_SCROLL else {
                        pendingX += dx * TOUCH_SPEED
                        pendingY += dy * TOUCH_SPEED
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val tap = SystemClock.uptimeMillis() - downAt < TAP_MS && travelled < TAP_SLOP * resources.displayMetrics.density
                    if (tap) click(if (fingers >= 2) "right" else "single")
                    fingers = 0
                }
            }
            return true
        }
    }

    companion object {
        private const val EXTRA_ID = "dev.droidtop.kdeconnect.ID"
        private const val EXTRA_NAME = "dev.droidtop.kdeconnect.NAME"
        private const val TICK_MS = 16L
        private const val TOUCH_SPEED = 1.5f
        private const val TOUCH_SCROLL = 0.15f
        private const val STICK_SPEED = 18f
        private const val STICK_SCROLL = 1.2f
        private const val TAP_MS = 200L
        private const val TAP_SLOP = 10f
        private val ARROWS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)

        fun intent(context: Context, id: String, name: String): Intent =
            Intent(context, KdeConnectTouchpadActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_NAME, name)
    }
}
