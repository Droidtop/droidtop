package dev.droidtop.library.integrations

import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.TextBlockItem
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginKind
import dev.droidtop.pluginhost.PluginManifest
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginTrustState
import dev.droidtop.pluginhost.ProvidedPoint
import dev.droidtop.pluginhost.V2Declarations
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** A source result's detail page drawn from its item bundles (docs/SPEC.md 12a, "A source's item page"; Droidtop/tracker#737). */
@RunWith(RobolectricTestRunner::class)
class SourceItemPageTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private val record = PluginRecord(
        manifest = PluginManifest(
            id = "acme.repacks",
            origin = "acme",
            label = "Repacks",
            description = null,
            version = "1.0",
            kind = PluginKind.NATIVE_BUNDLE,
            capabilities = setOf(PluginCapability.ACQUIRE_CONTENT),
            contractVersion = 3,
            requestsRoot = false,
            abis = emptySet(),
            entryClass = "x",
            payload = emptyList(),
            v2 = V2Declarations(provides = listOf(ProvidedPoint.fromJson(JSONObject().put("point", "library.sources").put("version", 1))!!)),
        ),
        archiveDigest = "a".repeat(64),
        trust = PluginTrustState.APPROVED,
        enabled = true,
        rootApproved = false,
        disabledReason = null,
    )

    // A repack's detail reply, as contract 3 facets: what it is, its downloads (one in parts), its links and a switch.
    private val reply = JSONObject(
        """
        {"title": "Sample Game", "facets": [
          {"id": "meta", "kind": "metadata", "v": 1, "title": "Sample Game", "version": "1.2", "developer": "Sample Dev", "tags": ["rpg"]},
          {"id": "about", "kind": "description", "v": 1, "overview": "A story.", "changelog": "1.2: fixes"},
          {"id": "dl", "kind": "download", "v": 1, "entries": [
            {"section": "Repack", "host": "Magnet", "label": "Magnet", "link": "magnet-0", "kind": "magnet"},
            {"section": "Repack", "host": "FuckingFast", "label": "All parts", "parts": [
              {"label": "part1.rar", "url": "https://fuckingfast.co/a", "size": 3145728},
              {"label": "part2.rar", "url": "https://fuckingfast.co/b", "size": 2097152}]}]},
          {"id": "links", "kind": "links", "v": 1, "entries": [{"kind": "source", "label": "Open the release page", "url": "https://repacks.example/sample/"}]},
          {"id": "thread", "kind": "link", "v": 1, "label": "Open the forum thread", "url": "https://forum.example/t/1"},
          {"id": "watch", "kind": "toggle", "v": 1, "label": "Watch this", "value": false, "call": {"op": "watch"}}
        ]}
        """.trimIndent(),
    )

    private fun page(data: JSONObject = reply) =
        SourceItemPage.read(context, record, "sample", JSONObject().put("id", "sample"), JSONObject().put("destination", "/games"), data, mutableListOf())

    @Test
    fun `a detail reply of bundles is drawn as the record is, with Overview, Downloads, Changelog and Links`() {
        val page = page()!!
        assertEquals(listOf("Overview", "Downloads", "Changelog", "Links"), page.groups.map { it.title })
        val overview = page.groups[0].items
        assertEquals(listOf("Version", "Developer", "Tags"), overview.dropLast(1).map { it.title })
        assertEquals("A story.", (overview.last() as TextBlockItem).text)
        assertEquals("1.2: fixes", (page.groups[2].items.single() as TextBlockItem).text)
    }

    @Test
    fun `a release in parts is one download row, its parts counted`() {
        // Two mirrors under one heading are one row that tries them in turn (Droidtop/tracker#838).
        val downloads = page()!!.groups[1].items
        val repack = downloads.single() as AsyncActionItem
        assertEquals("Repack", repack.title)
        assertEquals("Magnet, FuckingFast", repack.subtitle)
        // On its own, the release in parts is its own row, its parts counted.
        val onlyParts = JSONObject(reply.toString()).apply {
            val dl = getJSONArray("facets").getJSONObject(facetIndex("dl"))
            dl.getJSONArray("entries").remove(0)
        }
        val parts = page(onlyParts)!!.groups[1].items.single()
        assertTrue(parts.title.startsWith("FuckingFast"))
        assertEquals("2 parts, 5 MB", parts.value)
    }

    private fun facetIndex(id: String): Int {
        val facets = reply.getJSONArray("facets")
        return (0 until facets.length()).first { facets.getJSONObject(it).optString("id") == id }
    }

    @Test
    fun `the links bundle and a link facet are the Links, and the rest of the facets stay the plugin's view`() {
        val page = page()!!
        assertEquals(listOf("Open the release page", "Open the forum thread"), page.groups[3].items.map { it.title })
        assertEquals(listOf("forum.example"), page.groups[3].items.drop(1).map { it.value })
        val rest = page.rest.sections.flatMap { it.nodes }
        assertEquals(listOf("Watch this"), rest.map { it.title })
    }

    @Test
    fun `a reply with no bundles is read as any view`() {
        assertNull(page(JSONObject().put("sections", org.json.JSONArray())))
        val onlyControls = JSONObject().put("facets", org.json.JSONArray().put(JSONObject("""{"id": "watch", "kind": "toggle", "v": 1, "label": "Watch this", "value": false, "call": {"op": "watch"}}""")))
        assertNull(page(onlyControls))
    }
}
