package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A plugin's presentation fields (docs/plugin-api.md 1.2 "Presentation", Droidtop/tracker#521 item 2). */
class PluginPresentationTest {
    private val payload = setOf("res/icon.png", "res/shot1.webp", "res/shot2.png", "plugin.py")

    @Test
    fun `each field is read, checked and cut`() {
        val p = PluginPresentation.fromJson(
            JSONObject()
                .put("summary", "  Finds   retro games  from archives  ")
                .put("publisher", "Acme")
                .put("homepage", "https://acme.example/vpn")
                .put("category", "Sources")
                .put("icon", "res/icon.png")
                .put("screenshots", JSONArray().put("res/shot1.webp").put("res/shot2.png").put("res/shot1.webp")),
            payload,
        )
        assertEquals("Finds retro games from archives", p.summary)
        assertEquals("Acme", p.publisher)
        assertEquals("https://acme.example/vpn", p.homepage)
        assertEquals("sources", p.category)
        assertEquals("res/icon.png", p.icon)
        assertEquals(listOf("res/shot1.webp", "res/shot2.png"), p.screenshots)
        val long = PluginPresentation.fromJson(JSONObject().put("summary", "s".repeat(200)), payload).summary!!
        assertEquals(PluginPresentation.SUMMARY_MAX, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun `what droidtop cannot vouch for is dropped, never refused`() {
        val p = PluginPresentation.fromJson(
            JSONObject()
                .put("homepage", "http://acme.example")
                .put("category", "crypto")
                .put("icon", "res/missing.png")
                .put("screenshots", JSONArray().put("plugin.py").put("https://cdn.example/shot.png")),
            payload,
        )
        assertNull(p.homepage)
        assertEquals("other", p.category)
        assertNull("an icon must be a signed payload image", p.icon)
        assertTrue(p.screenshots.isEmpty())
        assertEquals(PluginPresentation(), PluginPresentation.fromJson(JSONObject(), payload))
    }

    @Test
    fun `it survives the record round trip`() {
        val p = PluginPresentation(summary = "VPN", publisher = "Acme", homepage = "https://acme.example", category = "system", icon = "res/icon.png")
        val json = JSONObject().also { p.putInto(it) }
        assertEquals(p, PluginPresentation.fromJson(json, payload))
    }

    @Test
    fun `categories come from the fixed list, the first list's ids read as their successors`() {
        assertEquals("sources", PluginPresentation.category("sources"))
        assertEquals("sync", PluginPresentation.category(" Sync "))
        assertEquals("system", PluginPresentation.category("tools"))
        assertEquals("appearance", PluginPresentation.category("themes"))
        assertEquals("integrations", PluginPresentation.category("social"))
        assertEquals("sources", PluginPresentation.category("games"))
        assertEquals("other", PluginPresentation.category(null))
        assertEquals("other", PluginPresentation.category("crypto"))
        PluginPresentation.CATEGORIES.forEach { assertTrue(it, PluginPresentation.CATEGORY_LABELS.containsKey(it)) }
    }
}
