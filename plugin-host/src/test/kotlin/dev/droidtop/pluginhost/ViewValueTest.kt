package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Typed view values (docs/plugin-api.md 1.6 "Typed values", Droidtop/tracker#521 item 7) and `needs` (item 5). */
class ViewValueTest {
    private fun info(value: Any?): String? {
        val node = JSONObject().put("type", "info").put("id", "v").put("title", "Value").put("value", value ?: JSONObject.NULL)
        return (ViewNode.parse(node) as ViewNode.Info).value
    }

    @Test
    fun `a typed value is written in droidtop's words`() {
        assertEquals("On", info(JSONObject().put("bool", true)))
        assertEquals("Off", info(JSONObject().put("bool", false)))
        assertEquals("1.5 GB", info(JSONObject().put("number", 1_500_000_000L).put("unit", "bytes")))
        assertEquals("2 h 5 min", info(JSONObject().put("number", 7500).put("unit", "seconds")))
        assertEquals("42%", info(JSONObject().put("number", 42).put("unit", "percent")))
        assertEquals("100%", info(JSONObject().put("number", 140).put("unit", "percent")))
        assertEquals("1,500", info(JSONObject().put("number", 1500)))
        assertEquals("2.5", info(JSONObject().put("number", 2.5)))
        assertEquals("60 fps", info(JSONObject().put("number", 60).put("unit", "fps")))
        val options = JSONArray().put(JSONObject().put("value", "eu").put("label", "Europe"))
        assertEquals("Europe", info(JSONObject().put("enum", "eu").put("options", options)))
        assertEquals("us", info(JSONObject().put("enum", "us").put("options", options)))
    }

    @Test
    fun `a plain value is kept and an unreadable object is no value`() {
        assertEquals("Connected", info("Connected"))
        assertNull(info(null))
        assertNull(info(JSONObject().put("colour", "red")))
        assertNull(info(JSONObject().put("number", "many")))
    }

    @Test
    fun `needs is read per node and kept with its section`() {
        val items = JSONArray()
            .put(JSONObject().put("type", "info").put("id", "a").put("title", "Clipboard").put("needs", "clipboard.read"))
            .put(JSONObject().put("type", "info").put("id", "b").put("title", "Always"))
        val view = PluginView.parse(JSONObject().put("view", 1).put("sections", JSONArray().put(JSONObject().put("id", "s").put("items", items))))!!
        assertEquals(mapOf("a" to "clipboard.read"), view.sections.single().needs)
    }
}
