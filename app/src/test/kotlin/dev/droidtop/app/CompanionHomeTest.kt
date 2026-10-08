package dev.droidtop.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** The companion Home's pure labels (docs/SPEC.md "The companion's tabs", Droidtop/tracker#328). */
class CompanionHomeTest {
    private val start = 1_000_000_000L

    @Test
    fun `a session under a minute has just started`() {
        assertEquals("Just started", sessionLabel(start, start + 59_000))
    }

    @Test
    fun `a session is counted in minutes, then hours and minutes`() {
        assertEquals("12 min", sessionLabel(start, start + 12 * 60_000 + 30_000))
        assertEquals("1 h 05 min", sessionLabel(start, start + 65 * 60_000))
    }

    @Test
    fun `a clock that went backwards never shows a negative session`() {
        assertEquals("Just started", sessionLabel(start, start - 5_000))
    }

    @Test
    fun `playtime is minutes under an hour and whole hours above`() {
        assertEquals("45 min played", playtimeLabel(45 * 60L))
        assertEquals("12 h played", playtimeLabel(12 * 3600L + 59 * 60L))
    }
}
