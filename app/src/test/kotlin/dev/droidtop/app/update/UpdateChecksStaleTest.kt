package dev.droidtop.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a place that shows updates checks again (Droidtop/tracker#792). */
class UpdateChecksStaleTest {
    @Test
    fun `a check more than 15 minutes old is checked again`() {
        val now = 10_000_000L
        assertTrue(UpdateChecks.isStale(now - UpdateChecks.STALE_MS, now))
        assertTrue(UpdateChecks.isStale(0L, now))
    }

    @Test
    fun `a recent check is not repeated`() {
        val now = 10_000_000L
        assertFalse(UpdateChecks.isStale(now - UpdateChecks.STALE_MS + 1, now))
        assertFalse(UpdateChecks.isStale(now, now))
    }
}
