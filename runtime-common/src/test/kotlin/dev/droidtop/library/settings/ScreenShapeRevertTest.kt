package dev.droidtop.library.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ScreenShapeRevertTest {
    @Test fun `the countdown rounds up and stops at zero`() {
        assertEquals(10, ScreenShapeRevert.secondsLeft(10_000, 0))
        assertEquals(10, ScreenShapeRevert.secondsLeft(10_000, 1))
        assertEquals(1, ScreenShapeRevert.secondsLeft(10_000, 9_999))
        assertEquals(0, ScreenShapeRevert.secondsLeft(10_000, 10_000))
        assertEquals(0, ScreenShapeRevert.secondsLeft(10_000, 12_000))
    }

    @Test fun `keep clears the waiting change and a new one replaces it`() {
        ScreenShapeRevert.propose("Font size 130%", now = 0) {}
        assertEquals("Font size 130%", ScreenShapeRevert.pending.value?.label)
        assertEquals(10_000, ScreenShapeRevert.pending.value?.deadlineMillis)
        ScreenShapeRevert.propose("Display size Large", now = 0) {}
        val second = ScreenShapeRevert.pending.value
        assertEquals("Display size Large", second?.label)
        assertSame(second, ScreenShapeRevert.pending.value)
        ScreenShapeRevert.keep()
        assertNull(ScreenShapeRevert.pending.value)
    }
}
