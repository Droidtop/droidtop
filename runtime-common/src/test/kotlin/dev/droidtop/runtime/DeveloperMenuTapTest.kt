package dev.droidtop.runtime

import dev.droidtop.library.settings.DeveloperMenu
import org.junit.Assert.assertEquals
import org.junit.Test

/** Seven taps on the version, each within a few seconds of the last, turn on the Developer menu (docs/SPEC.md 7, "Developer menu"). */
class DeveloperMenuTapTest {
    @Test
    fun `seven quick taps complete it, and it counts down`() {
        val count = DeveloperMenu.TapCount(7, 3_000L)
        val left = (0 until 7).map { count.tap(it * 500L) }
        assertEquals(listOf(6, 5, 4, 3, 2, 1, 0), left)
    }

    @Test
    fun `a long gap starts the count again`() {
        val count = DeveloperMenu.TapCount(7, 3_000L)
        repeat(5) { count.tap(it * 500L) }
        assertEquals(6, count.tap(10_000L))
    }

    @Test
    fun `after it completes the next tap starts a new count`() {
        val count = DeveloperMenu.TapCount(2, 3_000L)
        count.tap(0)
        assertEquals(0, count.tap(100))
        assertEquals(1, count.tap(200))
    }
}
