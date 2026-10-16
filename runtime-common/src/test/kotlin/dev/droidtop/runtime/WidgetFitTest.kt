package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which widgets the picker offers (WidgetFit.kt). */
class WidgetFitTest {
    @Test
    fun `a widget that resizes takes its resize minimum, one that does not its own size`() {
        assertEquals(2, WidgetFit.smallest(span = 6, minResizeSpan = 2, resizes = true))
        assertEquals(6, WidgetFit.smallest(span = 6, minResizeSpan = 2, resizes = false))
        // The resize minimum never makes a widget larger than its own size.
        assertEquals(3, WidgetFit.smallest(span = 3, minResizeSpan = 5, resizes = true))
    }

    @Test
    fun `it fits when its smallest size is inside the grid and was measured`() {
        assertTrue(WidgetFit.fits(4, 2, columns = 5, rows = 4))
        assertTrue(WidgetFit.fits(5, 4, columns = 5, rows = 4))
        // Calendar's declared size, measured as hundreds of cells and not resizable: no place.
        assertFalse(WidgetFit.fits(WidgetFit.smallest(140, 1, resizes = false), 1, columns = 5, rows = 4))
        // Never measured (no profile): never shown as 0×0.
        assertFalse(WidgetFit.fits(0, 0, columns = 5, rows = 4))
    }
}
