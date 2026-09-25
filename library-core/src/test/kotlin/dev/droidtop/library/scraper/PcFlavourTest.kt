package dev.droidtop.library.scraper

import dev.droidtop.library.GameLink
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.PcInfo
import dev.droidtop.library.consoles.GameMetadataEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/SPEC.md 7h, "PC games get PC-native sources": SteamGridDB's answers,
 * the ids Lutris and IGDB name, which source wins which field, and the
 * per-field source record. All pure; no network, no key.
 */
class PcFlavourTest {

    // ---- Lutris -------------------------------------------------------------

    @Test
    fun `Lutris's provider_games name the Steam and GOG ids of a search result`() {
        // The shape of a live answer for "hollow knight" (2026-09-25).
        val found = LutrisScraperClient.parse(
            JSONObject(
                """{"results": [{"name": "Hollow Knight", "slug": "hollow-knight", "year": 2017, "coverart": "",
                   "provider_games": [
                     {"name": "Hollow Knight", "slug": "1308320804", "service": "gog"},
                     {"name": "Hollow Knight", "slug": "hollow_knight_YZt44", "service": "humblebundle"},
                     {"name": "Hollow Knight", "slug": "367520", "service": "steam"},
                     {"name": "Hollow Knight", "slug": "hollow-knight", "service": "igdb"}]}]}""",
            ),
        ).foundOrNull!!.single()
        assertEquals(367520, found.steamAppId)
        assertEquals("1308320804", found.gogId)
        assertNull(found.coverUrl)
    }

    @Test
    fun `a result with no provider_games carries no store ids`() {
        val found = LutrisScraperClient.parse(JSONObject("""{"results": [{"name": "Eternum", "slug": "eternum"}]}""")).foundOrNull!!.single()
        assertNull(found.steamAppId)
        assertNull(found.gogId)
    }

    @Test
    fun `Lutris's per-game record gives a description and genres, and nothing else is claimed`() {
        val details = LutrisScraperClient.parseDetails(
            JSONObject(
                """{"name": "Hollow Knight", "description": "A 2D Metroidvania.",
                   "genres": [{"name": "Action"}, {"name": "Metroidvania"}], "steamid": 367520}""",
            ),
        ).foundOrNull!!
        assertEquals("A 2D Metroidvania.", details.description)
        assertEquals("Action, Metroidvania", details.genre)
        assertEquals(ScrapeLookup.NoMatch, LutrisScraperClient.parseDetails(JSONObject("""{"name": "Bare", "genres": []}""")))
    }

    // ---- IGDB ---------------------------------------------------------------

    @Test
    fun `IGDB's series, links and store ids come out of one row`() {
        val parsed = IgdbScraperClient.parse(
            JSONObject(
                """{"id": 14593, "name": "Hollow Knight",
                   "collections": [{"name": "Hollow Knight"}],
                   "franchise": {"name": "Team Cherry"},
                   "websites": [
                     {"url": "https://www.hollowknight.com", "type": 1},
                     {"url": "https://store.steampowered.com/app/367520", "type": 13},
                     {"url": "https://example.org/fan", "type": 999}],
                   "external_games": [
                     {"uid": "367520", "external_game_source": 1},
                     {"uid": "1308320804", "external_game_source": 5}]}""",
            ),
        )!!
        assertEquals(14593L, parsed.id)
        assertEquals("Hollow Knight", parsed.series)
        assertEquals(
            listOf(
                GameLink("Official website", "https://www.hollowknight.com"),
                GameLink("Steam", "https://store.steampowered.com/app/367520"),
                GameLink("example.org", "https://example.org/fan"),
            ),
            parsed.links,
        )
        assertEquals(367520, parsed.steamAppId)
        assertEquals("1308320804", parsed.gogId)
    }

    @Test
    fun `a franchise stands in for the series only when there is no collection`() {
        val parsed = IgdbScraperClient.parse(JSONObject("""{"name": "X", "franchises": [{"name": "Brand"}]}"""))!!
        assertEquals("Brand", parsed.series)
        assertTrue(parsed.links.isEmpty())
    }

    // ---- SteamGridDB --------------------------------------------------------

    @Test
    fun `a SteamGridDB search is names, ids and years`() {
        val games = SteamGridDbScraperClient.parseSearch(
            JSONObject(
                """{"success": true, "data": [
                   {"id": 4242, "name": "Hollow Knight", "types": ["steam"], "verified": true, "release_date": 1487894400},
                   {"id": 0, "name": "broken row"},
                   {"id": 77, "name": "Hollow Knight: Silksong"}]}""",
            ),
        ).foundOrNull!!
        assertEquals(listOf(SteamGridDbGame(4242, "Hollow Knight", 2017), SteamGridDbGame(77, "Hollow Knight: Silksong", null)), games)
        assertEquals(ScrapeLookup.NoMatch, SteamGridDbScraperClient.parseSearch(JSONObject("""{"success": true, "data": []}""")))
    }

    @Test
    fun `the best image is the first one the server lists`() {
        val body = JSONObject("""{"success": true, "data": [{"id": 1, "url": ""}, {"id": 2, "url": "https://cdn2.steamgriddb.com/grid/a.png"}]}""")
        assertEquals("https://cdn2.steamgriddb.com/grid/a.png", SteamGridDbScraperClient.firstImageUrl(body))
        assertNull(SteamGridDbScraperClient.firstImageUrl(JSONObject("""{"success": true, "data": []}""")))
    }

    @Test
    fun `a success-false answer is a refusal carrying the server's own sentence`() {
        val refused = SteamGridDbScraperClient.unwrap(JSONObject("""{"success": false, "errors": ["Invalid key format"]}"""))
        assertEquals(ScrapeLookup.Refused("SteamGridDB", 200, "Invalid key format"), refused)
        // And the same body on a 401 reads as its sentence, not as JSON.
        assertEquals(
            "Invalid key format",
            ScrapeRefusals.summarizeErrorBody("""{"success":false,"errors":["Invalid key format"]}""", emptyList()),
        )
    }

    @Test
    fun `a rejected SteamGridDB key names where to change it`() {
        val fix = ScraperReadiness.credentialFix(ScrapeLookup.Refused("SteamGridDB", 401, "Invalid key format")).orEmpty()
        assertTrue(fix, fix.contains("$SCRAPER_SETTINGS > SteamGridDB > API key"))
        assertTrue(ScraperReadiness.STEAMGRIDDB_KEY_MISSING.contains("steamgriddb.com/profile/preferences/api"))
        assertTrue(ScraperReadiness.STEAMGRIDDB_KEY_MISSING.contains("$SCRAPER_SETTINGS > SteamGridDB > API key"))
    }

    // ---- ids ----------------------------------------------------------------

    @Test
    fun `an entry's own store ids are read from its id and from the store id it carries`() {
        val gog = LibraryEntry(id = "gog:1207658924", title = "A GOG game", kind = LibraryEntryKind.WINE_PROFILE)
        assertEquals(PcGameIds(gogId = "1207658924"), PcGameIds.of(gog))
        val engine = LibraryEntry(
            id = "/storage/games/Doki Doki Literature Club",
            title = "Doki Doki Literature Club",
            kind = LibraryEntryKind.RENPY,
            pcInfo = PcInfo(source = "Steam", storeId = "steam:698780", installed = true),
        )
        assertEquals(698780, PcGameIds.of(engine).steamAppId)
        assertEquals(PcStoreId("steam", "698780"), PcGameIds.of(engine).storeId)
        assertEquals(PcGameIds(steamAppId = 1, gogId = "g"), PcGameIds(steamAppId = 1).orFrom(PcGameIds(steamAppId = 2, gogId = "g")))
    }

    // ---- which source wins which field --------------------------------------

    @Test
    fun `text prefers IGDB then the Steam store then Lutris, art prefers SteamGridDB`() {
        val igdb = PcMatch(name = "Game", sourceLabel = "IGDB", description = "igdb text", coverUrl = "igdb-cover", series = "Saga")
        val steam = PcMatch(
            name = "Game", sourceLabel = "Steam store",
            description = "steam text", developer = "Dev", coverUrl = "capsule", alternateCoverUrl = "header",
        )
        val lutris = PcMatch(name = "Game", sourceLabel = "Lutris", genre = "Action")
        val identity = PcMatch(name = "Game", sourceLabel = "Lutris", coverUrl = "lutris-cover")
        val sgdb = PcMatch(name = "Game", sourceLabel = "SteamGridDB", coverUrl = "grid", heroUrl = "hero", logoUrl = "logo", iconUrl = "icon")

        val record = PcRecord.of(text = listOf(igdb, steam, lutris, identity), art = listOf(sgdb, steam, igdb, identity))

        assertEquals(Sourced("igdb text", "IGDB"), record.description)
        assertEquals(Sourced("Dev", "Steam store"), record.developer)
        assertEquals(Sourced("Action", "Lutris"), record.genre)
        assertEquals(Sourced("Saga", "IGDB"), record.series)
        assertNull(record.publisher)
        assertEquals(listOf("grid", "capsule", "header", "igdb-cover", "lutris-cover"), record.covers.map { it.value })
        assertEquals("SteamGridDB", record.covers.first().source)
        assertEquals(Sourced("hero", "SteamGridDB"), record.hero)
        assertEquals(Sourced("logo", "SteamGridDB"), record.logo)
        assertEquals(Sourced("icon", "SteamGridDB"), record.icon)
    }

    @Test
    fun `a lookup refused along the way is reported with its fix, and a failure by count`() {
        val notes = pcFlavourNotes(
            listOf(ScrapeLookup.Refused("SteamGridDB", 401, "Invalid key format")),
            silenced = setOf("SteamGridDB"),
            failed = 2,
        )
        assertTrue(notes, notes.contains("SteamGridDB refused a lookup (HTTP 401: Invalid key format) and was not asked again."))
        assertTrue(notes, notes.contains("> SteamGridDB > API key"))
        assertTrue(notes, notes.contains("2 lookups for details or art could not connect."))
        assertEquals("", pcFlavourNotes(emptyList(), emptySet(), 0))
    }

    @Test
    fun `a PC summary carries the flavour notes after the counts`() {
        val counts = PcScrapeCounts(targeted = 2).apply {
            attempted = 2
            byName = 2
            flavourNotes = "SteamGridDB refused a lookup (HTTP 401)."
        }
        assertEquals("Lutris: matched 2 of 2. SteamGridDB refused a lookup (HTTP 401).", formatPcScrapeSummary("Lutris", counts))
    }

    // ---- the per-field source record ----------------------------------------

    @Test
    fun `a field the person edited stays theirs whatever a scrape brings`() {
        val had = FieldSources.encode(mapOf("description" to FieldSources.EDITED, "genre" to "Lutris"))
        assertEquals("mine", FieldSources.keep(had, FieldSources.DESCRIPTION, "scraped", "mine"))
        assertEquals("RPG", FieldSources.keep(had, FieldSources.GENRE, "RPG", "Action"))
        assertEquals("Action", FieldSources.keep(had, FieldSources.GENRE, null, "Action"))
        val merged = FieldSources.decode(FieldSources.merge(had, mapOf("description" to "IGDB", "genre" to "IGDB", "cover" to "SteamGridDB")))
        assertEquals(mapOf("description" to FieldSources.EDITED, "genre" to "IGDB", "cover" to "SteamGridDB"), merged)
    }

    @Test
    fun `saving the editor marks exactly the fields that changed as edited`() {
        val before = GameMetadataEntity(id = "g", description = "old", genre = "Action", fieldSources = """{"genre":"IGDB"}""")
        val after = before.copy(description = "new", favorite = true)
        assertEquals(
            mapOf("description" to FieldSources.EDITED, "genre" to "IGDB"),
            FieldSources.decode(FieldSources.afterEdit(before, after)),
        )
    }

    @Test
    fun `sources and links survive a round trip through the row, and junk reads as nothing`() {
        val sources = mapOf("cover" to "SteamGridDB", "description" to "IGDB")
        assertEquals(sources, FieldSources.decode(FieldSources.encode(sources)))
        assertTrue(FieldSources.decode("not json").isEmpty())
        assertNull(FieldSources.encode(emptyMap()))
        val links = listOf(GameLink("Steam", "https://store.steampowered.com/app/1"))
        assertEquals(links, GameLink.decode(GameLink.encode(links)))
        assertTrue(GameLink.decode("[{\"label\": \"x\"}]").isEmpty())
        assertNull(GameLink.encode(emptyList()))
    }
}
