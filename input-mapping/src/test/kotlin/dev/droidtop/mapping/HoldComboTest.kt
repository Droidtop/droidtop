package dev.droidtop.mapping

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mouse mode's switch, both stick clicks held (docs/SPEC.md 5b, "Controller mouse mode"). */
class HoldComboTest {
    private val left = PadInputs.KEYCODE_BUTTON_THUMBL
    private val right = PadInputs.KEYCODE_BUTTON_THUMBR

    @Test
    fun `both held long enough fire once per hold`() {
        val combo = HoldCombo.stickClicks()
        combo.press(left, 0)
        assertFalse(combo.due(1000))
        combo.press(right, 100)
        assertFalse(combo.due(500))
        assertTrue(combo.due(700))
        assertFalse(combo.due(2000))
        combo.release(right)
        combo.press(right, 3000)
        assertTrue(combo.due(3600))
    }

    @Test
    fun `other buttons do not count`() {
        val combo = HoldCombo.stickClicks()
        combo.press(PadInputs.KEYCODE_BUTTON_A, 0)
        combo.press(left, 0)
        assertFalse(combo.due(5000))
    }
}
