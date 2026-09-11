package dev.droidtop.shell.gamepad.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins ES-DE's own box-versus-line arithmetic for a text element
 * (TextComponent.cpp:770-785): a zero height means one line, multi-line
 * happens only when the box is taller than a line, and a line taller
 * than its box overflows the box centred rather than being pushed down
 * inside it.
 */
class EsDeTextBoxFitTest {
    @Test
    fun `a zero height box becomes one line`() {
        val fit = esDeTextBoxFit(boxHeight = 0.dp, lineHeight = 75.dp)
        assertEquals(75.dp, fit.height)
        assertEquals(1, fit.maxLines)
        assertFalse(fit.overflows)
    }

    @Test
    fun `a box taller than a line wraps`() {
        val fit = esDeTextBoxFit(boxHeight = 300.dp, lineHeight = 75.dp)
        assertEquals(300.dp, fit.height)
        assertEquals(Int.MAX_VALUE, fit.maxLines)
        assertFalse(fit.overflows)
    }

    @Test
    fun `slate's metadata row is one line that overflows its own box`() {
        // Slate's vertical gamelist: size 0.155 x 0.02 of a 1920dp-tall
        // window with fontSize 0.026 and the default 1.5 line spacing.
        val box = (0.02f * 1920).dp
        val line = (0.026f * 1920 * 1.5f).dp
        val fit = esDeTextBoxFit(box, line)
        assertEquals(box, fit.height)
        assertEquals(1, fit.maxLines)
        assertTrue(fit.overflows)
    }

    @Test
    fun `an exactly one-line box is one line and does not overflow`() {
        val fit = esDeTextBoxFit(boxHeight = 75.dp, lineHeight = 75.dp)
        assertEquals(1, fit.maxLines)
        assertFalse(fit.overflows)
    }
}
