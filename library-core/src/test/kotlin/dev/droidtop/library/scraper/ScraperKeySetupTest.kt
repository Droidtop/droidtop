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
        assertEquals("https://dev.twitch.tv/console/apps", ScraperKeyService.IGDB.url)
        assertTrue(ScraperKeyService.STEAMGRIDDB.url.startsWith("https://www.steamgriddb.com/"))
        for (service in ScraperKeyService.entries) assertTrue(service.title, service.url.startsWith("https://"))
    }

    @Test
    fun `every service names its fields and what it gets`() {
        for (service in ScraperKeyService.entries) {
            assertTrue(service.title, service.fields.isNotEmpty())
            assertTrue(service.title, service.gets.isNotBlank())
            assertEquals(service.fields.map { it.id }, service.handoffFields().map { it.id })
            assertEquals(service.fields.map { it.secret }, service.handoffFields().map { it.secret })
        }
        assertEquals(listOf("client_id", "client_secret"), ScraperKeyService.IGDB.fields.map { it.id })
        assertEquals(listOf("username", "password"), ScraperKeyService.SCREENSCRAPER.fields.map { it.id })
    }

    @Test
    fun `no field shares a storage key with another service`() {
        val keys = ScraperKeyService.entries.flatMap { s -> s.fields.map { it.storeKey } }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(dev.droidtop.library.credentials.CredentialStore.KEYS.containsAll(keys))
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
