package dev.droidtop.library.integrations

import dev.droidtop.library.integrations.PluginGameSections.RowAction
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginKind
import dev.droidtop.pluginhost.PluginManifest
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginTrustState
import dev.droidtop.pluginhost.PluginView
import dev.droidtop.pluginhost.ProvidedPoint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The rows a game section puts on a game's page (docs/plugin-api.md 3 C18): every node one row, never a blank. */
class PluginGameSectionsTest {
    private val record = PluginRecord(
        manifest = PluginManifest(
            id = "acme.notes",
            origin = "acme",
            label = "Acme Notes",
            description = null,
            version = "1.0",
            kind = PluginKind.NATIVE_BUNDLE,
            capabilities = emptySet<PluginCapability>(),
            contractVersion = 2,
            requestsRoot = false,
            abis = emptySet(),
            entryClass = "x",
            payload = emptyList(),
        ),
        archiveDigest = "a".repeat(64),
        trust = PluginTrustState.APPROVED,
        enabled = true,
        rootApproved = false,
        disabledReason = null,
    )
    private val section = PluginGameSections.Section(record, ProvidedPoint(point = PluginGameSections.POINT, id = "notes", label = "Notes"))

    private fun view(json: String) = PluginView.parse(JSONObject(json))

    @Test
    fun `every node is one row, and only nodes with something to do take A`() {
        val loaded = PluginGameSections.Loaded(
            section,
            view(
                """
                {"view":1,"sections":[{"id":"s","items":[
                  {"type":"info","id":"i","title":"Beaten","value":"Yes"},
                  {"type":"progress","id":"p","title":"Synced","value":40},
                  {"type":"row","id":"r","title":"Thread","columns":["Ren'Py","v2"],"action":{"kind":"view","op":"thread"}},
                  {"type":"button","id":"b","title":"Sync now","confirm":"Sync?","action":{"kind":"job","op":"sync"}},
                  {"type":"toggle","id":"t","title":"Track","value":true},
                  {"type":"choice","id":"c","title":"Mode","options":[{"value":"a","label":"Auto"}],"value":"a"}
                ]}]}
                """.trimIndent(),
            ),
            null,
        )
        val rows = PluginGameSections.rows(loaded)
        assertEquals(listOf("Beaten", "Synced", "Thread", "Sync now", "Track", "Mode"), rows.map { it.title })
        assertEquals(
            listOf(RowAction.NONE, RowAction.NONE, RowAction.OPEN_VIEW, RowAction.RUN, RowAction.OPEN_SECTION, RowAction.OPEN_SECTION),
            rows.map { it.action },
        )
        assertEquals("40%", rows[1].value)
        assertEquals("Ren'Py · v2", rows[2].subtitle)
        assertEquals("Sync?", rows[3].confirm)
        assertEquals("On", rows[4].value)
        assertEquals("Auto", rows[5].value)
    }

    @Test
    fun `a failed or empty section is one row naming the plugin`() {
        val failed = PluginGameSections.rows(PluginGameSections.Loaded(section, null, "No network"))
        assertEquals("Acme Notes could not show this", failed.single().title)
        assertEquals("No network", failed.single().subtitle)
        assertEquals(RowAction.NONE, failed.single().action)

        val empty = PluginGameSections.rows(PluginGameSections.Loaded(section, view("""{"view":1,"sections":[]}"""), null))
        assertEquals("Acme Notes: nothing here", empty.single().title)
        assertNull(empty.single().viewAction)
    }
}
