package dev.droidtop.runtime.windows

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer
import dev.droidtop.library.OwnGameScreens
import dev.droidtop.mapping.ControllerLayout
import dev.droidtop.mapping.MappingEngine
import dev.droidtop.mapping.PadInputs

/**
 * The Wine sink of the controls engine (Droidtop/tracker#618 C1/C3; docs/SPEC.md 5b "Controller layouts"): while [on],
 * the pad is played through [layout] as a keyboard and mouse. Pad buttons and sticks go to a [MappingEngine]; its keys
 * reach the X server as key presses ([WineKeyNames]), its mouse buttons, wheel and pointer moves go through the game's
 * one [pointer], as mouse mode's and the companion trackpad's do. A pad button the layout leaves unbound is not taken,
 * so it still reaches the game as the pad. Pad and action outputs have no target here yet and do nothing.
 *
 * The engine is ticked every frame while on, for the mouse stick and the long and double press windows. Main thread.
 */
internal class LayoutDriver(
    private val xServer: XServer,
    private val pointer: OwnGameScreens.Pointer,
    private val layout: ControllerLayout,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var engine = MappingEngine(layout)

    /** Pad key codes whose press the layout took, so their release is taken too. */
    private val taken = HashSet<Int>()

    /** X keys and pointer buttons held now, so turning the layout off lets go of them. */
    private val keysDown = HashSet<XKeycode>()
    private val buttonsDown = HashSet<OwnGameScreens.PointerButton>()

    /** The pointer's travel below one pixel, carried to the next frame. */
    private var carryX = 0f
    private var carryY = 0f

    /** Which hat directions are pressed, for pads that report the d-pad as axes. */
    private var hat = emptySet<String>()

    private val frame = object : Runnable {
        override fun run() {
            apply(engine.tick(SystemClock.uptimeMillis()))
            handler.postDelayed(this, FRAME_MS)
        }
    }

    var on: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            handler.removeCallbacks(frame)
            if (value) {
                engine = MappingEngine(layout)
                handler.post(frame)
            } else {
                letGo()
            }
        }

    /** Stops the frame tick (the screen is going away); what is held is let go. */
    fun close() {
        on = false
    }

    fun onKey(event: KeyEvent): Boolean {
        if (!on) return false
        val code = event.keyCode
        val physical = PadInputs.physical(code) ?: return false
        if (!fromPad(event.source)) return false
        val now = SystemClock.uptimeMillis()
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return code in taken
                if (engine.groupOn(physical.source)?.input(physical.input)?.activators.isNullOrEmpty()) return false
                taken += code
                apply(engine.press(physical.source, physical.input, now))
            }
            KeyEvent.ACTION_UP -> {
                if (!taken.remove(code)) return false
                apply(engine.release(physical.source, physical.input, now))
            }
            else -> return code in taken
        }
        return true
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (!on || !fromPad(event.source) || event.action != MotionEvent.ACTION_MOVE) return false
        val now = SystemClock.uptimeMillis()
        apply(engine.stick(PadInputs.LEFT_STICK, event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y), now))
        apply(engine.stick(PadInputs.RIGHT_STICK, event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RZ), now))
        val wanted = MappingEngine.directions(event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y))
        for (d in hat - wanted) apply(engine.release("dpad", d, now))
        for (d in wanted - hat) apply(engine.press("dpad", d, now))
        hat = wanted
        return true
    }

    private fun apply(outputs: List<MappingEngine.Output>) {
        for (o in outputs) {
            when (o) {
                is MappingEngine.Output.KeyDown -> WineKeyNames.keycode(o.key)?.let { if (keysDown.add(it)) xServer.injectKeyPress(it) }
                is MappingEngine.Output.KeyUp -> WineKeyNames.keycode(o.key)?.let { if (keysDown.remove(it)) xServer.injectKeyRelease(it) }
                is MappingEngine.Output.MouseDown -> button(o.button)?.let { if (buttonsDown.add(it)) pointer.button(it, true) }
                is MappingEngine.Output.MouseUp -> button(o.button)?.let { if (buttonsDown.remove(it)) pointer.button(it, false) }
                is MappingEngine.Output.Wheel -> pointer.wheel(down = o.direction.contains("DOWN", ignoreCase = true))
                is MappingEngine.Output.MouseMove -> move(o.dx, o.dy)
                else -> Unit
            }
        }
    }

    private fun move(dxWidths: Float, dyWidths: Float) {
        carryX += dxWidths * pointer.widthPx
        carryY += dyWidths * pointer.widthPx
        val dx = carryX.toInt()
        val dy = carryY.toInt()
        if (dx == 0 && dy == 0) return
        carryX -= dx
        carryY -= dy
        pointer.move(dx, dy)
    }

    private fun letGo() {
        for (k in keysDown) xServer.injectKeyRelease(k)
        keysDown.clear()
        for (b in buttonsDown) pointer.button(b, false)
        buttonsDown.clear()
        taken.clear()
        hat = emptySet()
        carryX = 0f
        carryY = 0f
    }

    private fun button(name: String): OwnGameScreens.PointerButton? = when (name.uppercase()) {
        "LEFT" -> OwnGameScreens.PointerButton.LEFT
        "RIGHT" -> OwnGameScreens.PointerButton.RIGHT
        "MIDDLE" -> OwnGameScreens.PointerButton.MIDDLE
        else -> null
    }

    private fun fromPad(source: Int): Boolean =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    companion object {
        private const val FRAME_MS = 16L
    }
}
