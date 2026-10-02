package dev.droidtop.runtime.tasks

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaunchLedgerTest {
    @After
    fun reset() = LaunchLedger.clearForTest()

    @Test
    fun `the last launch is the app in front, and the list is most recent first`() {
        LaunchLedger.note("com.android.calendar", 0, nowMs = 1_000)
        LaunchLedger.note("org.example.browser", 2, nowMs = 2_000)

        assertEquals("org.example.browser", LaunchLedger.last?.packageName)
        assertEquals(listOf("org.example.browser", "com.android.calendar"), LaunchLedger.entries(nowMs = 3_000).map { it.packageName })
    }

    @Test
    fun `a launch older than the age limit is no longer listed`() {
        LaunchLedger.note("com.android.calendar", 0, nowMs = 0)

        assertEquals(0, LaunchLedger.entries(nowMs = LaunchLedger.MAX_AGE_MS + 1).size)
    }

    @Test
    fun `forgetting the last app clears it`() {
        LaunchLedger.note("com.android.calendar", 0, nowMs = 1_000)
        LaunchLedger.forget("com.android.calendar")

        assertNull(LaunchLedger.last)
        assertEquals(0, LaunchLedger.entries(nowMs = 1_001).size)
    }
}
