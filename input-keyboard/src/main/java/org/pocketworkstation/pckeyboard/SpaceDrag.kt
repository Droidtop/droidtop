package org.pocketworkstation.pckeyboard

import kotlin.math.abs

/**
 * Space-bar drag moves the cursor (docs/SPEC.md 6a, "Editing helpers", Droidtop/tracker#340). A touch that starts
 * on the space key stays an ordinary space press until it has moved [activatePx] sideways; from then on it is a
 * cursor drag and every [stepPx] further moves the cursor one place, left or right. The thresholds are in pixels
 * here and in dp at the caller, so a handheld's dense panel and a tablet feel alike.
 */
class SpaceDrag(private val activatePx: Int, private val stepPx: Int) {
    private var startX = 0
    private var anchorX = 0

    /** True once the touch has become a drag; the space key is then not typed. */
    var active: Boolean = false
        private set

    fun down(x: Int) {
        startX = x
        anchorX = x
        active = false
    }

    /**
     * The cursor steps for a move of the touch to [x]: positive to the right, negative to the left, zero while the
     * touch is still below the threshold (and at the moment it passes it, which moves nothing).
     */
    fun move(x: Int): Int {
        if (!active) {
            if (abs(x - startX) < activatePx) return 0
            active = true
            anchorX = x
            return 0
        }
        val steps = (x - anchorX) / stepPx
        if (steps != 0) anchorX += steps * stepPx
        return steps
    }
}
