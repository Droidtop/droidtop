package dev.droidtop.library.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** What an entry to the Updates page says (Droidtop/tracker#792). */
class UpdatesWaitingTest {
    @Test
    fun `an entry says how many are available, or that everything is up to date`() {
        assertEquals("Up to date", UpdatesWaiting.line(0))
        assertEquals("3 available", UpdatesWaiting.line(3))
    }

    @Test
    fun `a published count is never negative`() {
        UpdatesWaiting.publish(-2)
        assertEquals(0, UpdatesWaiting.count.value)
        UpdatesWaiting.publish(4)
        assertEquals(4, UpdatesWaiting.count.value)
    }
}
