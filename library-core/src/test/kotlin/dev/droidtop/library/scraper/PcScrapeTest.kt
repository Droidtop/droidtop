package dev.droidtop.library.scraper

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The decisions the PC/engine scrape makes before it ever reaches the
 * network: what it searches for, whether a result is certain enough to
 * write without asking, and where the media it writes goes. Those are
 * the parts that can be wrong on real data, and all three are pure.
 *
 * The network paths themselves are not exercised here and cannot be:
 * IGDB needs the user's own credentials, which this project never
 * fabricates, and Lutris needs a live host.
 */
class PcScrapeTest {

    // ---- what gets searched ---------------------------------------------

    @Test
    fun `a version and platform tag are stripped from a real folder name`() {
        // Real download shapes, both from the user's own library.
        assertEquals("Eternum", PcScrapeTitle.clean("Eternum-0.9.5-pc"))
        assertEquals("Some Game", PcScrapeTitle.clean("Some_Game_v1.2_win64"))
    }

    @Test
    fun `an unrecognised release tag is left in rather than guessed at`() {
        // "scrappy" is a real distribution's own build tag and nothing
        // here can know that. It stays, the search then finds no exact
        // title, and the game goes to the picker -- which is the right
        // outcome: a rule invented to strip it would strip real title
        // words too.
        assertEquals("BeingADIK scrappy", PcScrapeTitle.clean("BeingADIK-0.8.3-scrappy"))
    }

    @Test
    fun `a numbered sequel keeps its number`() {
        // The reason a bare number is not treated as a version: eating
        // the digit would search for a different game entirely.
        assertEquals("Half Life 2", PcScrapeTitle.clean("Half-Life-2"))
        assertEquals("Persona 5", PcScrapeTitle.clean("Persona 5"))
    }

    @Test
    fun `a bracketed tag is dropped wherever it sits`() {
        assertEquals("Night Road", PcScrapeTitle.clean("Night Road [1.0] (Final)"))
    }

    @Test
    fun `a title that is only a version keeps its name rather than becoming nothing`() {
        // Never return an empty query: an empty search matches
        // everything, which is the worst possible input to a matcher.
        assertEquals("2064", PcScrapeTitle.clean("2064"))
        assertEquals("v1.0", PcScrapeTitle.clean("v1.0"))
    }

    @Test
    fun `a leading version-shaped word is part of the title and stays`() {
        // Stripping from the end only: "V2" here is the game, not a tag.
        assertEquals("V2 Berlin", PcScrapeTitle.clean("V2 Berlin"))
    }

    @Test
    fun `a title containing a platform word is not truncated mid-title`() {
        assertEquals("Winter Voices", PcScrapeTitle.clean("Winter Voices"))
    }

    // ---- what gets applied without asking --------------------------------

    private fun match(name: String) = PcMatch(name = name, sourceLabel = "test")

    @Test
    fun `one exact title match is applied automatically`() {
        val decision = PcMatching.decide("Eternum", listOf(match("Eternum"), match("Eternum Chronicles")))
        assertEquals(match("Eternum"), (decision as PcMatching.Decision.Confident).match)
    }

    @Test
    fun `punctuation, case and a leading article do not stop an exact match`() {
        val decision = PcMatching.decide("the-witchs-house", listOf(match("The Witch's House")))
        assertTrue(decision is PcMatching.Decision.Confident)
    }

    @Test
    fun `a close but inexact result is never applied silently`() {
        // The whole point: a wrong write over a user's own metadata is
        // worse than no write at all, so this goes to the picker.
        val decision = PcMatching.decide("Eternum", listOf(match("Eternum Evolved")))
        assertEquals(listOf(match("Eternum Evolved")), (decision as PcMatching.Decision.Ambiguous).matches)
    }

    @Test
    fun `two results with the same title are ambiguous, not a coin toss`() {
        val decision = PcMatching.decide("Prey", listOf(match("Prey"), match("Prey")))
        assertTrue(decision is PcMatching.Decision.Ambiguous)
    }

    @Test
    fun `no results is reported as no match rather than an empty pick list`() {
        assertEquals(PcMatching.Decision.None, PcMatching.decide("Eternum", emptyList()))
    }

    @Test
    fun `a title that normalizes to nothing is never treated as certain`() {
        // "..." normalizes to an empty string, which would otherwise
        // compare equal to any equally punctuation-only candidate name.
        assertTrue(PcMatching.decide("...", listOf(match("!!!"))) is PcMatching.Decision.Ambiguous)
    }

    // ---- where media lands -----------------------------------------------

    @Test
    fun `covers go into ES-DE's own downloaded_media layout`() {
        val root = File("/storage/games")
        assertEquals(
            File("/storage/games/downloaded_media/renpy/covers/Eternum-0.9.5-pc.png"),
            PcMediaLayout.coverFile(root, "renpy", "Eternum-0.9.5-pc"),
        )
    }

    @Test
    fun `an engine game files its media under its own engine folder`() {
        val entry = LibraryEntry(id = "/storage/games/Eternum", title = "Eternum", kind = LibraryEntryKind.RENPY)
        assertEquals("renpy", PcMediaLayout.systemFolderFor(entry))
        assertTrue(entry.isPcOrEngineGame)
    }

    @Test
    fun `a PC entry files its media under ES-DE's own pc system`() {
        val entry = LibraryEntry(id = "shortcut", title = "Some Game", kind = LibraryEntryKind.WINE_PROFILE)
        assertEquals("pc", PcMediaLayout.systemFolderFor(entry))
        assertTrue(entry.isPcOrEngineGame)
    }

    @Test
    fun `a console ROM keeps its own system and is not a PC scrape target`() {
        val entry = LibraryEntry(
            id = "/storage/games/gba/Game.gba",
            title = "Game",
            kind = LibraryEntryKind.CONSOLE_ROM,
            systemId = "gba",
        )
        assertEquals("gba", PcMediaLayout.systemFolderFor(entry))
        assertTrue(!entry.isPcOrEngineGame)
    }

    @Test
    fun `a plain Android app is neither scrapable nor filed anywhere`() {
        val entry = LibraryEntry(id = "com.example", title = "App", kind = LibraryEntryKind.NATIVE_ANDROID_APP)
        assertNull(PcMediaLayout.systemFolderFor(entry))
        assertTrue(!entry.isPcOrEngineGame)
    }

    // ---- the IGDB response shape -----------------------------------------

    @Test
    fun `an IGDB row is mapped onto ES-DE's own metadata conventions`() {
        // The response SHAPE, transcribed from IGDB's own documented
        // field set -- no credentials are involved in parsing one, and
        // none are used anywhere in these tests.
        val row = JSONObject(
            """
            {
              "name": "Example Title",
              "summary": "A description.",
              "first_release_date": 1000000000,
              "rating": 80.0,
              "cover": {"url": "//images.igdb.com/igdb/image/upload/t_thumb/abc.jpg"},
              "genres": [{"name": "Adventure"}, {"name": "Puzzle"}],
              "involved_companies": [
                {"company": {"name": "Dev Co"}, "developer": true, "publisher": false},
                {"company": {"name": "Pub Co"}, "developer": false, "publisher": true}
              ]
            }
            """.trimIndent(),
        )

        val parsed = IgdbScraperClient.parse(row)!!

        assertEquals("Example Title", parsed.name)
        assertEquals("A description.", parsed.description)
        assertEquals("Dev Co", parsed.developer)
        assertEquals("Pub Co", parsed.publisher)
        assertEquals("Adventure, Puzzle", parsed.genre)
        // ES-DE's MD_DATE is "YYYYMMDDT000000", never a raw epoch.
        assertEquals("20010909T000000", parsed.releaseDate)
        // ES-DE's MD_RATING is 0.0-1.0, not IGDB's 0-100.
        assertEquals(0.8f, parsed.rating!!, 0.001f)
        // The cover is upgraded from the thumbnail size token and given
        // a scheme, since IGDB returns protocol-relative URLs.
        assertEquals("https://images.igdb.com/igdb/image/upload/t_cover_big/abc.jpg", parsed.coverUrl)
    }

    @Test
    fun `an IGDB row with only a name yields no invented fields`() {
        val parsed = IgdbScraperClient.parse(JSONObject("""{"name": "Bare Title"}"""))!!
        assertEquals("Bare Title", parsed.name)
        assertNull(parsed.description)
        assertNull(parsed.developer)
        assertNull(parsed.releaseDate)
        assertNull(parsed.rating)
        assertNull(parsed.coverUrl)
    }

    @Test
    fun `a nameless IGDB row is dropped rather than shown as a blank candidate`() {
        assertNull(IgdbScraperClient.parse(JSONObject("""{"summary": "no name here"}""")))
    }
}
