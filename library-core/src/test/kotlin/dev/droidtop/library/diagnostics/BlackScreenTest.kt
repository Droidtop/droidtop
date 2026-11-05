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

    @Test
    fun `a copy that reads all zero is not a picture, and a pointer on black is`() {
        // Droidtop/tracker#896: BlueStacks' PixelCopy gave 48x27 pixels of RGB 0, alpha 255, over a drawn menu.
        assertFalse(FrameDarkness.hasPicture(IntArray(48 * 27) { 0xFF000000.toInt() }))
        assertTrue(FrameDarkness.hasPicture(IntArray(48 * 27) { if (it == 600) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }))
        assertTrue(FrameDarkness.hasPicture(IntArray(4) { 0xFF0A0A0A.toInt() }))
    }

    @Test
    fun `copies that never showed anything never say black screen`() {
        val watch = BlackScreenWatch(holdMs = 10_000)
        val said = (0L..120_000L step 2_000L).filter { watch.sample(it, windowShown = true, flatDark = true, picture = false) }
        assertTrue(said.isEmpty())
    }

    @Test
    fun `once a picture has shown, a black screen after it is said as before`() {
        val watch = BlackScreenWatch(holdMs = 10_000)
        assertFalse(watch.sample(0, true, flatDark = true, picture = false))
        // The pointer on black: a picture, and dark.
        assertFalse(watch.sample(2_000, true, flatDark = true, picture = true))
        // Later copies read all zero again; the run has shown a picture, so they count.
        assertFalse(watch.sample(8_000, true, flatDark = true, picture = false))
        assertTrue(watch.sample(12_000, true, flatDark = true, picture = false))
    }
}
