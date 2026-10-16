package dev.droidtop.runtime

import kotlin.math.abs

/**
 * Standard's gesture slots (docs/SPEC.md, Launcher mode "Gestures"; Droidtop/tracker#564): which slot a home-screen
 * gesture is. Pure, so the decisions are tested here: the rig cannot inject multi-touch or a focused Home press
 * (2026-10-16), and these are what it could not check. The launcher's MultiFingerGestureController and
 * Launcher.onNewIntent ask this, then run the slot's action.
 */
object StandardGestures {
    enum class Slot { TWO_FINGER_DOWN, TWO_FINGER_UP, PINCH_IN, PINCH_OUT }

    /**
     * The slot a two-finger movement is, or null while it is not one yet. [dy] is how far the fingers' midpoint
     * moved down (negative: up), [dSpan] how much the distance between them grew (negative: closer), and
     * [threshold] the distance either must pass (three touch slops). Whichever passes first wins; a change of
     * span wins a tie only when it is larger than the move.
     */
    fun twoFinger(dy: Float, dSpan: Float, threshold: Float): Slot? = when {
        abs(dSpan) > threshold && abs(dSpan) > abs(dy) -> if (dSpan < 0) Slot.PINCH_IN else Slot.PINCH_OUT
        abs(dy) > threshold -> if (dy > 0) Slot.TWO_FINGER_DOWN else Slot.TWO_FINGER_UP
        else -> null
    }

    /**
     * What a Home press does in the launcher: true runs the Home button's slot, false goes to the first page.
     * The slot runs only when the home screen already shows its first page and the slot is set to something,
     * as in Nova and Apex; on any other page Home still goes to the first page.
     */
    fun homeRunsSlot(onFirstPage: Boolean, slotSet: Boolean): Boolean = onFirstPage && slotSet
}
