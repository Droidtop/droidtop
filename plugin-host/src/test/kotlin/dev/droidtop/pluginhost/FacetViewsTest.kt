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
        // A status keeps its state, for the host's glyph (Droidtop/tracker#644).
        assertEquals(ViewNode.Info("index", "Index", "Updated 2 days ago", "OK", state = "ok"), main[0])
        val update = main[1] as ViewNode.Button
        assertEquals(ViewAction.Kind.JOB, update.action.kind)
        assertEquals("updateIndex", update.action.op)
        assertNull("no confirm unless asked", update.confirm)
        assertEquals("Clear cache", (main[3] as ViewNode.Button).confirm)
        val storage = view.sections[1]
        assertEquals("Storage", storage.title)
        // A meter is the host's bar with its figures under it, not words alone.
        val meter = storage.nodes[0] as ViewNode.Progress
        assertEquals("120 of 512 MB", meter.subtitle)
        assertEquals(23, meter.percent)
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

    private val addDevice = """{"facets": [
        {"id": "add", "kind": "group", "title": "Add a device", "form": true,
         "submit": {"label": "Add", "call": {"op": "addDevice", "args": {"folder": "music"}}},
         "facets": [
            {"id": "deviceId", "kind": "input", "label": "Device ID"},
            {"id": "name", "kind": "input", "label": "Name", "optional": true},
            {"id": "trusted", "kind": "toggle", "label": "Trust it", "value": false, "call": {"op": "ignored"}},
            {"id": "port", "kind": "input", "label": "Port", "value": "22000", "optional": true}
        ]},
        {"id": "other", "kind": "toggle", "label": "Elsewhere", "value": true, "call": {"op": "setOther"}}
    ]}"""

    @Test
    fun `a form's inputs send nothing and its submit sends them all, typed`() {
        val reading = Facets.read(JSONObject(addDevice))
        val group = reading.facets.first().body as FacetBody.Group
        assertTrue(group.isForm)
        // Inside a form an input needs no call, and one it gives is not used.
        assertTrue(group.facets.all { f -> (f.body as? FacetBody.Toggle)?.call == null && (f.body as? FacetBody.Input)?.call == null })

        val view = FacetViews.toView(reading.facets)
        val form = view.sections.single { it.id == "add" }.nodes
        assertNull((form[2] as ViewNode.Toggle).action)
        val submit = form.last() as ViewNode.Button
        assertEquals("add.submit", submit.id)
        assertEquals("addDevice", submit.action.op)

        val values = view.initialValues() + mapOf("deviceId" to "ABC-123", "trusted" to "true")
        val args = PluginViewCall.args(submit.action.args(), values, JSONObject())
        assertFalse(args.has(FacetViews.FORM))
        assertEquals("music", args.getString("folder"))
        val sent = args.getJSONObject("values")
        // Only the form's own fields, typed: an input outside the form is not part of it.
        assertEquals(setOf("deviceId", "name", "trusted", "port"), sent.keys().asSequence().toSet())
        assertEquals("ABC-123", sent.getString("deviceId"))
        assertEquals(true, sent.get("trusted"))
        assertEquals("22000", sent.get("port"))
    }

    @Test
    fun `a form is not sent until what it needs is filled in`() {
        val view = FacetViews.toView(Facets.read(JSONObject(addDevice)).facets)
        val submit = view.sections.single { it.id == "add" }.nodes.last() as ViewNode.Button
        assertEquals(listOf("Device ID"), PluginViewCall.missing(submit.action.args(), view.initialValues()))
        assertEquals(emptyList<String>(), PluginViewCall.missing(submit.action.args(), view.initialValues() + ("deviceId" to "X")))
        // Any other action has nothing to fill in.
        val other = view.sections.single { it.id == "facets" }.nodes.single() as ViewNode.Toggle
        assertEquals(emptyList<String>(), PluginViewCall.missing(other.action!!.args(), emptyMap()))
    }

    @Test
    fun `a form without a submit is a plain group whose inputs need their own calls`() {
        val reading = Facets.read(JSONObject("""{"facets": [{"id": "g", "kind": "group", "form": true, "facets": [
            {"id": "a", "kind": "toggle", "label": "A"},
            {"id": "b", "kind": "toggle", "label": "B", "call": {"op": "setB"}}
        ]}]}"""))
        val group = reading.facets.single().body as FacetBody.Group
        assertFalse(group.isForm)
        assertEquals(listOf("b"), group.facets.map { it.id })
        assertTrue(reading.messages.any { "without a valid submit" in it })
    }

    @Test
    fun `a grant action is droidtop's own prompt for one permission`() {
        val facets = Facets.read(JSONObject("""{"facets": [
            {"id": "allow", "kind": "notice", "title": "Syncthing cannot see your folders", "call": {"kind": "grant", "permission": "files.shared"}},
            {"id": "bad", "kind": "action", "label": "No permission named", "call": {"kind": "grant"}}
        ]}""")).facets
        assertEquals(listOf("allow"), facets.map { it.id })
        val notice = FacetViews.toView(facets).sections.single().nodes.single() as ViewNode.Notice
        assertEquals(ViewAction.Kind.GRANT, notice.action!!.kind)
        assertEquals("files.shared", notice.action!!.op)
        assertEquals("{}", notice.action!!.argsJson)
    }


    @Test
    fun `a secret is droidtop's Show button and its value never enters the page`() {
        val facets = Facets.read(JSONObject("""{"facets": [
            {"id": "pw", "kind": "secret", "label": "Encryption password", "sub": "For the Music folder", "call": {"op": "folderPassword", "args": {"folder": "music"}}},
            {"id": "job", "kind": "secret", "label": "As a job", "call": {"op": "x", "kind": "job"}},
            {"id": "none", "kind": "secret", "label": "No call"}
        ]}""")).facets
        assertEquals(listOf("pw"), facets.map { it.id })
        val show = FacetViews.toView(facets).sections.single().nodes.single() as ViewNode.Button
        assertEquals("Encryption password", show.title)
        assertEquals("Show", show.value)
        assertEquals(ViewAction.Kind.SECRET, show.action.kind)
        assertEquals("music", show.action.args().getString("folder"))
        // Nothing of the value is in the view: it holds no input, so no value is ever sent back or kept with the page.
        assertEquals(emptyMap<String, String>(), FacetViews.toView(facets).initialValues())
    }

    @Test
    fun `an input's field type is its own inputKind, not the envelope's kind`() {
        val reading = Facets.read(JSONObject("""{"facets": [{"id": "port", "kind": "group", "form": true,
            "submit": {"label": "Save", "call": {"op": "save"}},
            "facets": [{"id": "n", "kind": "input", "inputKind": "number", "label": "Port", "value": "22000"}]}]}"""))
        assertEquals(emptyList<String>(), reading.messages)
        val input = (reading.facets.single().body as FacetBody.Group).facets.single().body as FacetBody.Input
        assertEquals("number", input.inputKind)
        val view = FacetViews.toView(reading.facets)
        val submit = view.sections.single().nodes.last() as ViewNode.Button
        assertEquals(22000.0, PluginViewCall.args(submit.action.args(), view.initialValues(), JSONObject()).getJSONObject("values").get("n"))
    }
}
