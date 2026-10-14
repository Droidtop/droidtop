package dev.droidtop.input

import kotlin.math.hypot

/**
 * The trackpad as a pointer on a screen droidtop does not own, as pure logic (docs/SPEC.md "The companion's tabs",
 * Input; Droidtop/tracker#486 item 3).
 *
 * Nothing an ordinary app can do moves a system cursor over another app's window (`INJECT_EVENTS` is a signature
 * permission). What it can have is an accessibility service, whose `dispatchGesture` plays touches on any screen:
 * a cursor drawn in an accessibility overlay shows where the next touch lands, and a click, a long press, a drag and a
 * scroll are touches played there. This file is everything about that which has no Android type in it:
 * [PointerTrackpadSink] (the gesture engine's output, as a position and touches), [PointerGestureQueue] (touches
 * played one at a time, drags kept as one continuing stroke) and [TrackpadRoute] (when the pointer is the right
 * output and when droidtop's own shell is). `:app` supplies the two Android parts: the runner that builds gestures
 * and the cursor.
 */
interface PointerInjector {
    /** Where the cursor is now, in the target screen's pixels. */
    fun moveCursor(x: Float, y: Float)

    fun tap(x: Float, y: Float)

    /** A long press: what a right click means to a touch screen. */
    fun contextClick(x: Float, y: Float)

    fun dragStart(x: Float, y: Float)

    fun dragTo(x: Float, y: Float)

    fun dragEnd(x: Float, y: Float)

    /** A swipe from ([x], [y]) by the finger travel ([dxPx], [dyPx]): the content follows it. */
    fun scroll(x: Float, y: Float, dxPx: Float, dyPx: Float)
}

/**
 * The trackpad's output as a pointer: it keeps the position (the target screen's pixels, starting at its middle),
 * moves it with the engine's accelerated travel, and turns buttons into touches. A left press waits to learn what it
 * is: released without a move it is a tap where it was pressed (the engine releases a tap after its tap-and-drag
 * window, so a click lands that much later, as on every trackpad); moved while held it is a drag from where it was
 * pressed. A right press is a long press where the pointer is. Middle does nothing: a touch screen has no middle.
 *
 * [screenPx] is the target screen's size now (it changes with rotation), null while it is not known.
 */
class PointerTrackpadSink(
    private val injector: PointerInjector,
    private val screenPx: () -> Pair<Int, Int>?,
    var userSpeed: Float = 0f,
    var naturalScroll: Boolean = true,
) : TrackpadOutput {
    private var x = Float.NaN
    private var y = Float.NaN
    private var leftDown = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var scrollX = 0f
    private var scrollY = 0f

    /** Back to the middle of the screen: the target screen changed. */
    fun reset() {
        cancel()
        x = Float.NaN
        y = Float.NaN
    }

    /** Lets go of a drag in progress: the surface went away with a finger down. */
    fun cancel() {
        if (dragging) injector.dragEnd(x, y)
        dragging = false
        leftDown = false
        scrollX = 0f
        scrollY = 0f
    }

    private fun ready(): Pair<Int, Int>? {
        val size = screenPx() ?: return null
        if (size.first <= 0 || size.second <= 0) return null
        if (x.isNaN() || y.isNaN()) {
            x = size.first / 2f
            y = size.second / 2f
        }
        return size
    }

    override fun onMove(dxMm: Float, dyMm: Float, speedMmPerS: Float) {
        val (w, h) = ready() ?: return
        val gain = w / TRACKPAD_TRAVEL_MM_PER_SCREEN_WIDTH * PointerAcceleration.factor(speedMmPerS, userSpeed)
        x = (x + dxMm * gain).coerceIn(0f, w - 1f)
        y = (y + dyMm * gain).coerceIn(0f, h - 1f)
        injector.moveCursor(x, y)
        if (leftDown) {
            if (!dragging) {
                dragging = true
                injector.dragStart(downX, downY)
            }
            injector.dragTo(x, y)
        }
    }

    override fun onScroll(dxMm: Float, dyMm: Float) {
        val (w, _) = ready() ?: return
        val perMm = w / TRACKPAD_TRAVEL_MM_PER_SCREEN_WIDTH
        // Natural scrolling: the content follows the fingers, so the swipe is the fingers' own travel.
        val sign = if (naturalScroll) 1f else -1f
        scrollX += sign * dxMm * perMm
        scrollY += sign * dyMm * perMm
        if (hypot(scrollX, scrollY) >= SCROLL_STEP_PX) {
            injector.scroll(x, y, scrollX, scrollY)
            scrollX = 0f
            scrollY = 0f
        }
    }

    override fun onButton(evdevButton: Int, pressed: Boolean) {
        when (evdevButton) {
            EvdevKeys.BTN_LEFT -> if (pressed) {
                ready() ?: return
                leftDown = true
                dragging = false
                downX = x
                downY = y
            } else if (leftDown) {
                leftDown = false
                if (dragging) {
                    dragging = false
                    injector.dragEnd(x, y)
                } else {
                    injector.tap(downX, downY)
                }
            }

            EvdevKeys.BTN_RIGHT -> if (pressed && ready() != null) injector.contextClick(x, y)
            else -> Unit
        }
    }

    companion object {
        /** Finger travel, in the target screen's pixels, that is gathered into one swipe. */
        const val SCROLL_STEP_PX = 40f
    }
}

/** One touch for the target screen, in its pixels. */
sealed interface PointerOp {
    data class Tap(val x: Float, val y: Float) : PointerOp
    data class ContextClick(val x: Float, val y: Float) : PointerOp
    data class Scroll(val x: Float, val y: Float, val dx: Float, val dy: Float) : PointerOp
    data class DragStart(val x: Float, val y: Float) : PointerOp
    data class DragMove(val x: Float, val y: Float) : PointerOp
    data class DragEnd(val x: Float, val y: Float) : PointerOp
}

/** Plays one [PointerOp] and says whether it finished ([done] true) or was interrupted or refused (false). */
fun interface GestureRunner {
    fun run(op: PointerOp, done: (completed: Boolean) -> Unit)
}

/**
 * [PointerOp]s played in order, one at a time (the platform plays one gesture at a time, and a drag is one stroke
 * continued by the next gestures). Between two plays a newer drag move replaces an older one that has not started,
 * and scrolls at the same place add up, so a busy screen gets the latest position and the whole travel rather than a
 * growing backlog. A drag that was interrupted drops the rest of itself.
 */
class PointerGestureQueue(
    private val runner: GestureRunner,
    private val cursor: (x: Float, y: Float) -> Unit,
) : PointerInjector {
    private val queue = ArrayDeque<PointerOp>()
    private var running = false
    private var pumping = false
    private var dragOpen = false
    private var dragBroken = false

    override fun moveCursor(x: Float, y: Float) = cursor(x, y)

    override fun tap(x: Float, y: Float) = enqueue(PointerOp.Tap(x, y))

    override fun contextClick(x: Float, y: Float) = enqueue(PointerOp.ContextClick(x, y))

    override fun dragStart(x: Float, y: Float) {
        dragOpen = true
        dragBroken = false
        enqueue(PointerOp.DragStart(x, y))
    }

    override fun dragTo(x: Float, y: Float) {
        if (!dragOpen) return
        if (queue.lastOrNull() is PointerOp.DragMove) queue.removeLast()
        enqueue(PointerOp.DragMove(x, y))
    }

    override fun dragEnd(x: Float, y: Float) {
        if (!dragOpen) return
        dragOpen = false
        if (queue.lastOrNull() is PointerOp.DragMove) queue.removeLast()
        enqueue(PointerOp.DragEnd(x, y))
    }

    override fun scroll(x: Float, y: Float, dxPx: Float, dyPx: Float) {
        val last = queue.lastOrNull()
        if (last is PointerOp.Scroll) {
            queue.removeLast()
            enqueue(PointerOp.Scroll(x, y, (last.dx + dxPx).coerceIn(-MAX_SWIPE_PX, MAX_SWIPE_PX), (last.dy + dyPx).coerceIn(-MAX_SWIPE_PX, MAX_SWIPE_PX)))
        } else {
            enqueue(PointerOp.Scroll(x, y, dxPx, dyPx))
        }
    }

    private fun enqueue(op: PointerOp) {
        queue.addLast(op)
        pump()
    }

    private fun pump() {
        if (pumping) return
        pumping = true
        try {
            while (!running) {
                val op = queue.removeFirstOrNull() ?: break
                if (dragBroken && (op is PointerOp.DragMove || op is PointerOp.DragEnd)) continue
                running = true
                var finished = false
                runner.run(op) { completed ->
                    if (finished) return@run
                    finished = true
                    running = false
                    if (!completed && (op is PointerOp.DragStart || op is PointerOp.DragMove || op is PointerOp.DragEnd)) dragBroken = true
                    pump()
                }
            }
        } finally {
            pumping = false
        }
    }

    private companion object {
        const val MAX_SWIPE_PX = 900f
    }
}

/**
 * Which output the companion trackpad drives (docs/SPEC.md, Input). Pointer and focus are one selection in droidtop's
 * own shell, so while the shell is what the target screen shows the trackpad moves that selection (focus steps,
 * confirm, back); in any other app it is a pointer, which needs the accessibility service.
 */
enum class TrackpadRoute {
    FOCUS,
    POINTER,
    UNAVAILABLE,
    ;

    companion object {
        fun of(shellOnTarget: Boolean, pointerReachable: Boolean): TrackpadRoute = when {
            shellOnTarget -> FOCUS
            pointerReachable -> POINTER
            else -> UNAVAILABLE
        }
    }
}

/**
 * A trackpad output that picks its destination when a gesture starts and keeps it until a left button held through
 * the gesture is let go, so a drag that began as a pointer drag never ends as a focus step. [choose] is asked when
 * nothing is held; null means nowhere (the trackpad then does nothing).
 */
class RoutedTrackpadOutput(private val choose: () -> TrackpadOutput?) : TrackpadOutput {
    private var held: TrackpadOutput? = null

    override fun onMove(dxMm: Float, dyMm: Float, speedMmPerS: Float) {
        (held ?: choose())?.onMove(dxMm, dyMm, speedMmPerS)
    }

    override fun onScroll(dxMm: Float, dyMm: Float) {
        (held ?: choose())?.onScroll(dxMm, dyMm)
    }

    override fun onButton(evdevButton: Int, pressed: Boolean) {
        val sink = held ?: choose()
        if (evdevButton == EvdevKeys.BTN_LEFT) held = if (pressed) sink else null
        sink?.onButton(evdevButton, pressed)
    }
}
