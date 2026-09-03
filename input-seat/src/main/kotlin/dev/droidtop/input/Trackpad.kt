package dev.droidtop.input

import kotlin.math.abs
import kotlin.math.hypot

/**
 * The second-screen trackpad, as pure logic.
 *
 * Everything in this file is deliberately free of Android types, so the
 * parts that are easy to get subtly wrong -- the gesture state machine,
 * the pointer acceleration curve, the millimetre conversion -- can be
 * tested without a device. `TrackpadView` is the thin Android shim that
 * feeds it and [TrackpadOutput] is what it feeds; neither contains a
 * decision.
 *
 * ## Why millimetres
 *
 * A trackpad's behaviour has to be a property of how far the FINGER
 * moved, not of how many pixels that was. The Retroid's addon panel is
 * 1080x1920 across a few inches; a lapdock's touch surface is a different
 * density entirely. Working in pixels would make the same gesture mean
 * different things on different panels, and every threshold below (tap
 * slop, scroll slop, the acceleration curve's velocity knees) would need
 * re-tuning per device. So the engine speaks millimetres and knows
 * nothing about either panel's resolution.
 *
 * ## The gesture model is not invented here
 *
 * It is libinput's, which is what a Linux desktop and (in the parts that
 * matter) macOS and Windows Precision Touchpad already behave like:
 *
 * - one finger moves the pointer, with acceleration
 * - one-finger tap is left click, two-finger tap is right click,
 *   three-finger tap is middle click
 * - two fingers moving is scroll
 * - tap, then put a finger straight back down, is a drag with the left
 *   button held, released when that finger lifts
 *
 * Deliberately NOT included: drag lock (libinput ships it off by default,
 * and a button that stays down after the finger has left is astonishing),
 * software button areas along the bottom edge (a Retroid addon screen is
 * not a Thinkpad), and edge scrolling (superseded by two-finger scroll
 * everywhere). No gesture here is droidtop's own idea, which is the point:
 * a trackpad that behaves like every other trackpad needs no learning.
 */
object PointerAcceleration {

    /** Below this finger speed the pointer moves at [MIN_FACTOR]: precision. */
    const val LOW_MM_PER_S = 32f

    /** At and above this it moves at [MAX_FACTOR]: crossing the screen. */
    const val HIGH_MM_PER_S = 320f

    const val MIN_FACTOR = 0.4f
    const val MAX_FACTOR = 2.6f

    /**
     * libinput's "adaptive" profile, reduced to the two knees that give it
     * its character: a flat slow region so a slow finger can land on a
     * small close button, a flat fast region so a flick crosses the
     * screen, and a straight line between them.
     *
     * libinput's real curve smooths those corners. The corners are not
     * what makes a trackpad feel right -- the ratio between the two
     * plateaus is -- and a piecewise-linear ramp is something that can be
     * read and checked, rather than tuned by feel on hardware this change
     * was written without.
     *
     * [userSpeed] is libinput's own -1..1 pointer speed setting, applied
     * as a power of two so that 0 is unchanged, +1 is twice as fast and -1
     * half as fast across the whole curve rather than at only one end.
     */
    fun factor(speedMmPerS: Float, userSpeed: Float = 0f): Float {
        val base = when {
            speedMmPerS <= LOW_MM_PER_S -> MIN_FACTOR
            speedMmPerS >= HIGH_MM_PER_S -> MAX_FACTOR
            else -> {
                val t = (speedMmPerS - LOW_MM_PER_S) / (HIGH_MM_PER_S - LOW_MM_PER_S)
                MIN_FACTOR + t * (MAX_FACTOR - MIN_FACTOR)
            }
        }
        return base * Math.pow(2.0, userSpeed.coerceIn(-1f, 1f).toDouble()).toFloat()
    }
}

/**
 * How far the finger travels to cross the destination screen once at
 * acceleration factor 1.0.
 *
 * This is what makes the gain independent of BOTH panels: the gain in
 * output pixels per millimetre is the destination output's width divided
 * by this, so a 1080-wide compositor output and a 2560-wide lapdock output
 * take the same physical hand movement to cross. 160 mm is about the width
 * of a laptop trackpad, and "one trackpad width crosses the screen once at
 * unity" is a relationship a laptop user already has in their hands.
 */
const val TRACKPAD_TRAVEL_MM_PER_SCREEN_WIDTH = 160f

/** One wheel detent is 15 units on the Wayland axis; this is the finger travel that earns one. */
const val TRACKPAD_SCROLL_MM_PER_DETENT = 3f

/** Thresholds, in the units the engine speaks. Marked where a default is not libinput's own. */
data class TrackpadConfig(
    /** libinput's tap motion threshold. Beyond this a touch is a move, never a tap. */
    val tapSlopMm: Float = 3f,
    /** libinput's tap timeout. A touch held longer is not a tap however still it was held. */
    val tapTimeoutMs: Long = 180L,
    /**
     * How long after a tap a finger may return and turn it into a drag.
     * libinput's tap-and-drag window, and also the delay before a plain
     * tap's button release is sent -- which is why it is not longer.
     */
    val tapDragTimeoutMs: Long = 300L,
    /** Movement before two fingers count as scrolling rather than resting. Not a libinput constant. */
    val scrollSlopMm: Float = 1.5f,
)

/**
 * What a trackpad gesture produced, in physical units, with no opinion
 * about what it should do. [SeatTrackpadSink] turns this into a pointer
 * inside the container; [FocusNavTrackpadSink] turns it into shell
 * navigation. That split is the reason the engine emits neither pixels
 * nor key codes itself.
 */
interface TrackpadOutput {
    /** Finger movement, with the smoothed speed the acceleration curve wants. */
    fun onMove(dxMm: Float, dyMm: Float, speedMmPerS: Float)

    /** Two-finger movement. The sign is raw finger direction; the sink decides "natural" or not. */
    fun onScroll(dxMm: Float, dyMm: Float)

    /** [evdevButton] is BTN_LEFT/BTN_RIGHT/BTN_MIDDLE from [EvdevKeys]. */
    fun onButton(evdevButton: Int, pressed: Boolean)
}

/** One finger, in millimetres from the top-left of the trackpad area. */
data class TrackpadTouch(val id: Int, val xMm: Float, val yMm: Float)

/**
 * The gesture state machine.
 *
 * The API is a whole-frame one -- [onFrame] is handed every finger
 * currently down -- rather than per-pointer down/move/up callbacks. That
 * is not a style choice: centroid tracking with fingers arriving and
 * leaving is exactly where these state machines go wrong, and being handed
 * the complete set each time makes "which pointers were in both frames"
 * (see [commonCentroidDelta]) a local question instead of bookkeeping
 * spread across three entry points.
 *
 * Time is passed in rather than read, and [tick] exists, because the
 * tap-and-drag window is a real timeout: a plain tap's button release is
 * only correct once that window has passed with no finger returning. A
 * caller that never ticks would leave a click half-delivered, so
 * [nextTimeoutAtMs] states exactly when the next tick is owed.
 */
class TrackpadGestureEngine(
    private val out: TrackpadOutput,
    private val config: TrackpadConfig = TrackpadConfig(),
) {
    private var previous: Map<Int, TrackpadTouch> = emptyMap()
    private var previousMs: Long = 0L

    private var gestureStartMs: Long = 0L
    private var gestureStartCentroidX = 0f
    private var gestureStartCentroidY = 0f
    private var maxFingers = 0
    private var movedBeyondTapSlop = false
    private var scrolling = false
    private var scrollAccumX = 0f
    private var scrollAccumY = 0f

    private var dragging = false

    /** Non-null while a tap's button is down waiting either for a drag or for its release. */
    private var pendingClickReleaseAtMs: Long? = null

    private var smoothedSpeed = 0f

    /** When [tick] must next be called, or null if nothing is pending. */
    fun nextTimeoutAtMs(): Long? = pendingClickReleaseAtMs

    /** Delivers any timeout that has come due. Safe to call at any rate, including not at all. */
    fun tick(nowMs: Long) {
        val due = pendingClickReleaseAtMs ?: return
        if (nowMs >= due) {
            pendingClickReleaseAtMs = null
            out.onButton(EvdevKeys.BTN_LEFT, pressed = false)
        }
    }

    /**
     * Releases everything held and forgets the gesture. Called when the
     * surface goes away, the session ends, or Android cancels the touch
     * stream: a button whose release never arrives stays down forever on
     * the other side, which is the worst failure this class can have.
     */
    fun cancel() {
        if (dragging || pendingClickReleaseAtMs != null) {
            out.onButton(EvdevKeys.BTN_LEFT, pressed = false)
        }
        dragging = false
        pendingClickReleaseAtMs = null
        previous = emptyMap()
        resetGesture()
    }

    /** [touches] is every finger currently down on the trackpad area. */
    fun onFrame(touches: List<TrackpadTouch>, nowMs: Long) {
        tick(nowMs)

        val current = touches.associateBy { it.id }
        val wasEmpty = previous.isEmpty()
        val isEmpty = current.isEmpty()

        if (wasEmpty && !isEmpty) {
            beginGesture(current, nowMs)
        } else if (!wasEmpty && !isEmpty) {
            continueGesture(current, nowMs)
        } else if (!wasEmpty && isEmpty) {
            endGesture(nowMs)
        }

        previous = current
        previousMs = nowMs
    }

    private fun beginGesture(current: Map<Int, TrackpadTouch>, nowMs: Long) {
        gestureStartMs = nowMs
        val centroid = centroidOf(current.values)
        gestureStartCentroidX = centroid.first
        gestureStartCentroidY = centroid.second
        maxFingers = current.size
        movedBeyondTapSlop = false
        scrolling = false
        scrollAccumX = 0f
        scrollAccumY = 0f
        smoothedSpeed = 0f

        // A finger returning inside the tap-and-drag window turns the tap
        // that is still holding the button into a drag: the pending
        // release is dropped rather than sent, so the button never blips.
        if (pendingClickReleaseAtMs != null && current.size == 1) {
            pendingClickReleaseAtMs = null
            dragging = true
        }
    }

    private fun continueGesture(current: Map<Int, TrackpadTouch>, nowMs: Long) {
        maxFingers = maxOf(maxFingers, current.size)

        // A second finger landing mid-drag ends the drag rather than
        // dragging and scrolling at once. The button is released first so
        // the mode change cannot leave it down.
        if (dragging && current.size > 1) {
            dragging = false
            out.onButton(EvdevKeys.BTN_LEFT, pressed = false)
        }

        val delta = commonCentroidDelta(current) ?: return
        val (dx, dy) = delta
        val dtMs = (nowMs - previousMs).coerceAtLeast(1L)

        val centroid = centroidOf(current.values)
        if (hypot(centroid.first - gestureStartCentroidX, centroid.second - gestureStartCentroidY) > config.tapSlopMm) {
            movedBeyondTapSlop = true
        }

        when (current.size) {
            1 -> {
                val instantaneous = hypot(dx, dy) / (dtMs / 1000f)
                // A single frame's speed is noisy enough to make the
                // acceleration factor flicker between the two plateaus
                // mid-stroke, which reads as the pointer stuttering. An
                // exponential moving average is the standard fix, and is
                // what libinput's own velocity tracker does.
                smoothedSpeed = SPEED_SMOOTHING * instantaneous + (1f - SPEED_SMOOTHING) * smoothedSpeed
                if (dx != 0f || dy != 0f) out.onMove(dx, dy, smoothedSpeed)
            }

            2 -> {
                scrollAccumX += dx
                scrollAccumY += dy
                if (!scrolling && hypot(scrollAccumX, scrollAccumY) > config.scrollSlopMm) {
                    scrolling = true
                    // The travel that proved it was a scroll is real
                    // movement and is delivered, not discarded -- throwing
                    // it away gives every scroll a dead zone at its start.
                    out.onScroll(scrollAccumX, scrollAccumY)
                } else if (scrolling) {
                    out.onScroll(dx, dy)
                }
            }

            // Three or more fingers move nothing: they exist to be tapped.
            else -> Unit
        }
    }

    private fun endGesture(nowMs: Long) {
        if (dragging) {
            dragging = false
            out.onButton(EvdevKeys.BTN_LEFT, pressed = false)
            resetGesture()
            return
        }

        val wasTap = !movedBeyondTapSlop &&
            !scrolling &&
            (nowMs - gestureStartMs) <= config.tapTimeoutMs

        if (wasTap) {
            when (maxFingers) {
                1 -> {
                    // Pressed now, released when the tap-and-drag window
                    // closes -- because a finger returning inside that
                    // window means this was the start of a drag, not a
                    // click, and the button must not have blipped in
                    // between. libinput's behaviour, and the reason [tick]
                    // exists at all.
                    out.onButton(EvdevKeys.BTN_LEFT, pressed = true)
                    pendingClickReleaseAtMs = nowMs + config.tapDragTimeoutMs
                }

                2 -> click(EvdevKeys.BTN_RIGHT)
                3 -> click(EvdevKeys.BTN_MIDDLE)
                else -> Unit
            }
        }
        resetGesture()
    }

    private fun click(button: Int) {
        out.onButton(button, pressed = true)
        out.onButton(button, pressed = false)
    }

    private fun resetGesture() {
        maxFingers = 0
        movedBeyondTapSlop = false
        scrolling = false
        scrollAccumX = 0f
        scrollAccumY = 0f
        smoothedSpeed = 0f
    }

    /**
     * Centroid movement measured over ONLY the pointers present in both
     * frames.
     *
     * Using every pointer would teleport the cursor whenever a finger is
     * added or lifted, because the centroid of two fingers is nowhere near
     * the position of either. Null when the frames share no pointer at
     * all, which means there is no movement anyone can attest to.
     */
    private fun commonCentroidDelta(current: Map<Int, TrackpadTouch>): Pair<Float, Float>? {
        var dx = 0f
        var dy = 0f
        var count = 0
        for ((id, now) in current) {
            val before = previous[id] ?: continue
            dx += now.xMm - before.xMm
            dy += now.yMm - before.yMm
            count++
        }
        if (count == 0) return null
        return dx / count to dy / count
    }

    private fun centroidOf(touches: Collection<TrackpadTouch>): Pair<Float, Float> {
        if (touches.isEmpty()) return 0f to 0f
        var x = 0f
        var y = 0f
        for (touch in touches) {
            x += touch.xMm
            y += touch.yMm
        }
        return x / touches.size to y / touches.size
    }

    private companion object {
        const val SPEED_SMOOTHING = 0.4f
    }
}

/**
 * Converts a panel's reported physical density into millimetres per pixel,
 * defensively.
 *
 * `DisplayMetrics.xdpi`/`ydpi` are the honest answer when a device fills
 * them in, and notoriously wrong when it does not -- values of 0, of 1, or
 * a straight copy of `densityDpi` are all real, widely reported
 * behaviours, and a trackpad whose every threshold is in millimetres
 * cannot survive an xdpi of 1. So a reported value outside a range no real
 * panel falls outside is discarded in favour of `densityDpi`, which is
 * always populated because the whole resource system depends on it.
 */
object MmScale {
    const val MIN_PLAUSIBLE_DPI = 80f
    const val MAX_PLAUSIBLE_DPI = 1200f
    private const val MM_PER_INCH = 25.4f

    fun mmPerPx(reportedDpi: Float, densityDpi: Int): Float {
        val dpi = if (reportedDpi in MIN_PLAUSIBLE_DPI..MAX_PLAUSIBLE_DPI) {
            reportedDpi
        } else {
            densityDpi.toFloat().coerceIn(MIN_PLAUSIBLE_DPI, MAX_PLAUSIBLE_DPI)
        }
        return MM_PER_INCH / dpi
    }
}

/**
 * Trackpad output as a pointer inside the primary container, via the one
 * [InputSeat] every other input path also goes through. There is no second
 * injection route to `HostBridgeInput` here, which is what keeps the
 * compositor seeing a single pointer no matter how many surfaces drive it.
 */
class SeatTrackpadSink(
    private val seat: InputSeat,
    /**
     * Output pixels per millimetre of finger travel at acceleration 1.0.
     * Derived from the destination output's width, so the same hand
     * movement crosses any output once -- see
     * [TRACKPAD_TRAVEL_MM_PER_SCREEN_WIDTH].
     */
    var gainPxPerMm: Float,
    /** libinput's -1..1 pointer speed, straight from the user's setting. */
    var userSpeed: Float = 0f,
    /**
     * Content follows the fingers. Matches what `DesktopInputRouter`
     * already does for two-finger touch scroll on the primary surface, so
     * the two surfaces do not scroll opposite ways.
     */
    var naturalScroll: Boolean = true,
) : TrackpadOutput {

    override fun onMove(dxMm: Float, dyMm: Float, speedMmPerS: Float) {
        val factor = PointerAcceleration.factor(speedMmPerS, userSpeed)
        seat.onPointerMove(
            InputSource.SECOND_SCREEN_TRACKPAD,
            dxMm * gainPxPerMm * factor,
            dyMm * gainPxPerMm * factor,
        )
    }

    override fun onScroll(dxMm: Float, dyMm: Float) {
        val units = WHEEL_DETENT / TRACKPAD_SCROLL_MM_PER_DETENT
        val sign = if (naturalScroll) -1f else 1f
        seat.onPointerScroll(InputSource.SECOND_SCREEN_TRACKPAD, sign * dxMm * units, sign * dyMm * units)
    }

    override fun onButton(evdevButton: Int, pressed: Boolean) {
        seat.onPointerButton(InputSource.SECOND_SCREEN_TRACKPAD, evdevButton, pressed)
    }

    private companion object {
        const val WHEEL_DETENT = 15f
    }
}

/** What a trackpad gesture means to a shell that navigates by focus rather than by pointer. */
enum class NavKey { UP, DOWN, LEFT, RIGHT, CONFIRM, BACK }

/**
 * Turns continuous finger travel into discrete steps.
 *
 * The dominant axis is locked for the duration of a stroke. Without that,
 * a swipe of 40 mm right and 6 mm down emits a stray vertical step and the
 * selection jumps a row -- and in a grid, a stray step is not a small
 * error, it is the wrong game launching.
 */
class DirectionalStepper(private val stepMm: Float = 10f) {
    private var accumX = 0f
    private var accumY = 0f
    private var lockedHorizontal: Boolean? = null

    fun reset() {
        accumX = 0f
        accumY = 0f
        lockedHorizontal = null
    }

    fun accumulate(dxMm: Float, dyMm: Float): List<NavKey> {
        accumX += dxMm
        accumY += dyMm

        val horizontal = lockedHorizontal ?: run {
            if (abs(accumX) < stepMm && abs(accumY) < stepMm) return emptyList()
            (abs(accumX) >= abs(accumY)).also { lockedHorizontal = it }
        }

        val steps = mutableListOf<NavKey>()
        if (horizontal) {
            accumY = 0f
            while (abs(accumX) >= stepMm) {
                if (accumX > 0) {
                    steps += NavKey.RIGHT
                    accumX -= stepMm
                } else {
                    steps += NavKey.LEFT
                    accumX += stepMm
                }
            }
        } else {
            accumX = 0f
            while (abs(accumY) >= stepMm) {
                if (accumY > 0) {
                    steps += NavKey.DOWN
                    accumY -= stepMm
                } else {
                    steps += NavKey.UP
                    accumY += stepMm
                }
            }
        }
        return steps
    }
}

/**
 * Trackpad output for the Handheld shell, which has no pointer at all.
 *
 * Stated plainly, because it is the decision this class exists to record:
 * a cursor is not available to droidtop in Handheld mode. Moving a system
 * cursor over another app's window needs `INJECT_EVENTS`, a signature
 * permission no ordinary app holds, and the Handheld shell is Compose
 * focus navigation driven by a D-pad -- a drawn arrow would have nothing
 * to click even if droidtop drew one. So the trackpad drives what the
 * shell actually understands: focus steps, confirm and back. It is a
 * navigation surface here and a pointer there, and pretending the two are
 * the same thing would ship something that silently does nothing.
 *
 * Scroll folds into the same stepper with a coarser step, so two fingers
 * page through a long list faster than one finger walks it.
 */
class FocusNavTrackpadSink(
    private val emit: (NavKey) -> Unit,
    moveStepMm: Float = 10f,
    scrollStepMm: Float = 6f,
) : TrackpadOutput {
    private val moveStepper = DirectionalStepper(moveStepMm)
    private val scrollStepper = DirectionalStepper(scrollStepMm)

    override fun onMove(dxMm: Float, dyMm: Float, speedMmPerS: Float) {
        moveStepper.accumulate(dxMm, dyMm).forEach(emit)
    }

    override fun onScroll(dxMm: Float, dyMm: Float) {
        scrollStepper.accumulate(dxMm, dyMm).forEach(emit)
    }

    /**
     * Only the RELEASE is acted on, and only for the two buttons that mean
     * something here. A held button has no meaning to focus navigation --
     * there is nothing to drag -- and acting on the press as well would
     * make every tap fire twice.
     */
    override fun onButton(evdevButton: Int, pressed: Boolean) {
        if (pressed) return
        when (evdevButton) {
            EvdevKeys.BTN_LEFT -> emit(NavKey.CONFIRM)
            EvdevKeys.BTN_RIGHT -> emit(NavKey.BACK)
            else -> Unit
        }
    }

    fun reset() {
        moveStepper.reset()
        scrollStepper.reset()
    }
}
