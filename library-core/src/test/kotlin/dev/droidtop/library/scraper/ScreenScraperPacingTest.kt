package dev.droidtop.library.scraper

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenScraperPacingTest {
    @Test fun `requests keep the minimum interval`() {
        val pacing = ScreenScraperPacing(11_000)
        assertEquals(0, pacing.delayBeforeRequest(100))
        pacing.requestStarted(100)
        assertEquals(11_000, pacing.delayBeforeRequest(100))
        assertEquals(1_000, pacing.delayBeforeRequest(10_100))
    }

    @Test fun `quota refusals back off exponentially and ordinary refusals do not`() {
        val pacing = ScreenScraperPacing(0)
        pacing.response(403, "Forbidden", 100)
        assertEquals(0, pacing.delayBeforeRequest(100))
        pacing.response(429, "quota exceeded", 100)
        assertEquals(60_000, pacing.delayBeforeRequest(100))
        pacing.response(430, "", 100)
        assertEquals(120_000, pacing.delayBeforeRequest(100))
    }
}
