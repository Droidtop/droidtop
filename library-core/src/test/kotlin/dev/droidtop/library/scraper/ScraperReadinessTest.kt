package dev.droidtop.library.scraper

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A rejected key says where to fix it; nothing else pretends to be the person's to fix (docs/SPEC.md 7h). */
class ScraperReadinessTest {

    @Test
    fun `a TheGamesDB key the server rejects names the setting to change`() {
        val summary = formatScrapeSummary(
            systemName = "Mega Drive",
            targeted = 5,
            attempted = 5,
            found = 0,
            hashMatched = 0,
            thumbnailed = 0,
            miximaged = 0,
            failed = 0,
            refused = 5,
            lastRefusal = ScrapeLookup.Refused("TheGamesDB", 401, "Invalid API key"),
        )
        assertTrue(summary, summary.contains("refused every request"))
        assertTrue(summary, summary.contains("$SCRAPER_SETTINGS > TheGamesDB > API key"))
    }

    @Test
    fun `an IGDB sign-in refusal names the Twitch credentials`() {
        val fix = ScraperReadiness.credentialFix(ScrapeLookup.Refused("IGDB (Twitch sign-in)", 403, "invalid client secret"))
        assertTrue(fix.orEmpty(), fix.orEmpty().contains("Client ID and Client Secret"))
    }

    @Test
    fun `a quota or an outage is not presented as a key problem`() {
        assertNull(ScraperReadiness.credentialFix(ScrapeLookup.Refused("TheGamesDB", 429, "Quota")))
        assertNull(ScraperReadiness.credentialFix(ScrapeLookup.Refused("Lutris", 403, null)))
        assertNull(ScraperReadiness.credentialFix(ScrapeLookup.Refused("ScreenScraper", 503, "maintenance")))
    }

    @Test
    fun `the missing-key sentence says where the key goes and what needs none`() {
        val text = ScraperReadiness.THEGAMESDB_KEY_MISSING
        assertTrue(text, text.contains("thegamesdb.net"))
        assertTrue(text, text.contains("$SCRAPER_SETTINGS > TheGamesDB > API key"))
        assertTrue(text, text.contains("libretro database"))
    }
}
