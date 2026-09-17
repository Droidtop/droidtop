package dev.droidtop.shell.gamepad.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Appearance step's theme previews are frames of the SCREEN'S own
 * shape, not a fixed 16:9 plate (rig, build 546: the theme that lays out
 * a tall screen was still previewed wide on a phone held upright).
 */
class ThemePreviewFrameTest {

    @Test
    fun `a landscape screen gives a wide frame`() {
        val (width, height) = esDePreviewFrame(longEdge = 112f, screenWidth = 1920f, screenHeight = 1080f)
        assertEquals(112f, width, 0.01f)
        assertEquals(63f, height, 0.01f)
    }

    @Test
    fun `a portrait screen gives a tall frame of the same proportion`() {
        val (width, height) = esDePreviewFrame(longEdge = 112f, screenWidth = 1080f, screenHeight = 1920f)
        assertEquals(63f, width, 0.01f)
        assertEquals(112f, height, 0.01f)
    }

    @Test
    fun `a square screen gives a square frame`() {
        val (width, height) = esDePreviewFrame(longEdge = 112f, screenWidth = 1000f, screenHeight = 1000f)
        assertEquals(112f, width, 0.01f)
        assertEquals(112f, height, 0.01f)
    }

    @Test
    fun `a screen that reports no size at all does not divide by zero`() {
        val (width, height) = esDePreviewFrame(longEdge = 112f, screenWidth = 0f, screenHeight = 0f)
        assertEquals(112f, width, 0.01f)
        assertEquals(112f, height, 0.01f)
    }
}
