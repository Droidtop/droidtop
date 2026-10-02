package dev.droidtop.shell.gamepad.input

import android.view.KeyEvent
import dev.droidtop.library.controller.FaceLayout
import dev.droidtop.library.controller.GlyphFamily
import dev.droidtop.library.controller.LayoutSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The back half of the input pipeline (docs/SPEC.md 6e): the one key
 * table, the face-button swap, the edge rule and the held-direction cadence
 * every screen gets from `Modifier.onPad`.
 */
class PadInputTest {
    private val NINTENDO = FaceLayout.forFamily(GlyphFamily.NINTENDO, LayoutSource.FAMILY)

    @After
    fun resetSwap() = ControllerLayouts.useLayoutForTest(FaceLayout.DEFAULT)

    // ---- the table ----

    @Test
    fun `the keyboard is a pad through the same table`() {
        assertEquals(GamepadAction.A, GamepadKeyMap.actionFor(KeyEvent.KEYCODE_ENTER))
        assertEquals(GamepadAction.B, GamepadKeyMap.actionFor(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(GamepadAction.X, GamepadKeyMap.actionFor(KeyEvent.KEYCODE_SPACE))
        assertEquals(GamepadAction.Y, GamepadKeyMap.actionFor(KeyEvent.KEYCODE_DEL))
        assertEquals(GamepadAction.SELECT, GamepadKeyMap.actionFor(KeyEvent.KEYCODE_MENU))
        assertEquals(GamepadAction.R2, GamepadKeyMap.actionFor(KeyEvent.KEYCODE_F10))
        assertEquals(GamepadAction.UP, GamepadKeyMap.actionFor(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test
    fun `Tab is the next tab and Shift+Tab the previous one, like Page Down and Page Up`() {
        assertEquals(GamepadAction.R, GamepadKeyMap.actionFor(KeyEvent.KEYCODE_TAB, shift = false))
        assertEquals(GamepadAction.L, GamepadKeyMap.actionFor(KeyEvent.KEYCODE_TAB, shift = true))
        assertEquals(GamepadAction.R, GamepadKeyMap.actionFor(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(GamepadAction.L, GamepadKeyMap.actionFor(KeyEvent.KEYCODE_PAGE_UP))
    }

    @Test
    fun `a key the shell does not use means nothing`() {
        assertNull(GamepadKeyMap.actionFor(KeyEvent.KEYCODE_A))
        assertNull(GamepadKeyMap.actionFor(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun `swapped, the pad's face buttons trade meanings`() {
        ControllerLayouts.useLayoutForTest(NINTENDO)
        assertEquals(GamepadAction.B, GamepadKeyMap.actionFor(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(GamepadAction.A, GamepadKeyMap.actionFor(KeyEvent.KEYCODE_BUTTON_B))
    }

    @Test
    fun `swapped, a keyboard's Enter still confirms and Escape still cancels`() {
        // The swap answers what is printed on a PAD; a keyboard has no
        // such question, and Escape confirming would be a trap.
        ControllerLayouts.useLayoutForTest(NINTENDO)
        assertEquals(GamepadAction.A, GamepadKeyMap.actionFor(KeyEvent.KEYCODE_ENTER))
        assertEquals(GamepadAction.B, GamepadKeyMap.actionFor(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test
    fun `a preview handler leaves the keys a text field types alone`() {
        assertTrue(GamepadKeyMap.isTextKey(KeyEvent.KEYCODE_SPACE))
        assertTrue(GamepadKeyMap.isTextKey(KeyEvent.KEYCODE_DEL))
        assertTrue(GamepadKeyMap.isTextKey(KeyEvent.KEYCODE_TAB))
        assertFalse(GamepadKeyMap.isTextKey(KeyEvent.KEYCODE_BUTTON_X))
    }

    // ---- the edge rule ----

    private val dpadDown = KeyEvent.KEYCODE_DPAD_DOWN
    private val buttonB = KeyEvent.KEYCODE_BUTTON_B

    @Test
    fun `an action fires on the press, and its release is consumed without firing again`() {
        val edges = PadEdges(PadCadence.CHROME)
        val fired = mutableListOf<PadPress>()
        assertTrue(edges.down(buttonB, GamepadAction.B, 0, 0, 0) { fired += it; true })
        assertEquals(1, fired.size)
        assertTrue("the release belongs to the owner of the press", edges.up(buttonB))
        assertEquals(1, fired.size)
    }

    @Test
    fun `a release whose press went somewhere else is not this owner's`() {
        // The R2 that opened the Quick Menu, a B that closed the screen
        // above: their release must not act here.
        val edges = PadEdges(PadCadence.CHROME)
        assertFalse(edges.up(buttonB))
    }

    @Test
    fun `a press the handler declines is left for whoever is above`() {
        val edges = PadEdges(PadCadence.CHROME)
        assertFalse(edges.down(dpadDown, GamepadAction.DOWN, 0, 0, 0) { false })
        assertFalse("and so is its release", edges.up(dpadDown))
        assertFalse("and its repeats", edges.down(dpadDown, GamepadAction.DOWN, 1, 0, 600) { true })
    }

    @Test
    fun `buttons never repeat`() {
        val edges = PadEdges(PadCadence.CHROME)
        var count = 0
        edges.down(buttonB, GamepadAction.B, 0, 0, 0) { count++; true }
        assertTrue(edges.down(buttonB, GamepadAction.B, 1, 0, 600) { count++; true })
        assertTrue(edges.down(buttonB, GamepadAction.B, 2, 0, 2000) { count++; true })
        assertEquals(1, count)
    }

    @Test
    fun `a held direction waits half a second, then steps at the chrome cadence whatever the device repeats at`() {
        val edges = PadEdges(PadCadence.CHROME)
        val steps = mutableListOf<Long>()
        edges.down(dpadDown, GamepadAction.DOWN, 0, 0, 0) { steps += 0L; true }
        // A device repeating every 50 ms from 400 ms on.
        var t = 400L
        var n = 1
        while (t <= 2000L) {
            val at = t
            edges.down(dpadDown, GamepadAction.DOWN, n++, 0, at) { press ->
                assertTrue(press.repeat)
                steps += at
                true
            }
            t += 50
        }
        assertEquals("the first step is the press itself", 0L, steps[0])
        assertEquals("the first repeat waits for 500 ms", 500L, steps[1])
        assertEquals("then 180 ms", 700L, steps[2])
        assertEquals(900L, steps[3])
        // Past 1.6 s held, 80 ms (the device's 50 ms grid rounds it up).
        val late = steps.filter { it > 1600 }
        assertTrue(late.zipWithNext().all { (a, b) -> b - a in 80L..100L })
    }

    @Test
    fun `a fresh press after a lost release starts over`() {
        val edges = PadEdges(PadCadence.CHROME)
        edges.down(dpadDown, GamepadAction.DOWN, 0, 0, 0) { true }
        // Its release went to another window; the next press is new.
        var repeatFlag: Boolean? = null
        edges.down(dpadDown, GamepadAction.DOWN, 0, 5000, 5000) { repeatFlag = it.repeat; true }
        assertEquals(false, repeatFlag)
    }

    @Test
    fun `cadence tiers read like ES-DE's IList tiers`() {
        assertEquals(500L, PadCadence.CHROME.delayAt(0))
        assertEquals(180L, PadCadence.CHROME.delayAt(600))
        assertEquals(80L, PadCadence.CHROME.delayAt(5000))
        assertEquals(114L, PadCadence.THEMED_LIST.delayAt(600))
        assertEquals(16L, PadCadence.THEMED_LIST.delayAt(5000))
    }

    // ---- the menu cursor ----

    @Test
    fun `a menu's cursor stops at both ends`() {
        assertEquals(0, menuStep(0, 5, -1))
        assertEquals(4, menuStep(4, 5, +1))
        assertEquals(3, menuStep(2, 5, +1))
        assertEquals(0, menuStep(3, 0, +1))
    }
}
