package dev.droidtop.pluginhost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The media library for plugins (docs/plugin-api.md 3 A9, Droidtop/tracker#714): ids, kinds, paging and the answer's shape. */
class MediaApiTest {
    @Test
    fun `an item's id is its kind and Android's row, and nothing else reads as one`() {
        assertEquals(MediaId(MediaKind.IMAGE, 123), MediaId.parse("image:123"))
        assertEquals("audio:7", MediaId(MediaKind.AUDIO, 7).toString())
        for (bad in listOf(null, "", "image", "image:", "image:-1", "image:abc", "file:3", "/sdcard/x.png")) assertNull(bad, MediaId.parse(bad))
    }

    @Test
    fun `a kind is one of the four, and a page is 1 to 100 items`() {
        assertEquals(MediaKind.VIDEO, HostMediaApis.kind(JSONObject().put("kind", "video")))
        try {
            HostMediaApis.kind(JSONObject().put("kind", "contacts"))
            fail("contacts is no media kind")
        } catch (e: BrokerException) {
            assertEquals(PluginErrorCode.INVALID_ARGS, e.code)
        }
        assertEquals(0 to 50, HostMediaApis.paging(JSONObject()))
        assertEquals(3 to 100, HostMediaApis.paging(JSONObject().put("page", 3).put("size", 5000)))
        assertEquals(0 to 1, HostMediaApis.paging(JSONObject().put("size", 0)))
    }

    @Test
    fun `an item reads as its id, name, type, size and time, with only the facts it has`() {
        val song = HostMediaApis.itemJson(MediaLibrary.Item(MediaId(MediaKind.AUDIO, 5), "song.mp3", "audio/mpeg", 4000, 1_000L, durationMs = 180_000, title = "Song", artist = "Band"))
        assertEquals("audio:5", song.getString("id"))
        assertEquals(180_000L, song.getLong("durationMs"))
        assertEquals("Band", song.getString("artist"))
        assertFalse(song.has("width"))
        val picture = HostMediaApis.itemJson(MediaLibrary.Item(MediaId(MediaKind.IMAGE, 6), "a.png", null, 10, 2_000L, width = 64, height = 32))
        assertTrue(picture.isNull("mime"))
        assertEquals(64, picture.getInt("width"))
    }

    @Test
    fun `both ops need media read, a dangerous permission`() {
        assertEquals(PermissionTier.DANGEROUS, PluginPermissions.all.first { it.id == HostMediaApis.PERMISSION }.tier)
        assertTrue(HostMediaApis.ops.all { it.permission == HostMediaApis.PERMISSION })
        assertEquals(listOf("query", "open"), HostMediaApis.ops.map { it.op })
    }
}
