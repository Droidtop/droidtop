package dev.droidtop.pluginhost

import dev.droidtop.pluginhost.TestPlugins.arr
import dev.droidtop.pluginhost.TestPlugins.obj
import dev.droidtop.pluginhost.TestPlugins.strings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionProtocolsTest {
    private fun entry(vararg kv: Pair<String, Any>) = ProvidedPoint(point = "ui.context_action", extra = obj(*kv).toString())

    private val game = ContextTarget("game", "/roms/snes/zelda.sfc", "Zelda", systemId = "snes")

    @Test
    fun `a context action with no filter is for games only`() {
        assertTrue(ContextActionFilter.matches(entry(), game))
        assertFalse(ContextActionFilter.matches(entry(), ContextTarget("app", "com.x", "X", packageName = "com.x")))
    }

    @Test
    fun `an entry's targets default to games, as the filter reads them`() {
        assertEquals(listOf("game"), ContextActionFilter.targets(entry()))
        assertEquals(listOf("game", "app"), ContextActionFilter.targets(entry("targets" to strings("game", "app"))))
        assertEquals(listOf("game"), ContextActionFilter.targets(ProvidedPoint(point = "ui.context_action", extra = "not json")))
    }

    @Test
    fun `the static filter decides on kind, systems and packages`() {
        assertTrue(ContextActionFilter.matches(entry("targets" to strings("game", "app")), ContextTarget("app", "com.x", "X", packageName = "com.x")))
        assertTrue(ContextActionFilter.matches(entry("systems" to strings("snes", "nes")), game))
        assertFalse(ContextActionFilter.matches(entry("systems" to strings("psx")), game))
        assertFalse("a filter on systems needs the target to have one", ContextActionFilter.matches(entry("systems" to strings("snes")), game.copy(systemId = null)))
        val app = ContextTarget("app", "com.retro.arch", "RetroArch", packageName = "com.retro.arch")
        assertTrue(ContextActionFilter.matches(entry("targets" to strings("app"), "packages" to strings("com.retro.arch")), app))
        assertFalse(ContextActionFilter.matches(entry("targets" to strings("app"), "packages" to strings("com.other")), app))
    }

    @Test
    fun `a game's identity is handed over only with library read`() {
        val withIdentity = ContextActionFilter.targetJson(game, includeIdentity = true)
        assertEquals("/roms/snes/zelda.sfc", withIdentity.getString("id"))
        assertEquals("snes", withIdentity.getString("systemId"))
        val bare = ContextActionFilter.targetJson(game, includeIdentity = false)
        assertEquals("game", bare.getString("kind"))
        assertFalse(bare.has("id"))
        assertFalse(bare.has("title"))
    }

    @Test
    fun `an action can ask to run as a job`() {
        assertTrue(ContextActionFilter.runsAsJob(entry("job" to true)))
        assertFalse(ContextActionFilter.runsAsJob(entry()))
    }

    @Test
    fun `the most confident candidate at or above the floor wins`() {
        val data = obj(
            "candidates" to arr(
                obj("ids" to obj("igdb" to 1), "confidence" to 0.6),
                obj("ids" to obj("igdb" to 2), "confidence" to 0.9),
                obj("ids" to obj("igdb" to 3), "confidence" to 0.3),
            ),
        )
        assertEquals(2, PluginMetadataProtocol.bestCandidateIds(data)!!.getInt("igdb"))
    }

    @Test
    fun `no candidate above the floor is no match`() {
        assertNull(PluginMetadataProtocol.bestCandidateIds(obj("candidates" to arr(obj("ids" to obj("a" to 1), "confidence" to 0.2)))))
        assertNull(PluginMetadataProtocol.bestCandidateIds(obj()))
        assertNull(PluginMetadataProtocol.bestCandidateIds(obj("candidates" to arr(obj("confidence" to 0.9)))))
    }

    @Test
    fun `a tie at the top is not guessed between, and the person picks`() {
        val data = obj(
            "candidates" to arr(
                obj("ids" to obj("thread" to "1"), "confidence" to 0.6, "title" to "Eternum"),
                obj("ids" to obj("thread" to "2"), "confidence" to 0.6, "title" to "Eternum"),
                obj("ids" to obj("thread" to "3"), "confidence" to 0.5),
            ),
        )
        assertNull(PluginMetadataProtocol.bestCandidateIds(data))
        val decision = PluginMetadataProtocol.decide(data) as PluginMetadataProtocol.MatchDecision.Ambiguous
        assertEquals(listOf("1", "2"), decision.candidates.map { it.ids.getString("thread") })
        assertEquals("Eternum", decision.candidates.first().title)
        // The same ids twice are one candidate, not a tie.
        val twice = obj("candidates" to arr(obj("ids" to obj("thread" to "1"), "confidence" to 0.9), obj("ids" to obj("thread" to "1"), "confidence" to 0.9)))
        assertTrue(PluginMetadataProtocol.decide(twice) is PluginMetadataProtocol.MatchDecision.Match)
        assertEquals(PluginMetadataProtocol.MatchDecision.None, PluginMetadataProtocol.decide(obj()))
    }

    @Test
    fun `a fetch links the game only under the plugin's own source keys`() {
        val data = obj("sourceLinks" to obj("f95zone" to " 93340 ", "steam" to "570", "blank" to " "))
        assertEquals(mapOf("f95zone" to "93340"), PluginMetadataProtocol.sourceLinks(data, setOf("f95zone", "blank")))
        assertEquals(emptyMap<String, String>(), PluginMetadataProtocol.sourceLinks(obj(), setOf("f95zone")))
    }

    @Test
    fun `a candidate with no confidence counts as the floor`() {
        assertNotNull(PluginMetadataProtocol.bestCandidateIds(obj("candidates" to arr(obj("ids" to obj("a" to 1))))))
    }

    @Test
    fun `fields are read, trimmed, and the rating is clamped to 0 to 1`() {
        val fields = PluginMetadataProtocol.fields(
            "Acme Metadata",
            obj("description" to "  A hero.  ", "developer" to "Nintendo", "genre" to "Adventure", "players" to "1", "rating" to 1.7, "releaseDate" to "1991-11-21"),
        )!!
        assertEquals("Acme Metadata", fields.source)
        assertEquals("A hero.", fields.description)
        assertEquals("Nintendo", fields.developer)
        assertNull(fields.publisher)
        assertEquals(1.0f, fields.rating!!, 0.0001f)
        assertEquals("19911121T000000", fields.releaseDate)
    }

    @Test
    fun `a reply with no usable field is no result`() {
        assertNull(PluginMetadataProtocol.fields("x", obj()))
        assertNull(PluginMetadataProtocol.fields("x", obj("description" to "   ", "releaseDate" to "sometime")))
        assertNull(PluginMetadataProtocol.fields("x", JSONObject("""{"description":null}""")))
    }

    @Test
    fun `release dates are normalised to the form ES-DE stores`() {
        assertEquals("19911121T000000", PluginMetadataProtocol.normalizeReleaseDate("19911121T000000"))
        assertEquals("19911121T000000", PluginMetadataProtocol.normalizeReleaseDate("1991-11-21"))
        assertEquals("19911101T000000", PluginMetadataProtocol.normalizeReleaseDate("1991-11"))
        assertEquals("19910101T000000", PluginMetadataProtocol.normalizeReleaseDate("1991"))
        assertNull(PluginMetadataProtocol.normalizeReleaseDate("Nov 1991"))
        assertNull(PluginMetadataProtocol.normalizeReleaseDate(null))
    }

    @Test
    fun `a tile shows on the surfaces it lists, or on all when it lists none`() {
        val any = ProvidedPoint(point = "ui.quick_tile")
        val gaming = ProvidedPoint(point = "ui.quick_tile", extra = obj("surfaces" to strings("gaming.quick_menu")).toString())
        val desktop = ProvidedPoint(point = "ui.quick_tile", extra = obj("surfaces" to strings("desktop.tray")).toString())
        assertTrue(PluginTileProtocol.onSurface(any, "gaming.quick_menu"))
        assertTrue(PluginTileProtocol.onSurface(gaming, "gaming.quick_menu"))
        assertFalse(PluginTileProtocol.onSurface(desktop, "gaming.quick_menu"))
    }

    @Test
    fun `a tile state has a label, a value and an optional toggle`() {
        val state = PluginTileProtocol.state(obj("label" to "VPN", "value" to "Connected", "on" to true, "severity" to "ok"), "Fallback")
        assertEquals(TileState("VPN", "Connected", true, "ok"), state)
        assertEquals("toggle", PluginTileProtocol.pressOp(state))
        val readOnly = PluginTileProtocol.state(obj("value" to "12 GB free"), "Storage")
        assertEquals("Storage", readOnly.label)
        assertNull(readOnly.on)
        assertEquals("action", PluginTileProtocol.pressOp(readOnly))
        assertEquals("action", PluginTileProtocol.pressOp(null))
        assertEquals(false, PluginTileProtocol.state(obj("on" to "off"), "x").on)
    }

    @Test
    fun `a tile's label and value are cut to the view limits, never grown`() {
        // Droidtop/tracker#644: a tile is a host row of fixed height; a long answer ellipsizes.
        val long = PluginTileProtocol.state(obj("label" to "A".repeat(50), "value" to "Connected to the office network"), "x")
        assertEquals(PluginViewLimits.length("tileLabel"), long.label.length)
        assertEquals(PluginViewLimits.length("tileValue"), long.value!!.length)
        assertTrue(long.value!!.endsWith("\u2026"))
    }

    @Test
    fun `a shelf keeps only real entries, once each, in the plugin's order`() {
        val known = setOf("a", "b", "c")
        val shelves = PluginShelfProtocol.shelves(
            obj("shelves" to arr(obj("id" to "short", "title" to "  Short games  ", "entries" to strings("c", "x", "a", "c")))),
            known,
        )
        assertEquals(listOf(PluginShelf("short", "Short games", listOf("c", "a"))), shelves)
    }

    @Test
    fun `shelves are capped and empty or untitled ones are dropped`() {
        val known = (1..100).map { "e$it" }.toSet()
        val many = strings(*(1..100).map { "e$it" }.toTypedArray())
        val data = obj(
            "shelves" to arr(
                obj("id" to "none", "title" to "Nothing real", "entries" to strings("zzz")),
                obj("id" to "untitled", "entries" to many),
                obj("id" to "one", "title" to "One", "entries" to many),
                obj("id" to "one", "title" to "Same id again", "entries" to many),
                obj("id" to "two", "title" to "T".repeat(200), "entries" to many),
                obj("id" to "three", "title" to "Three", "entries" to many),
            ),
        )
        val shelves = PluginShelfProtocol.shelves(data, known)
        assertEquals(listOf("one", "two"), shelves.map { it.id })
        assertEquals(PluginShelfProtocol.MAX_ITEMS, shelves[0].entryIds.size)
        assertEquals(PluginShelfProtocol.MAX_TITLE, shelves[1].title.length)
        assertTrue(PluginShelfProtocol.shelves(obj(), known).isEmpty())
    }

    @Test
    fun `the library is described only with library read, and play times only with history`() {
        val candidates = listOf(ShelfCandidate("a", "Alpha", "console_rom", "snes", favorite = true, lastPlayedEpochMs = 42L))
        assertNull(PluginShelfProtocol.libraryContext(candidates, libraryRead = false, history = true))
        val plain = PluginShelfProtocol.libraryContext(candidates, libraryRead = true, history = false)!!.getJSONObject(0)
        assertEquals("Alpha", plain.getString("title"))
        assertEquals("snes", plain.getString("systemId"))
        assertFalse(plain.has("lastPlayed"))
        val withHistory = PluginShelfProtocol.libraryContext(candidates, libraryRead = true, history = true)!!.getJSONObject(0)
        assertEquals(42L, withHistory.getLong("lastPlayed"))
        val many = (1..600).map { ShelfCandidate("e$it", "E$it", "app", null, false, null) }
        assertEquals(PluginShelfProtocol.MAX_CANDIDATES, PluginShelfProtocol.libraryContext(many, true, false)!!.length())
    }

    @Test
    fun `a game section names its tab, and anything else is Extras`() {
        fun section(vararg kv: Pair<String, Any>) = ProvidedPoint(point = GameSectionProtocol.POINT, extra = obj(*kv).toString())
        assertEquals("versions", GameSectionProtocol.tab(section("tab" to "versions")))
        assertEquals("extras", GameSectionProtocol.tab(section("tab" to "somewhere")))
        assertEquals("extras", GameSectionProtocol.tab(section()))
        assertTrue("a section uses the context action filter", ContextActionFilter.matches(section("systems" to strings("snes")), game))
    }

    @Test
    fun `a candidate's title that is only its id is no name (tracker 784)`() {
        val ids = JSONObject().put("thread", "214581")
        assertTrue(PluginMetadataProtocol.isIdOnly("Thread 214581", ids))
        assertTrue(PluginMetadataProtocol.isIdOnly("214581", ids))
        assertTrue(PluginMetadataProtocol.isIdOnly("thread #214581", ids))
        assertFalse(PluginMetadataProtocol.isIdOnly("Thief of Hearts", ids))
        assertFalse("a name with a number in it is a name", PluginMetadataProtocol.isIdOnly("Thief of Hearts 214581 Edition", ids))
        assertFalse(PluginMetadataProtocol.isIdOnly(null, ids))
    }

    @Test
    fun `fetch's title is the record's name, one line`() {
        assertEquals("Thief of Hearts", PluginMetadataProtocol.title(JSONObject().put("title", "  Thief of Hearts ")))
        assertNull(PluginMetadataProtocol.title(JSONObject().put("title", "two\nlines")))
        assertNull(PluginMetadataProtocol.title(JSONObject()))
    }
}
