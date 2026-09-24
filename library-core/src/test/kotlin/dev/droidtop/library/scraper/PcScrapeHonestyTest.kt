package dev.droidtop.library.scraper

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.PcInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/SPEC.md section 7h, applied to the PC/engine scrape: a refusal is not
 * a miss for Lutris, IGDB or the Steam store either, and a Steam game is
 * matched by its app id rather than by a guess at its title. All of it is
 * pure; none of it needs a network or a credential.
 */
class PcScrapeHonestyTest {

    // ---- the summary --------------------------------------------------------

    @Test
    fun `a PC pass refused everything reads as an outage, naming who refused`() {
        val counts = PcScrapeCounts(targeted = 20).apply {
            attempted = 5
            refused = 5
            lastRefusal = ScrapeLookup.Refused("IGDB (Twitch sign-in)", 400, "invalid client secret")
        }
        val summary = formatPcScrapeSummary("IGDB", counts)
        assertFalse(summary, summary.contains("no result"))
        assertTrue(summary, summary.contains("IGDB (Twitch sign-in) refused every request"))
        assertTrue(summary, summary.contains("invalid client secret"))
        assertTrue(summary, summary.contains("says nothing about whether your games are in the database"))
    }

    @Test
    fun `refusals, misses, picks and store-id matches are each counted as themselves`() {
        val counts = PcScrapeCounts(targeted = 10).apply {
            attempted = 10
            byStoreId = 3
            byName = 2
            needsPicking = 2
            noMatch = 1
            failed = 1
            refused = 1
            lastRefusal = ScrapeLookup.Refused("Steam store", 429, null)
        }
        val summary = formatPcScrapeSummary("Lutris", counts)
        assertTrue(summary, summary.startsWith("Lutris: matched 5 of 10 (3 by store id)"))
        assertTrue(summary, summary.contains("2 need a match you pick"))
        assertTrue(summary, summary.contains("1 had no result at all"))
        assertTrue(summary, summary.contains("1 failed"))
        assertTrue(summary, summary.contains("1 refused by the server"))
        assertTrue(summary, summary.contains("Steam store HTTP 429"))
    }

    @Test
    fun `a pass that gave up says how many games were never asked about`() {
        val counts = PcScrapeCounts(targeted = 40).apply {
            attempted = 6
            byName = 1
            refused = 5
            lastRefusal = ScrapeLookup.Refused("Lutris", 503, "Service Unavailable")
        }
        val summary = formatPcScrapeSummary("Lutris", counts)
        assertTrue(summary, summary.contains("34 not asked for after the pass gave up"))
        assertFalse(summary, summary.contains("no result"))
    }

    @Test
    fun `mapping a lookup never turns a refusal into a result`() {
        val refused: ScrapeLookup<List<Int>> = ScrapeLookup.Refused("Lutris", 403, null)
        assertEquals(refused, refused.mapFound { it.map(Int::toString) })
        val none: ScrapeLookup<List<Int>> = ScrapeLookup.NoMatch
        assertEquals(ScrapeLookup.NoMatch, none.mapFound { it.size })
        assertEquals(ScrapeLookup.Found(2), ScrapeLookup.Found(listOf(1, 2)).mapFound { it.size })
    }

    // ---- Lutris -------------------------------------------------------------

    @Test
    fun `an empty Lutris result is a real miss, and a populated one keeps its cover`() {
        assertEquals(ScrapeLookup.NoMatch, LutrisScraperClient.parse(JSONObject("""{"results": []}""")))
        val found = LutrisScraperClient.parse(
            JSONObject(
                """{"results": [{"name": "The Witch's House", "slug": "the-witchs-house", "year": 2012,
                   "coverart": "https://lutris.net/media/igdb/cover/abc.jpg", "banner_url": ""}]}""",
            ),
        ).foundOrNull!!.single()
        assertEquals("The Witch's House", found.name)
        assertEquals(2012, found.year)
        assertEquals("https://lutris.net/media/igdb/cover/abc.jpg", found.coverUrl)
    }

    // ---- the Steam store ----------------------------------------------------

    @Test
    fun `a Steam app id is read from the entry id or from the store id an engine game carries`() {
        val store = LibraryEntry(id = "steam:440", title = "Team Fortress 2", kind = LibraryEntryKind.WINE_PROFILE)
        assertEquals(440, PcScraper.steamAppIdOf(store))
        val engine = LibraryEntry(
            id = "/storage/games/Steam/steamapps/common/Doki Doki Literature Club",
            title = "Doki Doki Literature Club",
            kind = LibraryEntryKind.RENPY,
            pcInfo = PcInfo(source = "Steam", storeId = "steam:698780", installed = true),
        )
        assertEquals(698780, PcScraper.steamAppIdOf(engine))
        val gog = LibraryEntry(id = "gog:1207658924", title = "Some GOG Game", kind = LibraryEntryKind.WINE_PROFILE)
        assertNull(PcScraper.steamAppIdOf(gog))
    }

    @Test
    fun `a Steam storefront record is mapped onto ES-DE's metadata conventions`() {
        // The response SHAPE of store.steampowered.com/api/appdetails,
        // transcribed from Steam's own storefront format.
        val response = JSONObject(
            """
            {"440": {"success": true, "data": {
              "type": "game",
              "name": "Team Fortress 2",
              "steam_appid": 440,
              "short_description": "Nine distinct classes &amp; a &quot;hat&quot; economy.",
              "header_image": "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/440/header.jpg",
              "developers": ["Valve"],
              "publishers": ["Valve"],
              "genres": [{"id": "1", "description": "Action"}, {"id": "37", "description": "Free To Play"}],
              "release_date": {"coming_soon": false, "date": "Oct 10, 2007"}
            }}}
            """.trimIndent(),
        )
        val match = SteamStoreClient.parse(440, response).foundOrNull!!
        assertEquals("Team Fortress 2", match.name)
        assertEquals("Nine distinct classes & a \"hat\" economy.", match.description)
        assertEquals("Valve", match.developer)
        assertEquals("Action, Free To Play", match.genre)
        assertEquals("20071010T000000", match.releaseDate)
        assertEquals(2007, match.year)
        assertTrue(match.coverUrl!!.endsWith("/apps/440/library_600x900.jpg"))
        assertTrue(match.alternateCoverUrl!!.endsWith("/apps/440/header.jpg"))
    }

    @Test
    fun `an app with no public store page is a real miss`() {
        assertEquals(ScrapeLookup.NoMatch, SteamStoreClient.parse(12, JSONObject("""{"12": {"success": false}}""")))
        assertEquals(ScrapeLookup.NoMatch, SteamStoreClient.parse(12, JSONObject("""{}""")))
    }

    @Test
    fun `Steam's regional date shapes parse, and a year alone never becomes January 1st`() {
        assertEquals("20071010T000000", SteamStoreClient.parseReleaseDate("10 Oct, 2007"))
        assertEquals("20071010T000000", SteamStoreClient.parseReleaseDate("Oct 10, 2007"))
        assertNull(SteamStoreClient.parseReleaseDate("2007"))
        assertNull(SteamStoreClient.parseReleaseDate("Q1 2025"))
        assertNull(SteamStoreClient.parseReleaseDate("Coming soon"))
    }

    @Test
    fun `an unreleased game's placeholder date is not written`() {
        val response = JSONObject(
            """{"9": {"success": true, "data": {"name": "Soon", "release_date": {"coming_soon": true, "date": "Oct 10, 2030"}}}}""",
        )
        assertNull(SteamStoreClient.parse(9, response).foundOrNull!!.releaseDate)
    }

    // ---- where the cover goes -----------------------------------------------

    @Test
    fun `a store title becomes a name an exFAT card accepts`() {
        assertEquals("Half-Life Alyx", PcMediaLayout.fileSafe("Half-Life: Alyx"))
        assertEquals("What Is This Game", PcMediaLayout.fileSafe("What? Is/This \"Game\"..."))
        assertEquals("Eternum-0.9.5-pc", PcMediaLayout.fileSafe("Eternum-0.9.5-pc"))
        assertEquals("game", PcMediaLayout.fileSafe("???"))
    }
}
