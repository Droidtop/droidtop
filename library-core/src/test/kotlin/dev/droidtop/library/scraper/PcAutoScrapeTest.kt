package dev.droidtop.library.scraper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PC games' automatic details (Droidtop/tracker#568): on by default, at most every twelve hours, never when off. */
class PcAutoScrapeTest {
    private val now = 100L * 24 * 60 * 60 * 1000

    @Test
    fun `a first run is due, a recent one is not, and off is never due`() {
        assertTrue(PcAutoScrape.due(enabled = true, lastMs = 0L, now = now))
        assertFalse(PcAutoScrape.due(enabled = true, lastMs = now - 60_000, now = now))
        assertTrue(PcAutoScrape.due(enabled = true, lastMs = now - PcAutoScrape.MIN_INTERVAL_MS, now = now))
        assertFalse(PcAutoScrape.due(enabled = false, lastMs = 0L, now = now))
    }
}
