package dev.droidtop.library.integrations

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AcquireContentSources.parseResults] against the two real shapes it has
 * to read: the generic one SPEC 12a settled on (id/title/subtitle/
 * platform/artUrl/options), and the older bare title/platform/size_str
 * shape the per-system "Get games" screen has always accepted, so this
 * change stays backward-compatible with it.
 */
class AcquireContentSourcesParseTest {

    private fun entriesJson(vararg entries: JSONObject): String = JSONArray(entries.toList()).toString()

    @Test
    fun `reads the generic result shape with options built from links`() {
        val obj = JSONObject()
            .put("slug", "some-game-usa")
            .put("title", "Some Game")
            .put("platform", "snes")
            .put("boxart", "https://example.invalid/art.png")
            .put("regions", JSONArray(listOf("USA", "Europe")))
            .put(
                "links",
                JSONArray(
                    listOf(
                        JSONObject().put("name", "Mirror A").put("host", "host-a").put("size_str", "2 MB"),
                        JSONObject().put("name", "Mirror B").put("host", "host-b").put("size_str", "2 MB"),
                    ),
                ),
            )
        val results = AcquireContentSources.parseResults(entriesJson(obj))
        assertEquals(1, results.size)
        val result = results.single()
        assertEquals("some-game-usa", result.id)
        assertEquals("Some Game", result.title)
        assertEquals("snes", result.platform)
        assertEquals("https://example.invalid/art.png", result.artUrl)
        assertEquals("USA, Europe", result.subtitle)
        assertEquals(2, result.options.size)
        assertEquals(0, result.options[0].index)
        assertEquals(1, result.options[1].index)
        assertTrue(result.options[0].label.contains("Mirror A"))
    }

    @Test
    fun `falls back to name and a hashed id when the generic fields are absent`() {
        val obj = JSONObject().put("name", "Legacy Result").put("platform", "genesis").put("size_str", "1 MB")
        val result = AcquireContentSources.parseResults(entriesJson(obj)).single()
        assertEquals("Legacy Result", result.title)
        assertEquals("1 MB", result.sizeLabel)
        assertTrue(result.id.isNotBlank())
        assertEquals(emptyList<AcquireContentOption>(), result.options)
        assertNull(result.subtitle)
    }

    @Test
    fun `a size label falls back to the first link's own size_str`() {
        val obj = JSONObject()
            .put("title", "Game")
            .put("links", JSONArray(listOf(JSONObject().put("size_str", "700 MB"))))
        val result = AcquireContentSources.parseResults(entriesJson(obj)).single()
        assertEquals("700 MB", result.sizeLabel)
    }

    @Test
    fun `a result with no title is dropped, not crashed on`() {
        val obj = JSONObject().put("platform", "nes")
        assertEquals(emptyList<AcquireContentResult>(), AcquireContentSources.parseResults(entriesJson(obj)))
    }

    @Test
    fun `malformed json returns no results rather than throwing`() {
        assertEquals(emptyList<AcquireContentResult>(), AcquireContentSources.parseResults("not json"))
    }
}
