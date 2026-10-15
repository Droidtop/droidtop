package dev.droidtop.library.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [totalRefusalSummary] speaks only for a pass refused everything; [describeRefusal] carries the server's own words. */
class ScrapeRefusalSummaryTest {
    private val refusal = ScrapeLookup.Refused("Foo", 500, "busy")

    @Test
    fun `a pass refused every request is reported as an outage`() {
        assertEquals(
            "Scan: Foo refused every request (3 of 3 refused; Foo HTTP 500: busy)." +
                " Nothing was scraped, and this says nothing about whether your games are in the database.",
            totalRefusalSummary("Scan", attempted = 3, found = 0, refused = 3, lastRefusal = refusal),
        )
    }

    @Test
    fun `a pass with any result or any request not refused is not an outage`() {
        assertNull(totalRefusalSummary("Scan", attempted = 3, found = 1, refused = 2, lastRefusal = refusal))
        assertNull(totalRefusalSummary("Scan", attempted = 3, found = 0, refused = 2, lastRefusal = refusal))
        assertNull(totalRefusalSummary("Scan", attempted = 3, found = 0, refused = 0, lastRefusal = null))
    }

    @Test
    fun `a refusal with no reason says so and no refusal says nothing`() {
        assertEquals(" (2 of 5 refused; Foo HTTP 429: the server gave no reason).", describeRefusal(2, 5, ScrapeLookup.Refused("Foo", 429, null)))
        assertEquals("", describeRefusal(1, 1, null))
    }
}
