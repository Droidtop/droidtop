package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopScaleTest {
    @Test
    fun `automatic follows the physical density, a quarter at a time, never below 1`() {
        assertEquals(2.0, DesktopScale.automatic(401f, 440), 0.0)
        assertEquals(1.0, DesktopScale.automatic(92f, 160), 0.0)
        assertEquals(1.0, DesktopScale.automatic(150f, 240), 0.0)
        assertEquals(1.5, DesktopScale.automatic(300f, 320), 0.0)
    }

    @Test
    fun `an unbelievable xdpi falls back to the density bucket`() {
        assertEquals(2.25, DesktopScale.automatic(0f, 440), 0.0)
        assertEquals(2.25, DesktopScale.automatic(5000f, 440), 0.0)
    }

    @Test
    fun `a stored choice wins, anything else is automatic`() {
        assertEquals(1.5, DesktopScale.resolve("1.5", 401f, 440), 0.0)
        assertEquals(2.0, DesktopScale.resolve(DesktopScale.AUTOMATIC, 401f, 440), 0.0)
        assertEquals(2.0, DesktopScale.resolve(null, 401f, 440), 0.0)
        assertEquals(2.0, DesktopScale.resolve("banana", 401f, 440), 0.0)
        assertEquals(2.0, DesktopScale.resolve("9", 401f, 440), 0.0)
    }

    @Test
    fun `labels read as a multiplier`() {
        assertEquals("2x", DesktopScale.label(2.0))
        assertEquals("1.25x", DesktopScale.label(1.25))
    }
}
