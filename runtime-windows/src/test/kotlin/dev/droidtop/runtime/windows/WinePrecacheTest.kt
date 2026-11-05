package dev.droidtop.runtime.windows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WinePrecacheTest {
    @Test
    fun `the run stops when its time is up, whatever the cache did`() {
        assertTrue(WinePrecache.stopNow(WinePrecache.BUDGET_MS, grew = false, quietMs = 0))
        assertFalse(WinePrecache.stopNow(WinePrecache.BUDGET_MS - 1, grew = false, quietMs = WinePrecache.QUIET_MS * 10))
    }

    @Test
    fun `a cache that grew and then went quiet ends the run early`() {
        assertTrue(WinePrecache.stopNow(60_000, grew = true, quietMs = WinePrecache.QUIET_MS))
        assertFalse(WinePrecache.stopNow(60_000, grew = true, quietMs = WinePrecache.QUIET_MS - 1))
        // Quiet before anything was written is a game still loading, not a finished one.
        assertFalse(WinePrecache.stopNow(60_000, grew = false, quietMs = WinePrecache.QUIET_MS * 2))
    }

    @Test
    fun `progress is the share of the time given, short of done`() {
        assertEquals(0, WinePrecache.progress(0))
        assertEquals(50, WinePrecache.progress(WinePrecache.BUDGET_MS / 2))
        assertEquals(99, WinePrecache.progress(WinePrecache.BUDGET_MS * 2))
    }
}
