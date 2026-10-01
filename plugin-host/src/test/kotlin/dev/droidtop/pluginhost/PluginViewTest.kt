package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginViewTest {
    private fun view(vararg items: JSONObject): JSONObject = JSONObject()
        .put("view", 1)
        .put("title", "Page")
        .put("sections", JSONArray().put(JSONObject().put("id", "main").put("items", JSONArray(items.toList()))))

    private fun node(type: String, id: String, vararg kv: Pair<String, Any>): JSONObject =
        JSONObject().put("type", type).put("id", id).put("title", "T $id").also { o -> kv.forEach { (k, v) -> o.put(k, v) } }

    private fun action(kind: String, op: String) = JSONObject().put("kind", kind).put("op", op).put("args", JSONObject().put("ref", "x"))

    @Test
    fun `every node type reads into its typed node`() {
        val parsed = PluginView.parse(
            view(
                node("info", "i", "value" to "v"),
                node("row", "r", "columns" to JSONArray(listOf("SNES", "1 MB")), "badges" to JSONArray(listOf("Verified")), "action" to action("view", "detail")),
                node("button", "b", "confirm" to "Sure?", "action" to action("job", "acquire")),
                node("toggle", "t", "value" to true),
                node("choice", "c", "options" to JSONArray().put(JSONObject().put("value", "eu").put("label", "Europe")), "value" to "eu"),
                node("slider", "s", "min" to 1, "max" to 5, "value" to 9),
                node("text", "q", "value" to "zelda", "action" to action("call", "search")),
                node("progress", "p", "value" to 45),
            ),
        )
        assertNotNull(parsed)
        val nodes = parsed!!.sections.single().nodes
        assertEquals(8, nodes.size)
        val row = nodes[1] as ViewNode.Row
        assertEquals("SNES · 1 MB", row.shownSubtitle())
        assertEquals("Verified", row.shownValue())
        assertEquals(ViewAction.Kind.VIEW, row.action!!.kind)
        assertEquals("x", row.action!!.args().getString("ref"))
        assertEquals("Sure?", (nodes[2] as ViewNode.Button).confirm)
        assertEquals("a slider value is kept inside its range", 5, (nodes[5] as ViewNode.Slider).value)
        assertEquals(mapOf("t" to "true", "c" to "eu", "s" to "5", "q" to "zelda"), parsed.initialValues())
    }

    @Test
    fun `unknown types, secret fields and nodes without id or title are dropped, not refused`() {
        val parsed = PluginView.parse(
            view(
                node("hologram", "h"),
                node("text", "pw", "secret" to true),
                JSONObject().put("type", "info").put("title", "no id"),
                node("button", "b"),
                node("info", "ok"),
            ),
        )!!
        assertEquals(listOf("ok"), parsed.sections.single().nodes.map { it.id })
    }

    @Test
    fun `an action with an unknown kind or oversized args leaves its row inert`() {
        val big = JSONObject().put("blob", "x".repeat(PluginView.MAX_ARGS_BYTES + 1))
        val parsed = PluginView.parse(
            view(
                node("row", "a", "action" to JSONObject().put("kind", "teleport").put("op", "x")),
                node("row", "b", "action" to JSONObject().put("kind", "call").put("op", "x").put("args", big)),
            ),
        )!!
        parsed.sections.single().nodes.forEach { assertNull((it as ViewNode.Row).action) }
    }

    @Test
    fun `limits cut the view instead of refusing it`() {
        val items = (0 until PluginView.MAX_NODES + 50).map { node("info", "n$it") }
        val parsed = PluginView.parse(view(*items.toTypedArray()))!!
        assertEquals(PluginView.MAX_NODES, parsed.sections.single().nodes.size)
        val long = PluginView.parse(view(node("info", "l").put("title", "y".repeat(1000))))!!
        assertTrue(long.sections.single().nodes.single().title.length <= PluginView.MAX_TITLE)
    }

    @Test
    fun `a different view version or a missing sections list is not a view`() {
        assertNull(PluginView.parse(JSONObject().put("view", 2).put("sections", JSONArray())))
        assertNull(PluginView.parse(JSONObject().put("view", 1)))
        assertNull(PluginView.parse(null))
    }

    @Test
    fun `droidtop's values and context are written after the plugin's args`() {
        val args = PluginViewCall.args(
            JSONObject().put("ref", "r1").put("context", "spoofed").put("values", "spoofed"),
            mapOf("region" to "eu"),
            JSONObject().put("destination", "/storage/roms/snes"),
        )
        assertEquals("r1", args.getString("ref"))
        assertEquals("eu", args.getJSONObject("values").getString("region"))
        assertEquals("/storage/roms/snes", args.getJSONObject("context").getString("destination"))
    }

    @Test
    fun `search results read the contract 2 shape and keep ref unread`() {
        val data = JSONObject().put(
            "results",
            JSONArray()
                .put(JSONObject().put("id", "a").put("title", "Game A").put("columns", JSONArray(listOf("SNES"))).put("ref", JSONObject().put("k", 1)))
                .put(JSONObject().put("id", "b")),
        )
        val rows = SourceResultProtocol.results(data)
        assertEquals(1, rows.size)
        assertEquals(listOf("SNES"), rows.single().columns)
        assertEquals(1, JSONObject(rows.single().refJson).getInt("k"))
    }

    @Test
    fun `a call reply may carry a message and a replacement view`() {
        val data = JSONObject().put("message", "Index downloaded").put("view", view(node("info", "x")))
        assertEquals("Index downloaded", PluginViewCall.replyMessage(data))
        assertEquals("x", PluginViewCall.replyView(data)!!.sections.single().nodes.single().id)
    }
}
