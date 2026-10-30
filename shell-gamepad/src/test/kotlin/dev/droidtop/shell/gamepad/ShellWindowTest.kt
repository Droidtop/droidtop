package dev.droidtop.shell.gamepad

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ShellWindow.touchFirst] decides whether the shell's own touch bar
 * substitutes for a theme's help legend (docs/SPEC.md 7j). A Retroid
 * Pocket 5 tripped the short-side "phone-sized" branch even with its own
 * pad registered, which put the shell's touch-sized pills over the
 * theme's thin legend and made them overlap the system carousel (rig
 * capture, 2026-09-25) -- [ShellWindow.padPresent] is what tells the two
 * apart.
 */
class ShellWindowTest {

    @Test
    fun `portrait is touch-first regardless of a pad`() {
        assertTrue(ShellWindow(widthDp = 411, heightDp = 731, padPresent = false).touchFirst)
        assertTrue(ShellWindow(widthDp = 411, heightDp = 731, padPresent = true).touchFirst)
    }

    @Test
    fun `a rotated phone with no pad is touch-first`() {
        // 1080x1920 at 420dpi, landscape.
        assertTrue(ShellWindow(widthDp = 731, heightDp = 411, padPresent = false).touchFirst)
    }

    @Test
    fun `a Retroid Pocket 5 sized window with its own pad registered is not touch-first`() {
        // 1080x1920, 5.5", landscape -- 768x432dp (rig capture, 2026-09-25).
        assertFalse(ShellWindow(widthDp = 768, heightDp = 432, padPresent = true).touchFirst)
    }

    @Test
    fun `the same small landscape window with no pad falls back to touch-first`() {
        assertTrue(ShellWindow(widthDp = 768, heightDp = 432, padPresent = false).touchFirst)
    }

    @Test
    fun `the console's assumed 1280x720dp landscape is never touch-first`() {
        assertFalse(ShellWindow(widthDp = 1280, heightDp = 720, padPresent = false).touchFirst)
        assertFalse(ShellWindow(widthDp = 1280, heightDp = 720, padPresent = true).touchFirst)
    }

    @Test
    fun `a Retroid Pocket 5 panel lays out at the 1080p canvas`() {
        // 1080x1920 at 360dpi on a panel reporting 300 ppi: 853x480dp at 2.25, drawn at 1.5 (1280x720dp).
        assertEquals(1.5f, canvasDensity(2.25f, 853, 480, xdpi = 300f), 0.001f)
        // Held upright, the same.
        assertEquals(1.5f, canvasDensity(2.25f, 480, 853, xdpi = 300f), 0.001f)
        // The same pixels on a sharper 401 ppi panel stop at the legibility floor, 401/250.
        assertEquals(1.604f, canvasDensity(2.25f, 853, 480, xdpi = 401f), 0.001f)
        // No believable dpi: Android's 2.25 stands for the panel (360/250 = 1.44), so the canvas's 1.5 holds.
        assertEquals(1.5f, canvasDensity(2.25f, 853, 480, xdpi = 0f), 0.001f)
    }

    @Test
    fun `Interface size multiplies the canvas, once, whatever density the window already carries`() {
        // The Retroid Pocket 5's 1080x1920 panel: 1.5 on Auto, 1.875 at 125 percent, 1.125 at 75 percent.
        assertEquals(1.5f, scaledCanvasDensity(2.25f, 1920f, 1080f, xdpi = 300f, factor = 1f), 0.001f)
        assertEquals(1.875f, scaledCanvasDensity(2.25f, 1920f, 1080f, xdpi = 300f, factor = 1.25f), 0.001f)
        assertEquals(1.125f, scaledCanvasDensity(2.25f, 1920f, 1080f, xdpi = 300f, factor = 0.75f), 0.001f)
    }

    @Test
    fun `a window already at the canvas keeps its own density`() {
        // The 1080p DP screen at 1.5x, and a 4K television at 2x (1920x1080dp).
        assertEquals(1.5f, canvasDensity(1.5f, 1280, 720, xdpi = 92f), 0.001f)
        assertEquals(2f, canvasDensity(2f, 1920, 1080, xdpi = 80f), 0.001f)
    }

    @Test
    fun `touch targets stay a finger's size on the canvas`() {
        // A panel reporting 401 ppi: 0.3 inch is 120.3px, 80.2dp of the 1.5x canvas.
        assertEquals(80.2f, touchTargetDp(xdpi = 401f, systemDensity = 2.25f, layoutDensity = 1.5f), 0.1f)
        // A display reporting no believable dpi falls back to Android's 48dp at its own density.
        assertEquals(72f, touchTargetDp(xdpi = 0f, systemDensity = 2.25f, layoutDensity = 1.5f), 0.1f)
        // Never under 48dp of the layout.
        assertEquals(48f, touchTargetDp(xdpi = 100f, systemDensity = 1f, layoutDensity = 1f), 0.1f)
    }

    // The console's panel (5.5" 1080p, 400dpi) on the 1280x720dp canvas: a finger is 0.3 inch, 80dp of it.
    private val consoleFinger = touchTargetDp(xdpi = 400f, systemDensity = 2.25f, layoutDensity = 1.5f).dp

    @Test
    fun `where the pad drives, a press target is the shell's 48dp, not a finger's`() {
        // Droidtop/tracker#740: the finger size made the pad-driven Quick Menu sparse.
        val console = ShellWindow(widthDp = 1280, heightDp = 720, padPresent = true, minTouchTarget = consoleFinger)
        assertEquals(80f, consoleFinger.value, 0.5f)
        assertEquals(Measure.padTarget, console.pressTarget)
        // The DP screen at 1920x1080 with the pad: the same.
        assertEquals(Measure.padTarget, ShellWindow(widthDp = 1280, heightDp = 720, padPresent = true).pressTarget)
    }

    @Test
    fun `where touch comes first, a press target is a finger's`() {
        val phone = ShellWindow(widthDp = 411, heightDp = 731, padPresent = false, minTouchTarget = 64.dp)
        assertEquals(64.dp, phone.pressTarget)
        // The companion, held upright, is touch-first.
        val companion = ShellWindow(widthDp = 480, heightDp = 853, padPresent = true, minTouchTarget = consoleFinger)
        assertEquals(consoleFinger, companion.pressTarget)
    }

    @Test
    fun `every Quick Menu rail tab fits the console's panel with the pad`() {
        // The 10-28 rail: one 48dp tab per section, all of them in the 720dp-tall canvas less the panel's float margin
        // and the hint row. At a finger's 80dp they did not, and the rail scrolled ("not showing all the tabs").
        val console = ShellWindow(widthDp = 1280, heightDp = 720, padPresent = true, minTouchTarget = consoleFinger)
        val room = 720.dp - Space.Md * 2 - Measure.padTarget
        assertTrue(console.pressTarget * QuickSection.entries.size <= room)
        assertFalse(consoleFinger * QuickSection.entries.size <= room)
    }

    @Test
    fun `a pane of the console's window keeps its pad-driven sizes`() {
        // The Quick Menu's Plugins tab sees the window at its pane's width, about 376dp of the 720dp-tall canvas: taller
        // than wide, so it read as a phone held upright and its rows took the finger size (#740).
        val console = ShellWindow(widthDp = 1280, heightDp = 720, padPresent = true, minTouchTarget = consoleFinger)
        val pane = console.pane(376)
        assertEquals(376, pane.widthDp)
        assertFalse(pane.touchFirst)
        assertEquals(Measure.padTarget, pane.pressTarget)
        // A phone's pane stays touch-first.
        assertTrue(ShellWindow(widthDp = 411, heightDp = 731).pane(300).touchFirst)
    }
}
