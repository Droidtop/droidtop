package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Declared settings (Droidtop/tracker#880, docs/plugin-api.md 1.10): rows read from the manifest, values kept by
 * droidtop and checked against their row, pushed text, and what changed since the plugin last heard.
 */
class DeclaredSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val rows = JSONArray()
        .put(JSONObject().put("id", "watch.sync").put("kind", "action").put("label", "Sync the watch list now").put("op", "sync_watch_list").put("when", "web.session.signed_in"))
        .put(JSONObject().put("id", "watch.info").put("kind", "info").put("label", "Watch list").put("when", "not web.session.signed_in"))
        .put(JSONObject().put("id", "nsfw").put("kind", "input").put("label", "Show adult games").put("input", "toggle").put("default", true))
        .put(JSONObject().put("id", "region").put("kind", "input").put("label", "Region").put("input", "choice")
            .put("choices", JSONArray().put(JSONObject().put("value", "eu").put("label", "Europe")).put(JSONObject().put("value", "us").put("label", "US")))
            .put("default", "us"))
        .put(JSONObject().put("id", "BAD ID").put("kind", "info").put("label", "x"))
        .put(JSONObject().put("id", "nowhen").put("kind", "info").put("label", "x").put("when", "the moon is full"))

    private val record = TestPlugins.record(TestPlugins.manifest { it.put("settings", rows) })

    @Test
    fun `rows are read from the manifest and what does not fit is left out`() {
        val settings = record.manifest.v2.settings
        assertEquals(listOf("watch.sync", "watch.info", "nsfw", "region"), settings.map { it.id })
        assertEquals("sync_watch_list", settings[0].op)
        assertEquals("web.session.signed_in", settings[0].whenCondition)
        assertEquals(true, settings[2].default)
        assertEquals("us", settings[3].default)
        // A round trip through the record keeps them.
        assertEquals(settings, V2Declarations.fromJson(JSONObject().also { record.manifest.v2.putInto(it) }).settings)
    }

    @Test
    fun `values are the defaults until the person sets one, and each is checked against its row`() {
        val store = PluginSettings(tmp.newFolder("settings"))
        assertEquals(mapOf("nsfw" to true, "region" to "us"), store.values(record))
        assertNull(store.set(record, "region", "eu"))
        assertNotNull(store.set(record, "region", "mars"))
        assertNotNull(store.set(record, "nsfw", "yes"))
        assertNotNull(store.set(record, "watch.info", "x"))
        assertEquals(mapOf("nsfw" to true, "region" to "eu"), store.values(record))
    }

    @Test
    fun `a change is pending until the plugin has heard it`() {
        val store = PluginSettings(tmp.newFolder("settings"))
        store.set(record, "nsfw", false)
        store.set(record, "region", "eu")
        assertEquals(listOf("nsfw", "region"), store.pending(record.manifest.id))
        assertEquals(listOf("nsfw", "region"), store.pending(record.manifest.id, clear = true))
        assertTrue(store.pending(record.manifest.id).isEmpty())
    }

    @Test
    fun `pushed text is kept per declared row and nothing else`() {
        val store = PluginSettings(tmp.newFolder("settings"))
        assertNull(store.text(record.manifest.id, "watch.info"))
        assertNull(store.setText(record, "watch.info", "12 watched threads", null))
        assertEquals(PluginSettings.Text("12 watched threads", null), store.text(record.manifest.id, "watch.info"))
        assertNotNull(store.setText(record, "undeclared", "x", null))
        store.setText(record, "watch.info", "x".repeat(500), "synced just now")
        assertEquals(PluginSettings.MAX_PUSHED, store.text(record.manifest.id, "watch.info")!!.value!!.length)
    }
}
