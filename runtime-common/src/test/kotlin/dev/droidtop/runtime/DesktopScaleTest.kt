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

    @Test
    fun `resolution falls back to full for anything that is not a fraction`() {
        assertEquals(1.0, DesktopScale.resolutionFactor(null), 0.0)
        assertEquals(1.0, DesktopScale.resolutionFactor(DesktopScale.FULL_RESOLUTION), 0.0)
        assertEquals(1.0, DesktopScale.resolutionFactor("banana"), 0.0)
        assertEquals(1.0, DesktopScale.resolutionFactor("2"), 0.0)
        assertEquals(1.0, DesktopScale.resolutionFactor("0.1"), 0.0)
        assertEquals(0.75, DesktopScale.resolutionFactor("0.75"), 0.0)
        assertEquals(0.5, DesktopScale.resolutionFactor("0.5"), 0.0)
    }

    @Test
    fun `lower resolution gives an even smaller output and keeps the logical size`() {
        assertEquals(1920 to 1080, DesktopScale.outputSize(1920, 1080, 1.0))
        assertEquals(1440 to 810, DesktopScale.outputSize(1920, 1080, 0.75))
        assertEquals(960 to 540, DesktopScale.outputSize(1920, 1080, 0.5))
        // odd sides round down to even, and a tiny view never reaches zero
        assertEquals(722 to 402, DesktopScale.outputSize(963, 537, 0.75))
        assertEquals(2 to 2, DesktopScale.outputSize(1, 1, 0.5))
        // 2x scale on 1920 wide is 960 logical; 1x on 960 pixels is 960 logical too
        assertEquals(1.0, DesktopScale.outputScale(2.0, 0.5), 0.0)
        assertEquals(0.5, DesktopScale.outputScale(1.0, 0.5), 0.0)
        assertEquals(960.0, 1920 / 2.0, 0.0)
        assertEquals(960.0, DesktopScale.outputSize(1920, 1080, 0.5).first / DesktopScale.outputScale(2.0, 0.5), 0.0)
    }
}
