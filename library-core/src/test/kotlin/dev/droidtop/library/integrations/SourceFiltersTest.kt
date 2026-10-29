package dev.droidtop.library.integrations

import dev.droidtop.pluginhost.FacetBody
import dev.droidtop.pluginhost.FacetGuards
import dev.droidtop.pluginhost.Facets
import dev.droidtop.pluginhost.ViewNode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A source's own search options (docs/plugin-api.md 1.10, `search.filter`): held by droidtop, sent with each search. */
class SourceFiltersTest {
    private val reply = JSONObject(
        """
        {"facets": [
          {"id": "sites", "kind": "choice", "targets": ["search.filter"], "label": "Site", "value": "all",
           "options": [{"value": "all", "label": "Every site"}, {"value": "f95", "label": "F95zone"}]},
          {"id": "completed", "kind": "toggle", "targets": ["search.filter"], "label": "Completed only", "value": false},
          {"id": "index", "kind": "notice", "targets": ["search.filter"], "title": "No game index yet",
           "call": {"op": "downloadIndex", "kind": "job"}},
          {"id": "chart", "kind": "chart", "targets": ["search.filter"], "label": "Not here", "points": [1, 2]}
        ]}
        """.trimIndent(),
    )

    private val source = FacetGuards.Plugin(id = "gamegrab.repacks", declaredTargets = setOf(PluginFacets.SEARCH_FILTER), domains = emptyList())

    @Test
    fun `its options need no call of their own and only the option kinds are drawn`() {
        val held = Facets.read(reply, held = true)
        val shown = FacetGuards.admit(held.facets, PluginFacets.SEARCH_FILTER, source, mutableListOf())
        assertEquals(listOf("sites", "completed", "index"), shown.map { it.id })
        assertNull((shown[1].body as FacetBody.Toggle).call)
        // Read anywhere else, an input without a call is dropped, as before.
        assertEquals(listOf("index", "chart"), Facets.read(reply).facets.map { it.id })
    }

    @Test
    fun `what the person set is laid over the plugin's defaults`() {
        val facets = Facets.read(reply, held = true).facets
        val kept = JSONObject().put("sites", "f95").put("completed", true)
        val applied = SourceFilters.applied(facets, kept)
        assertEquals("f95", (applied[0].body as FacetBody.Choice).value)
        assertEquals(true, (applied[1].body as FacetBody.Toggle).value)
        // A kept choice that is no longer offered is not shown.
        assertEquals("all", (SourceFilters.applied(facets, JSONObject().put("sites", "gone"))[0].body as FacetBody.Choice).value)
    }

    @Test
    fun `values are kept typed as the plugin reads them`() {
        assertEquals(true, SourceFilters.typed(ViewNode.Toggle("completed", "Completed only", null, false, null), "true"))
        assertEquals(2.0, SourceFilters.typed(ViewNode.Slider("min", "Rating", null, 0, 5, 0, null), "2"))
        assertEquals("f95", SourceFilters.typed(ViewNode.Choice("sites", "Site", null, emptyList(), null, null), "f95"))
        assertNull(SourceFilters.typed(ViewNode.Info("x", "x", null, null), "y"))
    }
}
