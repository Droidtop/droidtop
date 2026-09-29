package dev.droidtop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessibilityPrefsTest {
    @Test
    fun noneAndUnknownHaveNoMatrix() {
        assertNull(AccessibilityPrefs.colorMatrix(AccessibilityPrefs.VISION_NONE))
        assertNull(AccessibilityPrefs.colorMatrix("bogus"))
        assertNull(AccessibilityPrefs.colorMatrix(null))
    }

    @Test
    fun greyMakesEqualChannelsFromWhite() {
        val m = AccessibilityPrefs.colorMatrix(AccessibilityPrefs.VISION_GREY)!!
        for (row in 0 until 3) assertEquals(1f, m[row * 5] + m[row * 5 + 1] + m[row * 5 + 2], 0.001f)
    }

    @Test
    fun correctionsKeepNeutralColoursNeutral() {
        // A neutral input has no colour to lose, so each colour row sums to 1.
        for (mode in listOf("protanopia", "deuteranopia", "tritanopia")) {
            val m = AccessibilityPrefs.colorMatrix(mode)!!
            assertEquals(20, m.size)
            for (row in 0 until 3) assertEquals(mode, 1f, m[row * 5] + m[row * 5 + 1] + m[row * 5 + 2], 0.01f)
        }
    }
}
