package dev.droidtop.library.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Droidtop/tracker#601: a black screen with a pointer is noticed after a while; a picture or a fade never is. */
class BlackScreenTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val grey = 0xFF808080.toInt()

    private fun frame(size: Int = 48 * 27, fill: Int = black, others: Int = 0, other: Int = white) =
        IntArray(size) { if (it < others) other else fill }

    @Test
    fun aBlackFrameWithAPointerIsDark() {
        assertTrue(FrameDarkness.isFlatDark(frame()))
        // A pointer: a handful of bright pixels in 1,296.
        assertTrue(FrameDarkness.isFlatDark(frame(others = 20)))
        // A very dark grey, as some games clear to, is dark too.
        assertTrue(FrameDarkness.isFlatDark(frame(fill = 0xFF101010.toInt())))
    }

    @Test
    fun aPictureIsNotDark() {
        assertFalse(FrameDarkness.isFlatDark(frame(fill = grey)))
        assertFalse(FrameDarkness.isFlatDark(frame(others = 200)))
        assertFalse(FrameDarkness.isFlatDark(IntArray(0)))
    }

    @Test
    fun theScreenIsSaidOnceAfterItHasBeenBlackLongEnough() {
        val watch = BlackScreenWatch(holdMs = 10_000)
        val said = (0L..30_000L step 2_000L).filter { watch.sample(it, windowShown = true, flatDark = true) }
        assertEquals(listOf(10_000L), said)
    }

    @Test
    fun aBrightFrameOrNoWindowYetStartsTheCountAgain() {
        val watch = BlackScreenWatch(holdMs = 10_000)
        assertFalse(watch.sample(0, windowShown = false, flatDark = true))
        assertFalse(watch.sample(2_000, windowShown = true, flatDark = true))
        assertFalse(watch.sample(8_000, windowShown = true, flatDark = false))
        assertFalse(watch.sample(10_000, windowShown = true, flatDark = true))
        assertFalse(watch.sample(18_000, windowShown = true, flatDark = true))
        assertTrue(watch.sample(20_000, windowShown = true, flatDark = true))
    }

    @Test
    fun keepWaitingStartsANewCount() {
        val watch = BlackScreenWatch(holdMs = 10_000)
        watch.sample(0, true, true)
        assertTrue(watch.sample(10_000, true, true))
        watch.keepWaiting()
        assertFalse(watch.sample(12_000, true, true))
        assertFalse(watch.sample(20_000, true, true))
        assertTrue(watch.sample(22_000, true, true))
    }
}
