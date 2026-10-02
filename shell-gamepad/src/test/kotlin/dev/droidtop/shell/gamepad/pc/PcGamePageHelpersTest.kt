package dev.droidtop.shell.gamepad.pc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two phrases the PC game page's facts strip, its Details tab and the
 * Home hero card share (docs/SPEC.md 7i, Droidtop/tracker#277): when a game
 * was last played and how long it has been played, held to their wording on
 * the JVM so the strip and the tab cannot drift apart again.
 */
class PcGamePageHelpersTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L

    @Test
    fun `played in the last day reads today`() {
        assertEquals("today", lastPlayedPhrase(now, now))
        assertEquals("today", lastPlayedPhrase(now, now - day + 1))
    }

    @Test
    fun `a clock slightly behind the last play never reads in the future`() {
        assertEquals("today", lastPlayedPhrase(now, now + 5 * 60 * 1000))
    }

    @Test
    fun `one day ago reads yesterday`() {
        assertEquals("yesterday", lastPlayedPhrase(now, now - day))
        assertEquals("yesterday", lastPlayedPhrase(now, now - 2 * day + 1))
    }

    @Test
    fun `two to thirteen days read as a day count`() {
        assertEquals("2 days ago", lastPlayedPhrase(now, now - 2 * day))
        assertEquals("13 days ago", lastPlayedPhrase(now, now - 13 * day))
    }

    @Test
    fun `fourteen to fifty nine days read as weeks`() {
        assertEquals("2 weeks ago", lastPlayedPhrase(now, now - 14 * day))
        assertEquals("8 weeks ago", lastPlayedPhrase(now, now - 59 * day))
    }

    @Test
    fun `sixty days to a year read as months`() {
        assertEquals("2 months ago", lastPlayedPhrase(now, now - 60 * day))
        assertEquals("12 months ago", lastPlayedPhrase(now, now - 364 * day))
    }

    @Test
    fun `a year or more reads over a year ago`() {
        assertEquals("over a year ago", lastPlayedPhrase(now, now - 365 * day))
        assertEquals("over a year ago", lastPlayedPhrase(now, now - 3000 * day))
    }

    @Test
    fun `no play time reads not played yet`() {
        assertEquals("Not played yet", playtimeShort(0))
        assertEquals("Not played yet", playtimeShort(-5))
    }

    @Test
    fun `under a minute is named as such`() {
        assertEquals("Under a minute", playtimeShort(1))
        assertEquals("Under a minute", playtimeShort(59))
    }

    @Test
    fun `minutes only`() {
        assertEquals("1 min", playtimeShort(60))
        assertEquals("45 min", playtimeShort(45 * 60L))
        assertEquals("59 min", playtimeShort(59 * 60L + 59))
    }

    @Test
    fun `whole hours leave the minutes out`() {
        assertEquals("1 h", playtimeShort(3600))
        assertEquals("3 h", playtimeShort(3 * 3600L + 30))
    }

    @Test
    fun `hours and minutes`() {
        assertEquals("1 h 1 min", playtimeShort(3660))
        assertEquals("2 h 5 min", playtimeShort(2 * 3600L + 5 * 60))
    }
}
