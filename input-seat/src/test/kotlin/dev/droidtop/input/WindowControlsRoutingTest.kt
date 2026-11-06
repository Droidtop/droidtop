package dev.droidtop.input

import android.view.MotionEvent
import dev.droidtop.hostbridge.ControlButton
import dev.droidtop.hostbridge.SwayFrames
import dev.droidtop.hostbridge.SwayRect
import dev.droidtop.hostbridge.WindowButton
import dev.droidtop.hostbridge.WindowControls
import dev.droidtop.hostbridge.WindowFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A press on a title-bar button or in a window's resize band is droidtop's, and nothing of it is a click in the
 * compositor (Droidtop/tracker#910); every other press is left exactly as it was.
 */
class WindowControlsRoutingTest {
    private val bridge = FakeHostBridge()
    private val pressed = mutableListOf<ControlButton>()
    private var released = 0

    /** A 1920 x 1080 surface showing a 960 x 540 output, with one floating window: frame 200..1000 x 200..848. */
    private val router = DesktopInputRouter().apply {
        seat = InputSeat(bridge)
        transform = PointerTransform(1920, 1080, 1920, 1080)
        val window = WindowFrame(11, "foot", "~", "HEADLESS-1", floating = true, focused = true,
            rect = SwayRect(100, 124, 400, 300), titleBar = SwayRect(100, 100, 400, 24), border = 8)
        windowControls = WindowControls.layout(
            SwayFrames(mapOf("HEADLESS-1" to SwayRect(0, 0, 960, 540)), listOf(window)), "HEADLESS-1", 1920, 1080, 96f,
        )
        onWindowButton = { pressed += it }
        onPointerReleased = { released++ }
    }

    private fun nothingSent() {
        assertTrue(bridge.pointerAbsolutes.isEmpty() && bridge.pointerButtons.isEmpty() && bridge.keys.isEmpty())
    }

    @Test
    fun `a finger on a button sends nothing, and presses it when let go on it`() {
        assertTrue(router.touchCapture(MotionEvent.ACTION_DOWN, 950f, 220f))
        assertTrue(router.touchCapture(MotionEvent.ACTION_MOVE, 955f, 230f))
        assertTrue(router.touchCapture(MotionEvent.ACTION_POINTER_DOWN, 500f, 500f))
        assertTrue(router.touchCapture(MotionEvent.ACTION_UP, 955f, 230f))
        assertEquals(listOf(WindowButton.CLOSE), pressed.map { it.kind })
        assertEquals(1, released)
        nothingSent()
    }

    @Test
    fun `a finger slid off the button, or cancelled, presses nothing`() {
        router.touchCapture(MotionEvent.ACTION_DOWN, 950f, 220f)
        router.touchCapture(MotionEvent.ACTION_UP, 600f, 220f)
        router.touchCapture(MotionEvent.ACTION_DOWN, 950f, 220f)
        router.touchCapture(MotionEvent.ACTION_UP, 860f, 220f)
        router.touchCapture(MotionEvent.ACTION_DOWN, 950f, 220f)
        router.touchCapture(MotionEvent.ACTION_CANCEL, 950f, 220f)
        assertTrue(pressed.isEmpty())
        nothingSent()
    }

    @Test
    fun `the band holds Super and the right button over the window, follows the finger, and lets both go`() {
        assertTrue(router.touchCapture(MotionEvent.ACTION_DOWN, 1050f, 500f))
        // Super down, the pointer put just inside the right edge, then the right button.
        assertEquals(listOf(EvdevKeys.META_LEFT to true), bridge.keys)
        assertEquals(listOf(listOf<Any>(998.0, 500.0, 1920, 1080)), bridge.pointerAbsolutes)
        assertEquals(listOf(EvdevKeys.BTN_RIGHT to true), bridge.pointerButtons)
        // The finger moves 100 px right: the pointer moves with it, from where it was put.
        router.touchCapture(MotionEvent.ACTION_MOVE, 1150f, 520f)
        assertEquals(listOf<Any>(1098.0, 520.0, 1920, 1080), bridge.pointerAbsolutes.last())
        router.touchCapture(MotionEvent.ACTION_UP, 1160f, 520f)
        assertEquals(listOf(EvdevKeys.BTN_RIGHT to true, EvdevKeys.BTN_RIGHT to false), bridge.pointerButtons)
        assertEquals(listOf(EvdevKeys.META_LEFT to true, EvdevKeys.META_LEFT to false), bridge.keys)
        assertEquals(listOf(false, false), bridge.typed)
        assertTrue(pressed.isEmpty())
        assertEquals(1, released)
    }

    @Test
    fun `a press anywhere else is left to the gestures, with nothing sent for it here`() {
        assertFalse(router.touchCapture(MotionEvent.ACTION_DOWN, 600f, 500f))
        assertFalse(router.touchCapture(MotionEvent.ACTION_DOWN, 600f, 220f))
        assertFalse(router.touchCapture(MotionEvent.ACTION_DOWN, 50f, 50f))
        nothingSent()
    }

    @Test
    fun `with no controls every press is left as it was`() {
        router.windowControls = WindowControls.NONE
        assertFalse(router.touchCapture(MotionEvent.ACTION_DOWN, 1050f, 500f))
        assertFalse(router.mouseCapture(950f, 220f, MotionEvent.BUTTON_PRIMARY))
        nothingSent()
    }

    @Test
    fun `a mouse's primary button on a button is taken until every button is up`() {
        assertFalse(router.mouseCapture(950f, 220f, 0))
        assertTrue(router.mouseCapture(950f, 220f, MotionEvent.BUTTON_PRIMARY))
        assertTrue(router.mouseCapture(952f, 222f, MotionEvent.BUTTON_PRIMARY))
        assertTrue(router.mouseCapture(952f, 222f, 0))
        assertEquals(listOf(WindowButton.CLOSE), pressed.map { it.kind })
        nothingSent()
        // Hovering afterwards is not taken.
        assertFalse(router.mouseCapture(952f, 222f, 0))
    }

    @Test
    fun `a mouse's other buttons, or the primary pressed with another, are not taken`() {
        assertFalse(router.mouseCapture(950f, 220f, MotionEvent.BUTTON_SECONDARY))
        assertFalse(router.mouseCapture(1050f, 500f, MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_SECONDARY))
        nothingSent()
    }

    @Test
    fun `a mouse in the band resizes as a finger does`() {
        assertTrue(router.mouseCapture(1050f, 500f, MotionEvent.BUTTON_PRIMARY))
        router.mouseCapture(1100f, 500f, MotionEvent.BUTTON_PRIMARY)
        router.mouseCapture(1100f, 500f, 0)
        assertEquals(listOf(EvdevKeys.BTN_RIGHT to true, EvdevKeys.BTN_RIGHT to false), bridge.pointerButtons)
        assertEquals(listOf(EvdevKeys.META_LEFT to true, EvdevKeys.META_LEFT to false), bridge.keys)
        assertEquals(listOf<Any>(1048.0, 500.0, 1920, 1080), bridge.pointerAbsolutes.last())
    }

    @Test
    fun `losing the surface mid-resize lets go of the button, then Super`() {
        router.touchCapture(MotionEvent.ACTION_DOWN, 1050f, 500f)
        router.releaseHeldInput()
        assertEquals(EvdevKeys.BTN_RIGHT to false, bridge.pointerButtons.last())
        assertEquals(EvdevKeys.META_LEFT to false, bridge.keys.last())
        // The next press starts afresh.
        assertFalse(router.touchCapture(MotionEvent.ACTION_DOWN, 600f, 500f))
    }
}
