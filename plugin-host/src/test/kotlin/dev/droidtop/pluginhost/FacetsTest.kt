package dev.droidtop.pluginhost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Facets, the model and its guards (docs/plugin-api.md 1.10): reading a reply and what a target takes from it. */
class FacetsTest {
    private fun read(json: String, subject: FacetSubject = FacetSubject.Plugin) = Facets.read(JSONObject(json), subject)

    private val vpn = """{"facets": [
        {"id": "vpn.state", "kind": "status", "v": 1, "targets": ["quickmenu", "status.area"], "label": "VPN", "state": "on", "tone": "positive", "value": "Connected to Home"},
        {"id": "vpn.switch", "kind": "toggle", "v": 1, "targets": ["quickmenu"], "label": "Use the VPN", "value": true, "call": {"op": "setVpn"}},
        {"id": "vpn.speed", "kind": "chart", "v": 1, "targets": ["companion.card"], "label": "Download speed", "points": [1.2, 3.4, 2.9, 4.0], "unit": "MB/s"}
    ]}"""

    private val plugin = FacetGuards.Plugin(
        id = "vpn",
        declaredTargets = setOf("quickmenu", "status.area", "companion.card", "game.page", "item.page", "widget"),
        domains = listOf("example.com", "*.cdn.example.com"),
        storeOrSource = true,
    )

    @Test
    fun `a reply's facets are read with their envelope and kinds`() {
        val reading = read(vpn)
        assertEquals(listOf("vpn.state", "vpn.switch", "vpn.speed"), reading.facets.map { it.id })
        val state = reading.facets[0]
        assertEquals(FacetSubject.Plugin, state.subject)
        assertEquals(listOf("quickmenu", "status.area"), state.targets)
        assertEquals(FacetBody.Status("VPN", "on", "positive", "Connected to Home"), state.body)
        val toggle = reading.facets[1].body as FacetBody.Toggle
        assertTrue(toggle.value)
        assertEquals(FacetCall("setVpn"), toggle.call)
        assertEquals(listOf(1.2, 3.4, 2.9, 4.0), (reading.facets[2].body as FacetBody.Chart).points)
        assertTrue(reading.messages.isEmpty())
    }

    @Test
    fun `an unknown kind or version, a facet without an id and a missing call are dropped and said`() {
        val reading = read("""{"facets": [
            {"id": "a", "kind": "hologram", "label": "x"},
            {"id": "b", "kind": "status", "v": 2, "label": "x", "state": "on"},
            {"kind": "status", "label": "x", "state": "on"},
            {"id": "c", "kind": "action", "label": "Go"},
            {"id": "d", "kind": "badge", "text": "Update"}
        ]}""")
        assertEquals(listOf("d"), reading.facets.map { it.id })
        assertEquals(4, reading.messages.size)
        assertTrue(reading.messages[0].contains("hologram"))
    }

    @Test
    fun `texts over their limit are cut and a state droidtop does not know is unknown`() {
        val long = "x".repeat(PluginViewLimits.facetLength("label") + 10)
        val reading = read("""{"facets": [{"id": "s", "kind": "status", "label": "$long", "state": "glowing"}]}""")
        val status = reading.facets.single().body as FacetBody.Status
        assertEquals(PluginViewLimits.facetLength("label"), status.label.length)
        assertEquals("unknown", status.state)
        assertEquals(2, reading.messages.size)
    }

    @Test
    fun `a group is one level deep and a secret input is never read`() {
        val reading = read("""{"facets": [
            {"id": "g", "kind": "group", "title": "Index", "facets": [
                {"id": "age", "kind": "info", "label": "Updated", "value": "2 days ago"},
                {"id": "inner", "kind": "group", "facets": [{"id": "x", "kind": "badge", "text": "x"}]}
            ]},
            {"id": "pw", "kind": "input", "label": "Password", "secret": true, "call": {"op": "set"}}
        ]}""")
        val group = reading.facets.single().body as FacetBody.Group
        assertEquals(listOf("age"), group.facets.map { it.id })
    }

    @Test
    fun `a template is used by filling in its fields, and the use's own envelope wins`() {
        val reading = read("""{"facets": [
            {"kind": "template", "name": "count", "facets": [{"id": "t", "kind": "badge", "text": {"$": "n"}, "tone": "positive"}]},
            {"kind": "use", "template": "count", "id": "unread", "targets": ["game.row"], "fields": {"n": "3 new"}},
            {"kind": "use", "template": "missing", "id": "nope", "fields": {}}
        ]}""")
        val facet = reading.facets.single()
        assertEquals("unread", facet.id)
        assertEquals(listOf("game.row"), facet.targets)
        assertEquals(FacetBody.Badge("3 new", "positive"), facet.body)
        assertTrue(reading.messages.single().contains("missing"))
    }

    @Test
    fun `a target takes only what it was asked for, of its kinds and subjects, by priority`() {
        val reading = read("""{"facets": [
            {"id": "low", "kind": "action", "targets": ["quickmenu"], "priority": "low", "label": "Clear", "call": {"op": "clear"}},
            {"id": "text", "kind": "text", "targets": ["quickmenu"], "body": "not here"},
            {"id": "other", "kind": "status", "targets": ["status.area"], "label": "x", "state": "on"},
            {"id": "high", "kind": "action", "targets": ["quickmenu"], "priority": "high", "label": "Update", "call": {"op": "update"}},
            {"id": "game", "kind": "status", "subject": {"type": "item", "source": "s", "id": "1"}, "targets": ["quickmenu"], "label": "x", "state": "on"}
        ]}""")
        val messages = mutableListOf<String>()
        val shown = FacetGuards.admit(reading.facets, "quickmenu", plugin, messages)
        assertEquals(listOf("high", "low"), shown.map { it.id })
        assertEquals(2, messages.size)
    }

    @Test
    fun `a plugin gets nothing on a target it did not declare, and a cap holds per target`() {
        val facets = read("""{"facets": [${(1..6).joinToString(",") { """{"id": "a$it", "kind": "action", "targets": ["quickmenu", "home.card"], "label": "A$it", "call": {"op": "a"}}""" }}]}""").facets
        assertTrue(FacetGuards.admit(facets, "home.card", plugin).isEmpty())
        assertEquals(PluginViewLimits.facetsPerTarget("quickmenu"), FacetGuards.admit(facets, "quickmenu", plugin).size)
    }

    @Test
    fun `a group keeps only what its target draws`() {
        val facets = read("""{"facets": [{"id": "g", "kind": "group", "targets": ["quickmenu"], "facets": [
            {"id": "ok", "kind": "status", "label": "Index", "state": "ok"},
            {"id": "pic", "kind": "media", "role": "cover", "url": "https://example.com/a.png", "alt": "a"}
        ]}]}""").facets
        val group = FacetGuards.admit(facets, "quickmenu", plugin).single().body as FacetBody.Group
        assertEquals(listOf("ok"), group.facets.map { it.id })
    }

    @Test
    fun `pictures come only from the plugin's own sites`() {
        val facets = read("""{"facets": [
            {"id": "a", "kind": "image", "subject": {"type": "item", "source": "s", "id": "1"}, "targets": ["item.page"], "role": "cover", "url": "https://img.cdn.example.com/a.png", "alt": "A"},
            {"id": "b", "kind": "image", "subject": {"type": "item", "source": "s", "id": "1"}, "targets": ["item.page"], "role": "cover", "url": "https://elsewhere.test/b.png", "alt": "B"},
            {"id": "c", "kind": "image", "subject": {"type": "item", "source": "s", "id": "1"}, "targets": ["item.page"], "role": "cover", "url": "http://example.com/c.png", "alt": "C"}
        ]}""").facets
        assertEquals(listOf("a"), FacetGuards.admit(facets, "item.page", plugin).map { it.id })
    }

    @Test
    fun `store information only from a store or source plugin, on its own site, and cut to what it may claim`() {
        val store = read("""{"facets": [{"id": "st", "kind": "store", "subject": {"type": "item", "source": "s", "id": "1"}, "targets": ["item.page"],
            "store": "Example", "url": "https://example.com/g/1", "price": "4.99", "currency": "USD", "onSale": true, "owned": true}]}""").facets
        val shown = FacetGuards.admit(store, "item.page", plugin).single().body as FacetBody.Store
        assertEquals("4.99", shown.price)
        assertTrue(shown.onSale)
        assertNull("owned only from a signed-in store plugin", shown.owned)
        assertTrue(FacetGuards.admit(store, "item.page", plugin.copy(storeOrSource = false)).isEmpty())
        assertTrue(FacetGuards.admit(store, "item.page", plugin.copy(domains = listOf("other.test"))).isEmpty())

        val free = FacetGuards.cleanStore(FacetBody.Store("x", "https://example.com", "1.00", "USD", null, free = true, onSale = true), plugin)
        assertNull(free.price)
        assertFalse(free.onSale)
        val huge = FacetGuards.cleanStore(FacetBody.Store("x", "https://example.com", "2000000", "USD", null, free = false, onSale = false), plugin)
        assertNull(huge.price)
        assertEquals(true, FacetGuards.cleanStore(FacetBody.Store("x", "https://example.com", null, null, true, false, false), plugin.copy(signedInStore = true)).owned)
    }

    @Test
    fun `the F95 record reads as bundles`() {
        val reading = read("""{"facets": [
            {"id": "meta", "kind": "metadata", "v": 1, "title": "Eternum", "version": "0.9.5 Public", "developer": "Caribdis", "engine": "renpy", "status": "ongoing", "rating": 0.96, "votes": 310, "tags": ["3dcg", "male protagonist"], "updated": 1700000000000},
            {"id": "about", "kind": "description", "v": 1, "overview": "You are moving to Kredon.", "changelog": "v0.9.5: ..."},
            {"id": "dl", "kind": "download", "v": 1, "entries": [
                {"section": "Win/Linux", "host": "DATANODES", "label": "DATANODES", "url": "https://datanodes.to/f/2", "kind": "page"},
                {"section": "Win/Linux", "host": "AKIRABOX", "label": "AKIRABOX", "link": "0:AKIRABOX"},
                {"section": "Win/Linux", "label": "nothing to open"}]},
            {"id": "links", "kind": "links", "v": 1, "entries": [{"kind": "source", "label": "F95zone thread", "url": "https://f95zone.to/threads/93340/"}]}
        ]}""", FacetSubject.Item("f95zone", "93340"))
        val meta = reading.facets[0].body as FacetBody.Metadata
        assertEquals("0.9.5 Public", meta.version)
        assertEquals(0.96, meta.rating!!, 0.0)
        assertEquals(listOf("3dcg", "male protagonist"), meta.tags)
        assertEquals(2, (reading.facets[2].body as FacetBody.Download).entries.size)
        assertEquals(FacetSubject.Item("f95zone", "93340"), reading.facets[0].subject)
        assertTrue(reading.facets.all { it.isBundle })
    }

    @Test
    fun `the targets a plugin declares are read from its ui_facets entries`() {
        val provides = listOf(
            ProvidedPoint(point = "ui.facets", extra = """{"targets": ["quickmenu", "game.page", "romgi:addons", "nowhere"]}"""),
            ProvidedPoint(point = "ui.settings"),
        )
        assertEquals(setOf("quickmenu", "game.page", "romgi:addons"), FacetGuards.declaredTargets(provides))
    }

    @Test
    fun `host info says which kinds and targets this droidtop draws`() {
        val info = PluginCall.hostInfo().getJSONObject("facets")
        assertEquals(1, info.getJSONObject("kinds").getJSONArray("status").getInt(0))
        assertTrue(info.getJSONObject("targets").has("quickmenu"))
        assertTrue(info.getJSONObject("targets").has("game.page"))
    }
}
