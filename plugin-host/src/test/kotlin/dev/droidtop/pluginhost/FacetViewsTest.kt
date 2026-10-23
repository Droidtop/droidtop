package dev.droidtop.pluginhost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Facets drawn by the one renderer (docs/plugin-api.md 1.10, "One mechanism"). */
class FacetViewsTest {
    private val romgi = """{"facets": [
        {"id": "index", "kind": "status", "targets": ["quickmenu"], "label": "Index", "state": "ok", "value": "Updated 2 days ago"},
        {"id": "update", "kind": "action", "targets": ["quickmenu"], "label": "Update index", "call": {"op": "updateIndex", "kind": "job"}},
        {"id": "open", "kind": "action", "targets": ["quickmenu"], "label": "Open romgi", "call": {"op": "openMain"}},
        {"id": "clear", "kind": "action", "targets": ["quickmenu"], "label": "Clear cache", "sub": "The index is fetched again", "confirm": "danger", "call": {"op": "clearCache"}},
        {"id": "storage", "kind": "group", "title": "Storage", "facets": [
            {"id": "used", "kind": "meter", "label": "Cache", "value": 120, "max": 512, "unit": "MB"},
            {"id": "auto", "kind": "toggle", "label": "Update every day", "value": true, "call": {"op": "setAuto"}}
        ]}
    ]}"""

    @Test
    fun `facets become sections and nodes the renderer already draws`() {
        val view = FacetViews.toView(Facets.read(JSONObject(romgi)).facets)
        assertEquals(listOf("facets", "storage"), view.sections.map { it.id })
        val main = view.sections[0].nodes
        assertEquals(ViewNode.Info("index", "Index", "Updated 2 days ago", "OK"), main[0])
        val update = main[1] as ViewNode.Button
        assertEquals(ViewAction.Kind.JOB, update.action.kind)
        assertEquals("updateIndex", update.action.op)
        assertNull("no confirm unless asked", update.confirm)
        assertEquals("Clear cache", (main[3] as ViewNode.Button).confirm)
        val storage = view.sections[1]
        assertEquals("Storage", storage.title)
        assertEquals("120 of 512 MB", (storage.nodes[0] as ViewNode.Info).value)
        assertTrue((storage.nodes[1] as ViewNode.Toggle).value)
    }

    @Test
    fun `a reply of facets reads as a view, as a page call's reply does`() {
        val view = PluginView.parse(JSONObject(romgi))
        assertEquals(4, view!!.sections[0].nodes.size)
        assertNull("a view without facets or sections is not a page", PluginView.parse(JSONObject("""{"title": "x"}""")))
    }

    @Test
    fun `a facet input's value goes back as args value, typed`() {
        val toggle = Facets.read(JSONObject(romgi)).facets.last().let { (it.body as FacetBody.Group).facets[1] }
        val node = FacetViews.node(toggle) as ViewNode.Toggle
        val args = PluginViewCall.args(node.action!!.args(), mapOf("auto" to "false"), JSONObject().put("surface", "gaming.quick_menu"))
        assertEquals(false, args.get("value"))
        assertFalse("the host's mark never reaches the plugin", args.has(FacetViews.MARK))
        assertEquals("gaming.quick_menu", args.getJSONObject("context").getString("surface"))
    }

    @Test
    fun `a list's entries are rows and a page call opens a page`() {
        val view = FacetViews.toView(Facets.read(JSONObject("""{"facets": [
            {"id": "games", "kind": "list", "entries": [
                {"id": "a", "title": "Game A", "sub": "GBA", "badges": ["New"], "call": {"op": "show", "kind": "page", "args": {"id": "a"}}},
                {"id": "b", "title": "Game B"}
            ]}
        ]}""")).facets)
        val rows = view.sections.single().nodes.map { it as ViewNode.Row }
        assertEquals(listOf("Game A", "Game B"), rows.map { it.title })
        assertEquals(ViewAction.Kind.VIEW, rows[0].action!!.kind)
        assertEquals("a", rows[0].action!!.args().getString("id"))
    }
}
