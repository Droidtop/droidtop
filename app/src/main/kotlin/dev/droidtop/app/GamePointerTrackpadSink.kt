package dev.droidtop.app

import dev.droidtop.input.EvdevKeys
import dev.droidtop.input.PointerAcceleration
import dev.droidtop.input.TRACKPAD_SCROLL_MM_PER_DETENT
import dev.droidtop.input.TRACKPAD_TRAVEL_MM_PER_SCREEN_WIDTH
import dev.droidtop.input.TrackpadOutput
import dev.droidtop.library.OwnGameScreens

/**
 * The companion's trackpad as the mouse of droidtop's own game screen (Droidtop/tracker#565): finger travel scaled to
 * the game's own width (the same hand movement crosses its screen once, as on every other route), with libinput's
 * acceleration and the person's pointer speed, into the [pointer] controller mouse mode drives. Fractions of a pixel and
 * of a wheel step carry over, so slow movement is not lost. Main thread.
 */
internal class GamePointerTrackpadSink(
    private val pointer: () -> OwnGameScreens.Pointer?,
    private val userSpeed: Float,
    private val naturalScroll: Boolean,
) : TrackpadOutput {
    private var carryX = 0f
    private var carryY = 0f
    private var carryWheel = 0f

    override fun onMove(dxMm: Float, dyMm: Float, speedMmPerS: Float) {
        val target = pointer() ?: return
        val gain = target.widthPx / TRACKPAD_TRAVEL_MM_PER_SCREEN_WIDTH * PointerAcceleration.factor(speedMmPerS, userSpeed)
        carryX += dxMm * gain
        carryY += dyMm * gain
        val dx = carryX.toInt()
        val dy = carryY.toInt()
        carryX -= dx
        carryY -= dy
        target.move(dx, dy)
    }

    override fun onScroll(dxMm: Float, dyMm: Float) {
        val target = pointer() ?: return
        // Natural scrolling: the content follows the fingers, so fingers moving up scroll down.
        carryWheel += (if (naturalScroll) -dyMm else dyMm) / TRACKPAD_SCROLL_MM_PER_DETENT
        while (carryWheel >= 1f) { target.wheel(down = true); carryWheel -= 1f }
        while (carryWheel <= -1f) { target.wheel(down = false); carryWheel += 1f }
    }

    override fun onButton(evdevButton: Int, pressed: Boolean) {
        val button = when (evdevButton) {
            EvdevKeys.BTN_LEFT -> OwnGameScreens.PointerButton.LEFT
            EvdevKeys.BTN_RIGHT -> OwnGameScreens.PointerButton.RIGHT
            EvdevKeys.BTN_MIDDLE -> OwnGameScreens.PointerButton.MIDDLE
            else -> return
        }
        pointer()?.button(button, pressed)
    }
}
