package dev.droidtop.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When droidtop opens Home again after replacing itself (Droidtop/tracker#202). */
class UpdateRelaunchTest {
    private val now = 10_000_000_000L

    @Test
    fun `an update armed a moment ago relaunches`() {
        assertTrue(UpdateRelaunch.shouldRelaunch(now - 90_000, now))
    }

    @Test
    fun `nothing armed, or armed long ago, leaves the screen alone`() {
        assertFalse(UpdateRelaunch.shouldRelaunch(0, now))
        assertFalse(UpdateRelaunch.shouldRelaunch(now - UpdateRelaunch.VALID_MS - 1, now))
    }

    @Test
    fun `a record from the future is not trusted`() {
        assertFalse(UpdateRelaunch.shouldRelaunch(now + 5_000, now))
    }
}
