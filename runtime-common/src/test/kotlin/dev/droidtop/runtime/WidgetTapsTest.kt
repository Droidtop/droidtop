package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** One widget's tap is never another's (WidgetTaps.kt). */
class WidgetTapsTest {
    @Test
    fun `the same key on two widgets is two taps`() {
        assertNotEquals(WidgetTaps.requestCode(7, "play:game"), WidgetTaps.requestCode(8, "play:game"))
        assertNotEquals(WidgetTaps.address(7, "play:game"), WidgetTaps.address(8, "play:game"))
    }

    @Test
    fun `a widget id inside a key cannot be read as another widget's`() {
        // "play:42" on widget 1 and "play:1" on widget 42 were one string apart from colliding before.
        assertNotEquals(WidgetTaps.address(1, "play:42"), WidgetTaps.address(42, "play:1"))
        assertEquals("droidtop-widget://3/play%3Ax%2Fy", WidgetTaps.address(3, "play:x/y"))
    }
}
