package dev.droidtop.input

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which pointer events take the finger gestures (Droidtop/tracker#684, rig 2638: the emulator's touchscreen is also a stylus). */
class TouchRoutingTest {
    private val router = DesktopInputRouter()

    @Test
    fun `a finger on a touch screen is touch, whatever else the device is`() {
        assertTrue(router.isFingerTouch(fromTouchscreen = true, toolType = MotionEvent.TOOL_TYPE_FINGER))
        assertTrue(router.isFingerTouch(fromTouchscreen = true, toolType = MotionEvent.TOOL_TYPE_UNKNOWN))
    }

    @Test
    fun `a pen or a mouse is not, nor anything off a touch screen`() {
        assertFalse(router.isFingerTouch(fromTouchscreen = true, toolType = MotionEvent.TOOL_TYPE_STYLUS))
        assertFalse(router.isFingerTouch(fromTouchscreen = true, toolType = MotionEvent.TOOL_TYPE_MOUSE))
        assertFalse(router.isFingerTouch(fromTouchscreen = false, toolType = MotionEvent.TOOL_TYPE_FINGER))
    }
}
