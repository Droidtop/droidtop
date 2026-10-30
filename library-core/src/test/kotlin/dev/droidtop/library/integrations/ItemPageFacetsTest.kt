package dev.droidtop.library.integrations

import dev.droidtop.pluginhost.FacetGuards
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A source's item bundles as the record page droidtop draws (docs/plugin-api.md 1.10, item.page). */
class ItemPageFacetsTest {
    // The F95zone record of docs/plugin-api.md 1.10, as contract 3.
    private val reply = JSONObject(
        """
        {"items": [{"source": "f95zone", "id": "93340", "ttl": 86400, "facets": [
          {"id": "meta", "kind": "metadata", "v": 1, "title": "Eternum", "version": "0.9.5 Public", "developer": "Caribdis",
           "engine": "renpy", "status": "ongoing", "rating": 0.96, "votes": 310, "tags": ["3dcg", "male protagonist"],
           "updated": 1700000000000},
          {"id": "about", "kind": "description", "v": 1, "overview": "You are moving with your best friend to the city of Kredon.",
           "changelog": "v0.9.5: ..."},
          {"id": "cover", "kind": "image", "v": 1, "role": "cover", "url": "https://attachments.f95zone.to/2023/10/3018543_f95zone_banner.png", "alt": "Eternum"},
          {"id": "dl", "kind": "download", "v": 1, "entries": [
            {"section": "Win/Linux", "host": "DATANODES", "label": "DATANODES", "url": "https://datanodes.to/f/2", "kind": "page"},
            {"section": "Win/Linux", "host": "AKIRABOX", "label": "AKIRABOX", "link": "0:AKIRABOX"}]},
          {"id": "links", "kind": "links", "v": 1, "entries": [{"kind": "source", "label": "F95zone thread", "url": "https://f95zone.to/threads/93340/"}]}
        ]}]}
        """.trimIndent(),
    )

    private val f95 = FacetGuards.Plugin(
        id = "gamegrab.f95zone",
        declaredTargets = setOf(PluginFacets.ITEM_PAGE),
        domains = listOf("f95zone.to", "*.f95zone.to"),
        storeOrSource = true,
    )

    @Test
    fun `the item's bundles become the record page`() {
        val item = ItemPageFacets.itemJson(reply, "93340")!!
        val messages = mutableListOf<String>()
        val record = ItemPageFacets.record(ItemPageFacets.admitted(item, "f95zone", "93340", f95, messages), "93340")!!
        assertEquals("Eternum", record.title)
        assertEquals("0.9.5 Public", record.version)
        assertEquals("Caribdis", record.developer)
        assertEquals(310, record.votes)
        assertEquals(listOf("3dcg", "male protagonist"), record.tags)
        assertEquals("https://f95zone.to/threads/93340/", record.url)
        assertEquals("v0.9.5: ...", record.changelog)
        assertEquals("https://attachments.f95zone.to/2023/10/3018543_f95zone_banner.png", record.cover)
        assertEquals(listOf("DATANODES", "AKIRABOX"), record.downloads.map { it.host })
        assertEquals("https://datanodes.to/f/2", record.downloads[0].url)
        assertEquals("0:AKIRABOX", record.downloads[1].link)
    }

    @Test
    fun `a cover off the plugin's own sites is not drawn, and the rest of the page is`() {
        val item = ItemPageFacets.itemJson(reply, "93340")!!
        val elsewhere = f95.copy(domains = listOf("f95zone.to"))
        val record = ItemPageFacets.record(ItemPageFacets.admitted(item, "f95zone", "93340", elsewhere, mutableListOf()), "93340")!!
        assertNull(record.cover)
        assertEquals("Eternum", record.title)
    }

    @Test
    fun `another item, or a contract 2 record, is not read as bundles`() {
        assertNull(ItemPageFacets.itemJson(reply, "1"))
        assertNull(ItemPageFacets.itemJson(JSONObject().put("record", JSONObject().put("id", "1")), "1"))
        assertNull("no bundles, no record", ItemPageFacets.record(emptyList(), "1"))
    }

    @Test
    fun `the source's own switch, button and notice on an item page are its record's controls`() {
        val item = JSONObject(
            """
            {"facets": [
              {"id": "meta", "kind": "metadata", "title": "Eternum"},
              {"id": "watch", "kind": "toggle", "label": "Watch this thread", "value": true, "call": {"op": "setWatched", "args": {"thread": "93340"}}},
              {"id": "rate", "kind": "action", "label": "Rate it", "confirm": "confirm", "call": {"op": "rate", "kind": "job"}},
              {"id": "gone", "kind": "notice", "title": "The thread was moved", "tone": "caution"}
            ]}
            """.trimIndent(),
        )
        val record = ItemPageFacets.record(ItemPageFacets.admitted(item, "f95zone", "93340", f95, mutableListOf()), "93340")!!
        assertEquals(listOf("watch", "rate", "gone"), record.controls.map { it.id })
        val watch = record.controls[0]
        assertEquals(dev.droidtop.library.UpdateSources.Control.Kind.TOGGLE, watch.kind)
        assertEquals(true, watch.on)
        // Pressed, the switch sends its new state as a typed value, as any facet input does.
        val args = dev.droidtop.pluginhost.PluginViewCall.args(watch.action!!.args(), mapOf("watch" to "false"), JSONObject())
        assertEquals(false, args.get("value"))
        assertEquals("93340", args.getString("thread"))
        assertEquals(true, record.controls[1].confirm)
        assertNull(record.controls[2].action)
    }

    @Test
    fun `a release in parts is one download carrying every part`() {
        val item = JSONObject(
            """
            {"facets": [{"id": "dl", "kind": "download", "entries": [
              {"section": "Win", "host": "MEGA", "label": "MEGA", "parts": [
                {"label": "Part 1", "url": "https://mega.nz/file/a", "size": 4000000000},
                {"label": "Part 2", "link": "1:MEGA:2", "size": 2000000000},
                {"label": "No address"},
                {"url": "https://mega.nz/file/c"}
              ]}
            ]}]}
            """.trimIndent(),
        )
        val record = ItemPageFacets.record(ItemPageFacets.admitted(item, "f95zone", "93340", f95, mutableListOf()), "93340")!!
        val download = record.downloads.single()
        // A part needs a label and an address or a token; the entry needs neither of its own.
        assertEquals(listOf("Part 1", "Part 2"), download.parts.map { it.label })
        assertNull(download.url)
        assertEquals("https://mega.nz/file/a", download.parts[0].url)
        assertEquals("1:MEGA:2", download.parts[1].link)
        assertEquals(2000000000L, download.parts[1].size)
    }

    @Test
    fun `a mirror listed twice is one download covering both systems (tracker 838)`() {
        fun d(url: String?, systems: List<String>, link: String = "", host: String = "MEGA") =
            dev.droidtop.library.UpdateSources.Download("Win/Linux", host, host, link, url, systems)
        val list = ItemPageFacets.distinctDownloads(
            listOf(
                d("https://mega.nz/a", listOf("windows")),
                d("https://gofile.io/b", listOf("windows")),
                d("https://mega.nz/a", listOf("linux")),
                d(null, emptyList(), link = "0:MEGA"),
                d(null, emptyList(), link = "0:MEGA"),
                d(null, emptyList(), link = "1:MEGA"),
            ),
        )
        assertEquals(listOf("https://mega.nz/a", "https://gofile.io/b", null, null), list.map { it.url })
        assertEquals(listOf("windows", "linux"), list[0].systems)
        assertEquals(listOf("0:MEGA", "1:MEGA"), list.drop(2).map { it.link })
    }
}
