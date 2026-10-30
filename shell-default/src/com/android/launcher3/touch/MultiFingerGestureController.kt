package com.android.launcher3.touch

import android.view.MotionEvent
import android.view.ViewConfiguration
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.LauncherState
import com.android.launcher3.MotionEventsUtils
import com.android.launcher3.util.TouchController
import dev.droidtop.shell.standard.HomeActions
import dev.droidtop.runtime.StandardGestures
import kotlin.math.hypot

/**
 * droidtop patch (Droidtop/tracker#564): the two-finger gesture slots on the home screen, Nova/Apex-style.
 * Two fingers moving down or up together are a two-finger swipe; two fingers moving apart or together are a pinch
 * out or in. Whichever crosses its threshold first wins, and the slot's [GestureAction] runs once; the rest of the
 * gesture is consumed. A slot set to Nothing leaves the touch to the launcher.
 */
class MultiFingerGestureController(private val mLauncher: Launcher) : TouchController {
    /** Three touch slops: two fingers landing are never quite still, and a pinch must not read as a tap. */
    private val mThreshold = (3 * ViewConfiguration.get(mLauncher).scaledTouchSlop).toFloat()
    private var mTracking = false
    private var mStartMidY = 0f
    private var mStartSpan = 0f

    override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> mTracking = false
            MotionEvent.ACTION_POINTER_DOWN -> {
                mTracking = ev.pointerCount == 2 && canIntercept(ev)
                if (mTracking) {
                    mStartMidY = midY(ev)
                    mStartSpan = span(ev)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!mTracking) return false
                if (ev.pointerCount != 2) {
                    mTracking = false
                    return false
                }
                val dy = midY(ev) - mStartMidY
                val dSpan = span(ev) - mStartSpan
                // Which slot it is: StandardGestures, the one rule, tested in :runtime-common.
                val slot = when (StandardGestures.twoFinger(dy, dSpan, mThreshold) ?: return false) {
                    StandardGestures.Slot.PINCH_IN -> LauncherPrefs.GESTURE_PINCH_IN_ACTION
                    StandardGestures.Slot.PINCH_OUT -> LauncherPrefs.GESTURE_PINCH_OUT_ACTION
                    StandardGestures.Slot.TWO_FINGER_DOWN -> LauncherPrefs.GESTURE_TWO_FINGER_DOWN_ACTION
                    StandardGestures.Slot.TWO_FINGER_UP -> LauncherPrefs.GESTURE_TWO_FINGER_UP_ACTION
                }
                mTracking = false
                return HomeActions.runSlot(mLauncher, slot)
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mTracking = false
        }
        return false
    }

    override fun onControllerTouchEvent(ev: MotionEvent): Boolean = consumeUntilGestureEnds(ev)

    private fun canIntercept(ev: MotionEvent) = !MotionEventsUtils.isTrackpadScroll(ev) &&
            mLauncher.isInState(LauncherState.NORMAL) &&
            AbstractFloatingView.getTopOpenView(mLauncher) == null &&
            SLOTS.any { HomeActions.isSet(mLauncher, it) }

    private fun midY(ev: MotionEvent) = (ev.getY(0) + ev.getY(1)) / 2f

    private fun span(ev: MotionEvent) = hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))

    private companion object {
        val SLOTS = listOf(
            LauncherPrefs.GESTURE_TWO_FINGER_DOWN_ACTION,
            LauncherPrefs.GESTURE_TWO_FINGER_UP_ACTION,
            LauncherPrefs.GESTURE_PINCH_IN_ACTION,
            LauncherPrefs.GESTURE_PINCH_OUT_ACTION,
        )
    }
}

/** A gesture slot's controller after its action ran: the rest of the gesture is its own, until the finger lifts. */
internal fun consumeUntilGestureEnds(ev: MotionEvent): Boolean =
    ev.actionMasked != MotionEvent.ACTION_UP && ev.actionMasked != MotionEvent.ACTION_CANCEL
