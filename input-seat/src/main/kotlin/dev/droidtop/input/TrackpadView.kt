package dev.droidtop.input

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * The Android half of the second-screen trackpad: it converts touch events
 * into millimetres and hands them to [TrackpadGestureEngine], and it owns
 * the timer that engine needs. It contains no gesture logic of its own, on
 * purpose -- everything that could be wrong in an interesting way lives in
 * the pure half, where it is unit-tested.
 *
 * Density is read from the display this view is actually attached to
 * (`context.resources.displayMetrics`, which for a view inside a
 * `Presentation` or an activity on a secondary display is that display's
 * metrics), through [MmScale], which discards the implausible values some
 * panels report for `xdpi`.
 */
class TrackpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /**
     * Null means the trackpad is inert: no session to point at, no shell
     * to navigate. Touches are then dropped rather than queued, for the
     * same reason `DesktopInputRouter` drops them -- a click made against
     * a dead surface must not be replayed into a live one later.
     */
    var engine: TrackpadGestureEngine? = null
        set(value) {
            if (field !== value) field?.cancel()
            field = value
        }

    private val handler = Handler(Looper.getMainLooper())

    private var mmPerPxX = 0f
    private var mmPerPxY = 0f

    private val tickRunnable = Runnable { runTick() }

    init {
        isFocusable = false
        isClickable = true
        recomputeScale()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        // A move between displays, or a density change, invalidates the
        // millimetre scale -- and a stale scale silently changes every
        // threshold the engine has.
        recomputeScale()
    }

    private fun recomputeScale() {
        val metrics = resources.displayMetrics
        mmPerPxX = MmScale.mmPerPx(metrics.xdpi, metrics.densityDpi)
        mmPerPxY = MmScale.mmPerPx(metrics.ydpi, metrics.densityDpi)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val engine = engine ?: return false

        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            engine.cancel()
            handler.removeCallbacks(tickRunnable)
            return true
        }

        engine.onFrame(touchesOf(event), event.eventTime)
        scheduleTick()
        return true
    }

    /**
     * Every pointer still down, in millimetres.
     *
     * The pointer that is leaving on ACTION_POINTER_UP is still present in
     * the event and must be excluded: counting it would mean the engine
     * never sees the finger count drop, so a two-finger tap would look
     * like a two-finger gesture that simply ended, and right click would
     * never fire.
     */
    private fun touchesOf(event: MotionEvent): List<TrackpadTouch> {
        val leaving = when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> event.actionIndex
            else -> -1
        }
        val touches = ArrayList<TrackpadTouch>(event.pointerCount)
        for (index in 0 until event.pointerCount) {
            if (index == leaving) continue
            touches += TrackpadTouch(
                id = event.getPointerId(index),
                xMm = event.getX(index) * mmPerPxX,
                yMm = event.getY(index) * mmPerPxY,
            )
        }
        return touches
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tickRunnable)
        val due = engine?.nextTimeoutAtMs() ?: return
        // eventTime and SystemClock.uptimeMillis() share a clock, which is
        // what makes an absolute deadline from the engine directly usable
        // as a Handler delay.
        val delay = (due - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0L)
        handler.postDelayed(tickRunnable, delay)
    }

    private fun runTick() {
        engine?.tick(android.os.SystemClock.uptimeMillis())
        scheduleTick()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tickRunnable)
        // The surface is going away with fingers possibly still down and a
        // button possibly still held; releasing here is what stops the
        // container being left with a stuck left button.
        engine?.cancel()
        super.onDetachedFromWindow()
    }
}
