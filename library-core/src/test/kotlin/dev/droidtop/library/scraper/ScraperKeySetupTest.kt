package dev.droidtop.library.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guided key setup: short numbered steps and one honest state per source (docs/SPEC.md 7h). */
class ScraperKeySetupTest {

    @Test
    fun `every step is a label of at most five words, not prose`() {
        for (service in ScraperKeyService.entries) {
            assertTrue(service.title, service.steps.size in 3..5)
            for (step in service.steps) {
                assertTrue("$step has too many words", step.split(" ").size <= 5)
            }
        }
    }

    @Test
    fun `each guide points at the official https page`() {
        assertEquals("https://dev.twitch.tv/console", ScraperKeyService.IGDB.url)
        assertTrue(ScraperKeyService.STEAMGRIDDB.url.startsWith("https://www.steamgriddb.com/"))
    }

    @Test
    fun `state is Not set, Not tested or Connected`() {
        assertEquals("Not set", ScraperKeyState.label(configured = false, verified = true))
        assertEquals("Not tested", ScraperKeyState.label(configured = true, verified = false))
        assertEquals("Connected", ScraperKeyState.label(configured = true, verified = true))
    }

    @Test
    fun `a found or empty answer means connected`() {
        assertEquals("Connected", ScraperKeyState.outcome(ScrapeLookup.Found(listOf(1))))
        assertEquals("Connected", ScraperKeyState.outcome(ScrapeLookup.NoMatch))
    }

    @Test
    fun `a rejected credential is one short line telling the person to check it`() {
        assertEquals(
            "Rejected: check what you pasted",
            ScraperKeyState.outcome(ScrapeLookup.Refused("SteamGridDB", 401, "Unauthorized")),
        )
        assertEquals(
            "Rejected: check what you pasted",
            ScraperKeyState.outcome(ScrapeLookup.Refused(IgdbScraperClient.SIGNIN_SOURCE, 400, "invalid client")),
        )
    }

    @Test
    fun `another refusal gives its status and the first line of the server's reason`() {
        val line = ScraperKeyState.outcome(ScrapeLookup.Refused("SteamGridDB", 429, "Too many requests\nretry later"))
        assertEquals("Refused (429): Too many requests", line)
        assertEquals("Refused (503)", ScraperKeyState.outcome(ScrapeLookup.Refused("IGDB", 503, null)))
    }
}
