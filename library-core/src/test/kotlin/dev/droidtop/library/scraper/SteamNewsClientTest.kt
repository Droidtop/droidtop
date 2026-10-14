package dev.droidtop.library.scraper

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "What's new" from Steam's news answer (Droidtop/tracker#229). */
class SteamNewsClientTest {
    private fun answer(vararg items: String) = JSONObject("""{"appnews":{"appid":440,"newsitems":[${items.joinToString(",")}],"count":${items.size}}}""")

    private fun item(title: String, date: Long, patchNotes: Boolean = false, contents: String = "Body"): String =
        JSONObject()
            .put("gid", "1")
            .put("title", title)
            .put("url", "https://store.steampowered.com/news/app/440/view/$date")
            .put("contents", contents)
            .put("feedname", "steam_community_announcements")
            .put("date", date)
            .apply { if (patchNotes) put("tags", org.json.JSONArray().put("patchnotes")) }
            .toString()

    @Test
    fun `the latest patch notes win over a newer announcement`() {
        val found = SteamNewsClient.parse(answer(item("Sale!", 300), item("Update 1.2", 200, patchNotes = true), item("Update 1.1", 100, patchNotes = true)))
        val post = (found as ScrapeLookup.Found).value
        assertEquals("Update 1.2", post.title)
        assertTrue(post.patchNotes)
        assertEquals(200_000L, post.postedAtMillis)
    }

    @Test
    fun `with no patch notes the latest announcement is shown, and none at all is no match`() {
        val found = SteamNewsClient.parse(answer(item("Old", 100), item("New", 200)))
        assertEquals("New", (found as ScrapeLookup.Found).value.title)
        assertEquals(ScrapeLookup.NoMatch, SteamNewsClient.parse(answer()))
        assertEquals(ScrapeLookup.NoMatch, SteamNewsClient.parse(JSONObject("{}")))
    }

    @Test
    fun `BBCode and Steam's image placeholders are dropped from the excerpt`() {
        assertEquals(
            "Fixes Faster loading",
            SteamNewsClient.plainText("{STEAM_CLAN_IMAGE}/123/abc.png [h2]Fixes[/h2]\n[list][*]Faster loading[/list]"),
        )
    }
}
