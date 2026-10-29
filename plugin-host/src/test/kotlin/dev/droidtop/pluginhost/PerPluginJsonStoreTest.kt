package dev.droidtop.pluginhost

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Droidtop/tracker#766: one per-plugin store, and a corrupt file is set aside, never read as empty and written over. */
class PerPluginJsonStoreTest {
    private val dir: File = Files.createTempDirectory("store").toFile()
    private val store = PerPluginJsonStore(dir, ".counts.json")

    @Test
    fun `a plugin's object is written whole and read back`() {
        assertTrue(store.read("acme.tool") is PerPluginJsonStore.Read.Absent)
        store.write("acme.tool", JSONObject().put("a", 1))
        assertEquals(1, store.readOrSetAside("acme.tool")!!.getInt("a"))
        assertTrue(File(dir, "acme.tool.counts.json").isFile)
    }

    @Test
    fun `a corrupt file is set aside and the plugin starts afresh`() {
        File(dir, "acme.tool.counts.json").writeText("{ not json")
        assertTrue(store.read("acme.tool") is PerPluginJsonStore.Read.Corrupt)
        assertNull(store.readOrSetAside("acme.tool"))
        assertEquals("{ not json", File(dir, "acme.tool.counts.json.corrupt").readText())
        assertFalse(File(dir, "acme.tool.counts.json").exists())
    }

    @Test
    fun `delete takes the file, its set-aside copy and an old temp file`() {
        store.write("acme.tool", JSONObject())
        File(dir, "acme.tool.counts.json.corrupt").writeText("x")
        File(dir, "acme.tool.counts.json.tmp").writeText("x")
        store.delete("acme.tool")
        assertEquals(0, dir.list()!!.size)
    }
}
