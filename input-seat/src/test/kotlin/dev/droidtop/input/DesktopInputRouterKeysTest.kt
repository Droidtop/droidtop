package dev.droidtop.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key mapping [DesktopInputRouter] injects, driven through the
 * int-taking [DesktopInputRouter.onKeyEvent] because the android.jar a
 * unit test runs against throws on every method call — a KeyEvent cannot
 * even be constructed there. Expected values are evdev codes
 * (KEY_LFSH = 42, KEY_1 = 2, KEY_A = 30), the vocabulary the compositor
 * receives.
 */
class DesktopInputRouterKeysTest {

    private val shiftMeta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON

    private fun router(bridge: FakeHostBridge): DesktopInputRouter =
        DesktopInputRouter().apply { seat = InputSeat(bridge) }

    @Test
    fun `a key shifted only in the meta state gets a Shift press and release around it`() {
        // An IME-synthesized keystroke, or a hardware keyboard whose Shift
        // press predates the surface's focus: the Shift exists only as
        // META_SHIFT_ON on the character key's own event (tracker#147).
        val bridge = FakeHostBridge()
        val router = router(bridge)

        router.onKeyEvent(KeyEvent.KEYCODE_1, KeyEvent.ACTION_DOWN, 0, shiftMeta)
        router.onKeyEvent(KeyEvent.KEYCODE_1, KeyEvent.ACTION_UP, 0, shiftMeta)

        assertEquals(
            listOf(
                42 to true,  // Shift reaches the container before the key
                2 to true,   // KEY_1
                2 to false,
                42 to false, // and leaves with it
            ),
            bridge.keys,
        )
    }

    @Test
    fun `a typed key and its synthesized Shift go through the text keyboard, a physical key through the layout one`() {
        val bridge = FakeHostBridge()
        val router = router(bridge)

        router.onKeyEvent(KeyEvent.KEYCODE_1, KeyEvent.ACTION_DOWN, 0, shiftMeta, typed = true)
        router.onKeyEvent(KeyEvent.KEYCODE_1, KeyEvent.ACTION_UP, 0, shiftMeta, typed = true)
        router.onKeyEvent(KeyEvent.KEYCODE_Y, KeyEvent.ACTION_DOWN, 0, 0, typed = false)
        router.onKeyEvent(KeyEvent.KEYCODE_Y, KeyEvent.ACTION_UP, 0, 0, typed = false)

        assertEquals(listOf(42 to true, 2 to true, 2 to false, 42 to false, 21 to true, 21 to false), bridge.keys)
        assertEquals(listOf(true, true, true, true, false, false), bridge.typed)
    }

    @Test
    fun `a held key is released through the keyboard it was pressed on`() {
        val bridge = FakeHostBridge()
        val router = router(bridge)

        router.onKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, 0, 0, typed = false)
        router.releaseHeldInput()

        assertEquals(listOf(30 to true, 30 to false), bridge.keys)
        assertEquals(listOf(false, false), bridge.typed)
    }

    @Test
    fun `an unshifted key is forwarded alone`() {
        val bridge = FakeHostBridge()
        val router = router(bridge)

        router.onKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, 0, 0)
        router.onKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP, 0, 0)

        assertEquals(listOf(30 to true, 30 to false), bridge.keys)
    }

    @Test
    fun `a Shift the container already sees is not synthesized a second time`() {
        // The hardware route that already worked: the Shift press itself is
        // forwarded like any other key, and the meta state must not stack a
        // second one on top of it.
        val bridge = FakeHostBridge()
        val router = router(bridge)

        // A real Shift press carries META_SHIFT_ON in its own meta state.
        router.onKeyEvent(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_DOWN, 0, shiftMeta)
        router.onKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, 0, shiftMeta)
        router.onKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP, 0, shiftMeta)
        router.onKeyEvent(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_UP, 0, 0)

        assertEquals(listOf(42 to true, 30 to true, 30 to false, 42 to false), bridge.keys)
    }

    @Test
    fun `overlapping shifted keys share one synthesized Shift`() {
        val bridge = FakeHostBridge()
        val router = router(bridge)

        router.onKeyEvent(KeyEvent.KEYCODE_1, KeyEvent.ACTION_DOWN, 0, shiftMeta)
        router.onKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, 0, shiftMeta)
        // Releasing the first key must not drop the Shift out from under
        // the second.
        router.onKeyEvent(KeyEvent.KEYCODE_1, KeyEvent.ACTION_UP, 0, shiftMeta)
        router.onKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP, 0, 0)

        assertEquals(
            listOf(42 to true, 2 to true, 30 to true, 2 to false, 30 to false, 42 to false),
            bridge.keys,
        )
    }

    @Test
    fun `caps lock in the meta state is not shift`() {
        // The Caps Lock key is forwarded like any other and toggles the
        // container's own caps state; a Shift synthesized around these
        // would cancel it back out.
        val bridge = FakeHostBridge()
        val router = router(bridge)

        router.onKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, 0, KeyEvent.META_CAPS_LOCK_ON)
        router.onKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP, 0, KeyEvent.META_CAPS_LOCK_ON)

        assertEquals(listOf(30 to true, 30 to false), bridge.keys)
    }

    @Test
    fun `repeats reach the compositor's own repeat, not a second press`() {
        val bridge = FakeHostBridge()
        val router = router(bridge)

        router.onKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, 0, 0)
        router.onKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, 1, 0)

        assertEquals(listOf(30 to true), bridge.keys)
    }

    @Test
    fun `a lost key release does not leave the synthesized Shift held`() {
        val bridge = FakeHostBridge()
        val router = router(bridge)

        router.onKeyEvent(KeyEvent.KEYCODE_1, KeyEvent.ACTION_DOWN, 0, shiftMeta)
        router.releaseHeldInput()

        // The everything-up path releases in held order, Shift first; what
        // matters is that neither the key nor the Shift stays down.
        assertEquals(listOf(42 to true, 2 to true, 42 to false, 2 to false), bridge.keys)
    }

    @Test
    fun `keys Android owns are still declined rather than swallowed`() {
        val bridge = FakeHostBridge()
        val router = router(bridge)

        assertFalse(router.onKeyEvent(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN, 0, 0))
        assertTrue(router.onKeyEvent(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, 0, 0))
        assertEquals(listOf(28 to true), bridge.keys)
    }
}
