package dev.droidtop.library.integrations

import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginKind
import dev.droidtop.pluginhost.PluginManifest
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginTrustState
import dev.droidtop.pluginhost.ProvidedPoint
import dev.droidtop.pluginhost.V2Declarations
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which systems a source plugin is listed under and what state Game sources shows for it (Droidtop/tracker#540, #543). */
class SourcesServeTest {
    private fun record(
        systems: Any? = null,
        trust: PluginTrustState = PluginTrustState.APPROVED,
        enabled: Boolean = true,
        disabledReason: String? = null,
    ): PluginRecord {
        val entry = JSONObject().put("point", "library.sources").put("version", 1)
        if (systems != null) entry.put("systems", systems)
        return PluginRecord(
            manifest = PluginManifest(
                id = "acme.source",
                origin = "acme",
                label = "Acme Source",
                description = null,
                version = "1.0",
                kind = PluginKind.NATIVE_BUNDLE,
                capabilities = setOf(PluginCapability.ACQUIRE_CONTENT),
                contractVersion = 2,
                requestsRoot = false,
                abis = emptySet(),
                entryClass = "x",
                payload = emptyList(),
                v2 = V2Declarations(provides = listOf(ProvidedPoint.fromJson(entry)!!)),
            ),
            archiveDigest = "a".repeat(64),
            trust = trust,
            enabled = enabled,
            rootApproved = false,
            disabledReason = disabledReason,
        )
    }

    private fun source(systems: Any? = null) = AcquireContentSource.Plugin(record(systems))

    @Test
    fun `a source that declares systems is listed only under them and the PC library`() {
        val f95 = source(JSONArray(listOf("windows", "linux")))
        assertTrue(f95.serves("windows"))
        assertTrue(f95.serves("pc"))
        assertFalse(f95.serves("gba"))
    }

    @Test
    fun `a console source is not asked by the PC library`() {
        assertFalse(source(JSONArray(listOf("psp"))).serves("pc"))
    }

    @Test
    fun `a source that declares nothing is listed everywhere as before`() {
        val old = source()
        assertTrue(old.serves("gba"))
        assertTrue(old.serves("pc"))
        assertTrue(old.serves(null))
    }

    @Test
    fun `a source that serves only PC systems opens straight on the PC search, any other asks the system`() {
        val entry = SourceScreens.EntryContext(emptyList(), emptyList(), null, java.io.File("games"))
        assertEquals("source_search_plugin_acme.source_pc", SourceScreens.entryScreen(source(JSONArray(listOf("windows", "dos"))), entry).id)
        assertEquals("source_entry_plugin_acme.source", SourceScreens.entryScreen(source("*"), entry).id)
        assertEquals("source_entry_plugin_acme.source", SourceScreens.entryScreen(source(), entry).id)
    }

    @Test
    fun `the state shown follows the plugin's trust and switches`() {
        assertEquals(SourceState.READY, AcquireContentSources.stateOf(record()))
        assertEquals(SourceState.WAITING_FOR_APPROVAL, AcquireContentSources.stateOf(record(trust = PluginTrustState.PENDING)))
        assertEquals(SourceState.DENIED, AcquireContentSources.stateOf(record(trust = PluginTrustState.DENIED)))
        assertEquals(SourceState.DISABLED, AcquireContentSources.stateOf(record(disabledReason = "crashed")))
        assertEquals(SourceState.TURNED_OFF, AcquireContentSources.stateOf(record(enabled = false)))
        assertEquals("Waiting for your approval", SourceListing(record(trust = PluginTrustState.PENDING), SourceState.WAITING_FOR_APPROVAL).stateLabel)
    }
}
