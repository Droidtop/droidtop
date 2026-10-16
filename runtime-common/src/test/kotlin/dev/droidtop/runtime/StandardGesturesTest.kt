package dev.droidtop.runtime

import dev.droidtop.runtime.StandardGestures.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which gesture slot a home-screen gesture is (docs/SPEC.md, Launcher mode "Gestures"). */
class StandardGesturesTest {
    private val t = 30f

    @Test
    fun `two fingers moving together are a two-finger swipe`() {
        assertEquals(Slot.TWO_FINGER_DOWN, StandardGestures.twoFinger(dy = 40f, dSpan = 5f, threshold = t))
        assertEquals(Slot.TWO_FINGER_UP, StandardGestures.twoFinger(dy = -40f, dSpan = -5f, threshold = t))
    }

    @Test
    fun `fingers moving apart or together are a pinch`() {
        assertEquals(Slot.PINCH_OUT, StandardGestures.twoFinger(dy = 3f, dSpan = 50f, threshold = t))
        assertEquals(Slot.PINCH_IN, StandardGestures.twoFinger(dy = -3f, dSpan = -50f, threshold = t))
    }

    @Test
    fun `a pinch that also drifts is a pinch only while the span changes more than the move`() {
        assertEquals(Slot.PINCH_OUT, StandardGestures.twoFinger(dy = 35f, dSpan = 45f, threshold = t))
        assertEquals(Slot.TWO_FINGER_DOWN, StandardGestures.twoFinger(dy = 45f, dSpan = 35f, threshold = t))
    }

    @Test
    fun `fingers that have not moved past the threshold are no gesture yet`() {
        assertNull(StandardGestures.twoFinger(dy = 29f, dSpan = -29f, threshold = t))
        assertNull(StandardGestures.twoFinger(dy = 0f, dSpan = 0f, threshold = t))
    }

    @Test
    fun `home runs its slot only on the first page and only when set`() {
        assertTrue(StandardGestures.homeRunsSlot(onFirstPage = true, slotSet = true))
        assertFalse(StandardGestures.homeRunsSlot(onFirstPage = false, slotSet = true))
        assertFalse(StandardGestures.homeRunsSlot(onFirstPage = true, slotSet = false))
    }
}
