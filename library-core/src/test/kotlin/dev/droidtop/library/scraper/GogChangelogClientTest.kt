package dev.droidtop.library.scraper

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GogChangelogClientTest {
    @Test fun `the newest entry is the text up to the next version heading`() {
        val html = "<h4>1.2.0 (12 October 2026)</h4><ul><li>New &amp; improved saves</li><li>Fixed a crash</li></ul>" +
            "<h4>1.1.0</h4><ul><li>Older</li></ul>"
        assertEquals("1.2.0 (12 October 2026). New & improved saves. Fixed a crash", GogChangelogClient.newestEntry(html))
    }

    @Test fun `bold paragraphs count as headings too`() {
        val html = "<p><b>Patch 3</b><br>Balance changes</p><p><b>Patch 2</b><br>Old</p>"
        assertEquals("Patch 3. Balance changes", GogChangelogClient.newestEntry(html))
    }

    @Test fun `a long entry is cut on a word`() {
        val entry = GogChangelogClient.newestEntry("<h4>1.0</h4><p>" + "word ".repeat(200) + "</p>")
        assertTrue(entry.endsWith("…"))
        assertTrue(entry.length <= 281)
        assertTrue(entry.removeSuffix("…").endsWith("word"))
    }

    @Test fun `the product answer gives the entry and the game's page, and no changelog is no match`() {
        val found = GogChangelogClient.parse(
            JSONObject("""{"id":1207658691,"changelog":"<h4>1.1</h4><p>Fixes</p>","links":{"product_card":"https://www.gog.com/en/game/sample"}}"""),
        )
        assertEquals(ScrapeLookup.Found(GogChangelog("1.1. Fixes", "https://www.gog.com/en/game/sample")), found)
        assertEquals(ScrapeLookup.NoMatch, GogChangelogClient.parse(JSONObject("""{"id":1,"changelog":null}""")))
        assertEquals(ScrapeLookup.NoMatch, GogChangelogClient.parse(JSONObject("""{"id":1}""")))
    }
}
