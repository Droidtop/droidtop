package dev.droidtop.runtime.windows

import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.winlator.xserver.Pointer
import com.winlator.xserver.XServer
import dev.droidtop.library.PadMouse

/**
 * Controller mouse mode in droidtop's Windows game screen (docs/SPEC.md 5b, "Controller mouse mode";
 * Droidtop/tracker#565). While [settings] is on, the chosen stick moves the X pointer, the chosen buttons click, and
 * the stick's own click held turns the stick into a wheel; the pad's other keys still reach the game. Both stick
 * clicks held ([PadMouse.Combo]) turn it on and off at any time, [onToggled] keeping the choice for the game.
 *
 * Everything goes through the calls the screen's touch trackpad already makes on [xServer]
 * (`injectPointerMoveDelta`, `injectPointerButtonPress` and `Release`, the wheel as scroll-button presses), so there
 * is one pointer path. Main thread.
 */
internal class PadMouseDriver(
    private val xServer: XServer,
    var settings: PadMouse.Settings,
    private val onToggled: (PadMouse.Settings) -> Unit,
) {
    private val combo = PadMouse.Combo()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var stickX = 0f
    private var stickY = 0f
    private var scrolling = false
    private var carryX = 0f
    private var carryY = 0f
    private var carryScroll = 0f
    private var lastFrameNanos = 0L
    private var ticking = false

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dt = if (lastFrameNanos == 0L) 1f / 60f else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
            lastFrameNanos = frameTimeNanos
            if (!settings.on || !step(dt)) {
                ticking = false
                lastFrameNanos = 0L
                return
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** One frame: move or scroll; false when the stick is at rest and nothing is left to do. */
    private fun step(dt: Float): Boolean {
        if (scrolling) {
            val rate = PadMouse.scrollRate(stickY)
            if (rate == 0f) return false
            carryScroll += rate * dt
            while (carryScroll >= 1f) { wheel(Pointer.Button.BUTTON_SCROLL_DOWN); carryScroll -= 1f }
            while (carryScroll <= -1f) { wheel(Pointer.Button.BUTTON_SCROLL_UP); carryScroll += 1f }
            return true
        }
        val (vx, vy) = PadMouse.velocity(stickX, stickY, xServer.screenInfo.width.toInt())
        if (vx == 0f && vy == 0f) return false
        carryX += vx * dt
        carryY += vy * dt
        val dx = carryX.toInt()
        val dy = carryY.toInt()
        carryX -= dx
        carryY -= dy
        if (dx != 0 || dy != 0) xServer.injectPointerMoveDelta(dx, dy)
        return true
    }

    private fun wheel(button: Pointer.Button) {
        xServer.injectPointerButtonPress(button)
        xServer.injectPointerButtonRelease(button)
    }

    private fun ensureTicking() {
        if (ticking) return
        ticking = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    /** A key from the pad: true when mouse mode took it (a click, the scroll hold); the combination is always watched. */
    fun onKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BUTTON_THUMBL || code == KeyEvent.KEYCODE_BUTTON_THUMBR) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                combo.press(code, event.eventTime)
                // A held key sends nothing more: check again once the hold could be long enough.
                handler.postDelayed({ if (combo.due(android.os.SystemClock.uptimeMillis())) toggle() }, PadMouse.TOGGLE_HOLD_MS + 10)
            }
            if (event.action == KeyEvent.ACTION_UP) combo.release(code)
            if (combo.due(event.eventTime)) toggle()
        }
        if (!settings.on) return false
        if (code == PadMouse.scrollKey(settings.stick)) {
            scrolling = event.action == KeyEvent.ACTION_DOWN
            if (scrolling) ensureTicking()
            return true
        }
        val button = PadMouse.buttonFor(code, settings.clicks) ?: return false
        if (event.repeatCount > 0) return true
        val x = if (button == PadMouse.Button.LEFT) Pointer.Button.BUTTON_LEFT else Pointer.Button.BUTTON_RIGHT
        when (event.action) {
            KeyEvent.ACTION_DOWN -> xServer.injectPointerButtonPress(x)
            KeyEvent.ACTION_UP -> xServer.injectPointerButtonRelease(x)
        }
        return true
    }

    /**
     * A joystick motion: in mouse mode the chosen stick is the pointer and the event is taken, so the game does not
     * also read it as a stick. The combination's timer is checked here too, since a held key sends no further events.
     */
    fun onMotion(event: MotionEvent): Boolean {
        if (combo.due(event.eventTime)) toggle()
        if (!settings.on) return false
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false
        if (settings.stick == PadMouse.Stick.RIGHT) {
            stickX = event.getAxisValue(MotionEvent.AXIS_Z)
            stickY = event.getAxisValue(MotionEvent.AXIS_RZ)
        } else {
            stickX = event.getAxisValue(MotionEvent.AXIS_X)
            stickY = event.getAxisValue(MotionEvent.AXIS_Y)
        }
        ensureTicking()
        return true
    }

    /** On or off, from the combination or the Quick Menu. */
    fun toggle() = set(settings.copy(on = !settings.on))

    fun set(next: PadMouse.Settings) {
        settings = next
        if (!next.on) {
            stickX = 0f
            stickY = 0f
            scrolling = false
        }
        onToggled(next)
    }
}
