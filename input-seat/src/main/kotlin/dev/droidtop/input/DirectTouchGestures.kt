package dev.droidtop.input

import kotlin.math.hypot

/**
 * Touch on the desktop surface itself, where the finger is the pointer (docs/SPEC.md 6b "Touch on the desktop",
 * Droidtop/tracker#684). Pure: positions in view pixels, time in milliseconds, so every rule is tested without a
 * screen.
 *
 * - A finger that lifts without moving past [slopPx] is a left click where it was.
 * - A finger that moves past [slopPx] presses the left button where it went down and drags, so a title bar or a
 *   selection follows it.
 * - A finger held still for [longPressMs] is a right click where it is: a context menu, the terminal's paste.
 * - Two fingers that lift without moving past [slopPx] are a right click; two fingers moving scroll.
 *
 * Why not the trackpad's [TrackpadGestureEngine]: that engine is a laptop trackpad, where one finger moves the pointer
 * by its travel without pressing anything and dragging is tap-then-drag. Here the pointer jumps to the finger and a
 * press-and-move must drag, as it always has on this surface. The thresholds are Android's own for a touch screen
 * (`ViewConfiguration`'s touch slop and long-press timeout), which the host passes in.
 *
 * The host calls [down], [move], [secondDown], [pointerUp], [up] and [cancel] from the touch events, and [tick] when
 * [nextTimeoutAtMs] comes due (the long press needs no further event to fire).
 */
class DirectTouchGestures(
    private val out: Output,
    var slopPx: Float = DEFAULT_SLOP_PX,
    var longPressMs: Long = DEFAULT_LONG_PRESS_MS,
) {
    /** What the gestures do, in view pixels; the host maps them onto the desktop. */
    interface Output {
        fun moveTo(x: Float, y: Float)
        fun button(evdevButton: Int, pressed: Boolean)

        /** The fingers' travel since the last call; content follows the fingers. */
        fun scroll(dxPx: Float, dyPx: Float)
    }

    private enum class State { IDLE, PENDING, DRAGGING, LONG_PRESSED, TWO_FINGERS, DONE }

    private var state = State.IDLE
    private var startX = 0f
    private var startY = 0f
    private var startAt = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var twoMoved = false

    /** When [tick] next has something to do, or null. */
    fun nextTimeoutAtMs(): Long? = if (state == State.PENDING) startAt + longPressMs else null

    fun down(x: Float, y: Float, nowMs: Long) {
        cancel()
        state = State.PENDING
        startX = x
        startY = y
        lastX = x
        lastY = y
        startAt = nowMs
        out.moveTo(x, y)
    }

    fun move(x: Float, y: Float, nowMs: Long) {
        tick(nowMs)
        when (state) {
            State.PENDING -> if (hypot(x - startX, y - startY) > slopPx) {
                // The drag starts where the finger went down, so what was under it is what moves.
                out.button(EvdevKeys.BTN_LEFT, true)
                state = State.DRAGGING
                out.moveTo(x, y)
            }
            State.DRAGGING, State.LONG_PRESSED -> out.moveTo(x, y)
            else -> Unit
        }
    }

    /** A second finger landed; [x], [y] is the two fingers' centroid. A drag in progress is let go first. */
    fun secondDown(x: Float, y: Float, nowMs: Long) {
        if (state == State.DRAGGING) out.button(EvdevKeys.BTN_LEFT, false)
        if (state == State.DONE || state == State.TWO_FINGERS) return
        state = State.TWO_FINGERS
        twoMoved = false
        startX = x
        startY = y
        lastX = x
        lastY = y
        startAt = nowMs
    }

    /** Two or more fingers moved; [x], [y] is their centroid. */
    fun twoMove(x: Float, y: Float) {
        if (state != State.TWO_FINGERS) return
        if (!twoMoved && hypot(x - startX, y - startY) <= slopPx) return
        twoMoved = true
        out.scroll(x - lastX, y - lastY)
        lastX = x
        lastY = y
    }

    /** One of several fingers lifted: two fingers that never moved are a right click. The rest of the touch does nothing. */
    fun pointerUp(nowMs: Long) {
        if (state != State.TWO_FINGERS) return
        if (!twoMoved && nowMs - startAt < longPressMs) click(EvdevKeys.BTN_RIGHT)
        state = State.DONE
    }

    /** The last finger lifted at [x], [y]. */
    fun up(x: Float, y: Float, nowMs: Long) {
        tick(nowMs)
        when (state) {
            State.PENDING -> click(EvdevKeys.BTN_LEFT)
            State.DRAGGING -> {
                out.moveTo(x, y)
                out.button(EvdevKeys.BTN_LEFT, false)
            }
            else -> Unit
        }
        state = State.IDLE
    }

    /** The long press, when its time has come and the finger has not moved. */
    fun tick(nowMs: Long) {
        if (state == State.PENDING && nowMs - startAt >= longPressMs) {
            click(EvdevKeys.BTN_RIGHT)
            state = State.LONG_PRESSED
        }
    }

    /** The touch was taken away: a drag's button is let go, nothing else is sent. */
    fun cancel() {
        if (state == State.DRAGGING) out.button(EvdevKeys.BTN_LEFT, false)
        state = State.IDLE
    }

    private fun click(button: Int) {
        out.button(button, true)
        out.button(button, false)
    }

    companion object {
        /** Android's default touch slop (8 dp) at mdpi; the host passes the device's own. */
        const val DEFAULT_SLOP_PX = 8f

        /** Android's default long-press timeout; the host passes the device's own. */
        const val DEFAULT_LONG_PRESS_MS = 400L
    }
}
