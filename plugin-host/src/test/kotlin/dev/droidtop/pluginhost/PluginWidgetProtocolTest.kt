package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** C10 widget templates and which points may be on a widget (docs/plugin-api.md 3 C10, Droidtop/tracker#563). */
class PluginWidgetProtocolTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun `a value template reads typed values and its action`() {
        val t = PluginWidgetProtocol.parse(
            JSONObject().put("template", "value").put("title", "Synced").put("value", JSONObject().put("number", 1_500_000_000L).put("unit", "bytes"))
                .put("action", JSONObject().put("id", "open")),
        ) as WidgetTemplate.Value
        assertEquals("1.5 GB", t.value)
        assertEquals("open", t.action)
        assertEquals(setOf("open"), PluginWidgetProtocol.actionIds(t))
    }

    @Test
    fun `a list keeps at most twenty items, each id once, and offers their actions`() {
        val items = JSONArray()
        repeat(30) { items.put(JSONObject().put("id", "i${it % 25}").put("title", "Item $it").put("action", JSONObject().put("id", "a$it"))) }
        items.put(JSONObject().put("id", "x"))
        val t = PluginWidgetProtocol.parse(JSONObject().put("template", "list").put("items", items).put("empty", "Nothing synced")) as WidgetTemplate.ListOf
        assertEquals(PluginWidgetProtocol.MAX_ITEMS, t.items.size)
        assertEquals(t.items.map { it.id }.toSet().size, t.items.size)
        assertEquals("Nothing synced", t.empty)
        assertTrue("a5" in PluginWidgetProtocol.actionIds(t))
    }

    @Test
    fun `a gauge is 0 to 100, or -1 when unknown`() {
        fun gauge(p: Any) = (PluginWidgetProtocol.parse(JSONObject().put("template", "gauge").put("title", "Battery").put("percent", p)) as WidgetTemplate.Gauge).percent
        assertEquals(42, gauge(42))
        assertEquals(100, gauge(140))
        assertEquals(-1, gauge(-5))
        assertEquals(-1, gauge("lots"))
    }

    @Test
    fun `media keeps three buttons with valid ids`() {
        val buttons = JSONArray()
        listOf("prev", "play", "bad id", "next", "more").forEach { buttons.put(JSONObject().put("id", it).put("label", it)) }
        val t = PluginWidgetProtocol.parse(JSONObject().put("template", "media").put("title", "Song").put("buttons", buttons)) as WidgetTemplate.Media
        assertEquals(listOf("prev", "play", "next"), t.buttons.map { it.id })
        assertNull(t.image)
    }

    @Test
    fun `an image must be what its bytes say and within the cap`() {
        assertNotNull(PluginWidgetProtocol.image(JSONObject().put("data", b64(png)).put("type", "image/png")))
        assertNotNull(PluginWidgetProtocol.image(JSONObject().put("data", b64(png))))
        assertNull(PluginWidgetProtocol.image(JSONObject().put("data", b64(png)).put("type", "image/jpeg")))
        assertNull(PluginWidgetProtocol.image(JSONObject().put("data", b64("<svg/>".toByteArray()))))
        assertNull(PluginWidgetProtocol.image(JSONObject().put("data", "not base64!!")))
        val big = png + ByteArray(PluginWidgetProtocol.IMAGE_MAX_BYTES)
        assertNull(PluginWidgetProtocol.image(JSONObject().put("data", b64(big))))
        assertNull(PluginWidgetProtocol.parse(JSONObject().put("template", "image").put("image", JSONObject().put("data", "AAAA"))))
    }

    @Test
    fun `text is cut, and what droidtop cannot draw is no snapshot`() {
        val t = PluginWidgetProtocol.parse(JSONObject().put("template", "value").put("title", "x".repeat(200)).put("value", "1")) as WidgetTemplate.Value
        assertEquals(PluginWidgetProtocol.TITLE_MAX, t.title.length)
        assertNull(PluginWidgetProtocol.parse(JSONObject().put("template", "html").put("title", "x")))
        assertNull(PluginWidgetProtocol.parse(JSONObject().put("template", "value").put("title", "No value")))
        assertNull((PluginWidgetProtocol.parse(JSONObject().put("template", "list").put("action", JSONObject().put("id", "has space"))) as WidgetTemplate.ListOf).action)
    }

    @Test
    fun `widget capability is the surfaces filter tiles already have`() {
        fun entry(point: String, extra: JSONObject = JSONObject()) = ProvidedPoint(point = point, extra = extra.toString())
        assertTrue(PluginWidgetProtocol.widgetCapable(entry("ui.widget")))
        assertTrue(PluginWidgetProtocol.widgetCapable(entry("ui.status_tile")))
        assertTrue(PluginWidgetProtocol.widgetCapable(entry("ui.quick_tile", JSONObject().put("surfaces", JSONArray().put("gaming.quick_menu").put("android.widget")))))
        assertFalse(PluginWidgetProtocol.widgetCapable(entry("ui.quick_tile", JSONObject().put("surfaces", JSONArray().put("gaming.quick_menu")))))
        assertFalse(PluginWidgetProtocol.widgetCapable(entry("ui.panel")))
    }

    @Test
    fun `calls say they are for an Android widget`() {
        val args = PluginWidgetProtocol.snapshotArgs(250, 110)
        assertEquals(250, args.getJSONObject("size").getInt("widthDp"))
        assertEquals("android.widget", args.getJSONObject("context").getString("surface"))
        assertEquals("open", PluginWidgetProtocol.actionArgs("open").getString("id"))
        assertTrue(ExtensionPoints.supports("ui.widget", 1))
    }
}
