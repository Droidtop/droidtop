package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.InstallVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A game folder's row draws how full its partition is (show, then say: Droidtop/tracker#738). */
class StoreInstallOfferBarTest {
    private fun volume(free: Long, total: Long) = InstallVolume("Games", "/storage/Games", free, total)

    @Test
    fun `the bar fills with the space used`() {
        assertEquals(0.75f, usedFraction(volume(free = 25, total = 100))!!, 0.0001f)
        assertEquals(0f, usedFraction(volume(free = 100, total = 100))!!, 0f)
        assertEquals(1f, usedFraction(volume(free = 0, total = 100))!!, 0f)
    }

    @Test
    fun `a partition that reports no size draws no bar, and a free count past the size is held to it`() {
        assertNull(usedFraction(volume(free = 10, total = 0)))
        assertEquals(0f, usedFraction(volume(free = 500, total = 100))!!, 0f)
    }
}
