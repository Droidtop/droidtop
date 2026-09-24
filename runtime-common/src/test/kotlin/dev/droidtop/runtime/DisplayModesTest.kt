package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayModesTest {
    @Test
    fun `the add-on's safe mode is flagged`() {
        // The mode the add-on came up in on the first live session,
        // against its native DRM mode.
        assertTrue(DisplayModes.isFallback(480, 640, 1080, 1920))
    }

    @Test
    fun `a rotated current size still compares against the unrotated mode`() {
        assertTrue(DisplayModes.isFallback(640, 480, 1080, 1920))
        assertFalse(DisplayModes.isFallback(1920, 1080, 1080, 1920))
    }

    @Test
    fun `a panel at its native mode is not flagged`() {
        assertFalse(DisplayModes.isFallback(1080, 1920, 1080, 1920))
    }

    @Test
    fun `a genuinely small panel is not flagged`() {
        assertFalse(DisplayModes.isFallback(480, 640, 480, 640))
    }

    @Test
    fun `1080p on a panel that also lists 4K is a choice, not a fault`() {
        assertFalse(DisplayModes.isFallback(1920, 1080, 3840, 2160))
    }

    @Test
    fun `an unknown size is never flagged`() {
        assertFalse(DisplayModes.isFallback(0, 0, 1080, 1920))
    }

    @Test
    fun `the summary names both sizes`() {
        val output = DisplayOutput(
            id = "9", androidDisplayId = 9, kind = DisplayOutputKind.SECOND_SCREEN,
            widthPx = 480, heightPx = 640, nativeWidthPx = 1080, nativeHeightPx = 1920,
        )
        assertTrue(output.isInFallbackMode)
        assertEquals("480×640 of 1080×1920", output.modeSummary())
    }
}
