package dev.droidtop.shell.gamepad

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
}
