package dev.droidtop.pluginhost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `hostOpen`'s paths for a Flutter plugin (docs/plugin-api.md 5.3, Droidtop/tracker#375). */
class FlutterOpenFilesTest {
    @Test
    fun aSlotIsTakenOnceAndFreedByItsPath() {
        val files = FlutterOpenFiles()
        val first = files.take()!!
        val second = files.take()!!
        assertTrue(first != second)
        assertEquals("/droidtop-sandbox/open/$first", FlutterOpenFiles.pathOf(first))
        assertTrue(files.free(FlutterOpenFiles.pathOf(first)))
        assertFalse("a slot is freed once", files.free(FlutterOpenFiles.pathOf(first)))
        assertEquals("a freed slot is taken again", first, files.take())
    }

    @Test
    fun atMostSixtyFourAtATime() {
        val files = FlutterOpenFiles()
        repeat(FlutterOpenFiles.MAX) { assertTrue(files.take() != null) }
        assertNull(files.take())
        files.free(FlutterOpenFiles.pathOf(7))
        assertEquals(7, files.take())
    }

    @Test
    fun onlyItsOwnPathsAreSlots() {
        val files = FlutterOpenFiles()
        files.take()
        assertNull(FlutterOpenFiles.slotOf("/droidtop-sandbox/plugin/0"))
        assertNull(FlutterOpenFiles.slotOf("/droidtop-sandbox/open/64"))
        assertNull(FlutterOpenFiles.slotOf("/droidtop-sandbox/open/x"))
        assertFalse(files.free("/droidtop-sandbox/plugin/0"))
        assertEquals(0, FlutterOpenFiles.slotOf("/droidtop-sandbox/open/0"))
    }

    @Test
    fun theReplyCarriesThePathBesideWhatTheBrokerSaid() {
        val reply = JSONObject().put("ok", true).put("data", JSONObject().put("name", "save.dat")).toString()
        val data = JSONObject(FlutterOpenFiles.withPath(reply, "/droidtop-sandbox/open/3")).getJSONObject("data")
        assertEquals("/droidtop-sandbox/open/3", data.getString("path"))
        assertEquals("save.dat", data.getString("name"))
        val bare = JSONObject(FlutterOpenFiles.withPath("""{"ok":true}""", "/p")).getJSONObject("data")
        assertEquals("/p", bare.getString("path"))
    }
}
