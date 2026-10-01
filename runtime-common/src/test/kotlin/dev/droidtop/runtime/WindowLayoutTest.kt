package dev.droidtop.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowLayoutTest {
    @Test fun layoutUsesWindowBounds() {
        assertTrue(isPortraitWindow(800, 1200))
        assertFalse(isPortraitWindow(1200, 800))
        assertFalse(isPortraitWindow(1000, 1000))
    }
}
