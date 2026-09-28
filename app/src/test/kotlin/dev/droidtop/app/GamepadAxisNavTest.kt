package dev.droidtop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [axisTargetKey]'s own direction/edge/dedupe math (Droidtop/tracker#1/#43,
 * owner on the console with a real gamepad: "Up still isn't processed in
 * a game list" / "Left and up in the game list ... are just inert.
 * Nothing happens on press or on release"). Traced to
 * [GamepadAxisNav]'s per-device dedupe: the Retroid Pocket 5's own D-pad
 * sends DOWN/RIGHT as real `KeyEvent`s but UP/LEFT only through the hat
 * axis, and the old dedupe disabled hat/stick translation for the WHOLE
 * device the moment any one real DPAD key arrived -- permanently blinding
 * it to the hat's own UP/LEFT the instant the very first real DOWN
 * landed. [suppressed] is now asked per exact keyCode, and these tests
 * are built around exactly that asymmetric device (real DOWN and RIGHT,
 * hat-only UP and LEFT) from both a fresh axis (the "first item" case)
 * and a direction already held (the "middle of a long list" case, i.e.
 * hysteresis keeping the current key).
 */
class GamepadAxisNavTest {
    private val up = 1
    private val down = 2
    private val left = 3
    private val right = 4

    /** This device's own real keys -- DOWN and RIGHT only, exactly the owner's Retroid Pocket 5. */
    private val realOnDownAndRight: (Int) -> Boolean = { it == down || it == right }
    private val neverReal: (Int) -> Boolean = { false }

    @Test
    fun `a fresh negative deflection picks the negative key when nothing is suppressed`() {
        assertEquals(up, axisTargetKey(value = -1f, currentKey = null, negativeKey = up, positiveKey = down, suppressed = neverReal))
    }

    @Test
    fun `a fresh positive deflection picks the positive key when nothing is suppressed`() {
        assertEquals(down, axisTargetKey(value = 1f, currentKey = null, negativeKey = up, positiveKey = down, suppressed = neverReal))
    }

    @Test
    fun `UP still translates from a fresh axis even though this device already sends a real DOWN`() {
        // Droidtop/tracker#1/#43's exact device shape: DOWN is real, UP is hat-only.
        // The old per-device dedupe returned null here; per-key must not.
        assertEquals(up, axisTargetKey(value = -1f, currentKey = null, negativeKey = up, positiveKey = down, suppressed = realOnDownAndRight))
    }

    @Test
    fun `LEFT still translates from a fresh axis even though this device already sends a real RIGHT`() {
        assertEquals(left, axisTargetKey(value = -1f, currentKey = null, negativeKey = left, positiveKey = right, suppressed = realOnDownAndRight))
    }

    @Test
    fun `DOWN does not translate a second time -- this device already sends it as a real key`() {
        assertNull(axisTargetKey(value = 1f, currentKey = null, negativeKey = up, positiveKey = down, suppressed = realOnDownAndRight))
    }

    @Test
    fun `RIGHT does not translate a second time -- this device already sends it as a real key`() {
        assertNull(axisTargetKey(value = 1f, currentKey = null, negativeKey = left, positiveKey = right, suppressed = realOnDownAndRight))
    }

    @Test
    fun `UP keeps translating while the stick is held there -- hysteresis, middle of a held press`() {
        // Below ENTER but still past EXIT: the axis is mid-return, not yet centered.
        assertEquals(up, axisTargetKey(value = -0.4f, currentKey = up, negativeKey = up, positiveKey = down, suppressed = realOnDownAndRight))
    }

    @Test
    fun `UP releases once the axis actually returns to center`() {
        assertNull(axisTargetKey(value = 0f, currentKey = up, negativeKey = up, positiveKey = down, suppressed = realOnDownAndRight))
    }

    @Test
    fun `a value in the dead zone with no current key stays at rest`() {
        assertNull(axisTargetKey(value = 0.1f, currentKey = null, negativeKey = up, positiveKey = down, suppressed = neverReal))
    }

    @Test
    fun `suppression is per exact key -- suppressing UP never suppresses DOWN on the same axis`() {
        val suppressUpOnly: (Int) -> Boolean = { it == up }
        assertEquals(down, axisTargetKey(value = 1f, currentKey = null, negativeKey = up, positiveKey = down, suppressed = suppressUpOnly))
        assertNull(axisTargetKey(value = -1f, currentKey = null, negativeKey = up, positiveKey = down, suppressed = suppressUpOnly))
    }
}
