package dev.droidtop.pluginhost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A source result's own facets and its box art (docs/plugin-api.md 1.8, "featured"; Droidtop/tracker#612). */
class SourceResultFacetsTest {
    private fun reply(facets: String) = JSONObject("""{"results": [{"id": "1", "title": "Eternum", "facets": $facets}]}""")

    @Test
    fun aResultsFacetsAreReadAndItsBoxArtIsTheCoverNeverAScreenshot() {
        val row = SourceResultProtocol.results(
            reply(
                """[
                  {"id": "shot", "kind": "media", "v": 1, "role": "screenshot", "url": "https://a.example/shot.png", "alt": "A scene"},
                  {"id": "banner", "kind": "image", "v": 1, "role": "banner", "url": "https://a.example/banner.png", "alt": "Eternum"},
                  {"id": "cover", "kind": "image", "v": 1, "role": "cover", "url": "https://a.example/cover.png", "alt": "Eternum"}
                ]""",
            ),
        ).single()
        assertEquals(3, row.facets.size)
        assertEquals("https://a.example/cover.png", SourceResultProtocol.boxArt(row.facets))
        assertEquals("https://a.example/banner.png", SourceResultProtocol.boxArt(row.facets.filter { it.id != "cover" }))
        assertNull("only screenshots: no box art", SourceResultProtocol.boxArt(row.facets.filter { it.id == "shot" }))
    }

    @Test
    fun aResultWithoutFacetsIsReadAsBefore() {
        val row = SourceResultProtocol.results(JSONObject("""{"results": [{"id": "1", "title": "Eternum"}]}""")).single()
        assertTrue(row.facets.isEmpty())
        assertNull(SourceResultProtocol.boxArt(row.facets))
    }
}
