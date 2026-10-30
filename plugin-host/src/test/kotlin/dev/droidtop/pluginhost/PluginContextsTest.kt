package dev.droidtop.pluginhost

import dev.droidtop.net.peer.Computer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Syncable data, droidtop's side (docs/plugin-api.md F8 protocol 2, Droidtop/tracker#794): a published copy is made
 * current whole, a sync's merged copy replaces it whole, and nothing that fails changes anything.
 */
class PluginContextsTest {
    private val root: File = Files.createTempDirectory("contexts794").toFile()
    private val pc = Computer("ab".repeat(32), "Desk PC", listOf("192.168.1.20:47610"), pairedAtMs = 1L)
    private val calls = mutableListOf<Pair<String, JSONObject>>()
    private var reply: (JSONObject) -> JSONObject = { JSONObject() }
    private val received = mutableListOf<Long>()

    private val contexts = PluginContexts(
        root = { root },
        computers = { listOf(pc) },
        call = { _, op, args -> calls += op to args; reply(args) },
        noteSync = { _, _ -> },
    )

    init {
        PluginContexts.onReceived = { _, _, generation -> received += generation }
    }

    @After
    fun tearDown() {
        PluginContexts.onReceived = null
        root.deleteRecursively()
    }

    private val decl = JSONObject().put("id", "f95checker").put("presence", "both")
        .put("fields", JSONArray().put(JSONObject().put("name", "installed").put("direction", "both").put("rule", "ask")))

    private fun records(vararg pairs: Pair<String, String>) =
        JSONObject().apply { pairs.forEach { (k, v) -> put(k, JSONObject().put("installed", v)) } }

    private fun copyFile() = File(root, "droidtop.f95/contexts/f95checker.copy.json")

    @Test
    fun `a published copy is current whole, and each publish is the next generation`() {
        contexts.open("droidtop.f95", "f95checker", decl)
        assertNull(contexts.copy("droidtop.f95", "f95checker"))
        assertEquals(1L, contexts.publish("droidtop.f95", "f95checker", records("1" to "0.5", "2" to "1.0")).generation)
        val second = contexts.publish("droidtop.f95", "f95checker", records("1" to "0.6"))
        assertEquals(2L, second.generation)
        val read = contexts.copy("droidtop.f95", "f95checker")!!
        assertEquals("a publish replaces the copy, it does not add to it", setOf("1"), read.records.keys().asSequence().toSet())
        // Written beside it and renamed: nothing half-written is left next to it.
        assertEquals(listOf("f95checker.copy.json", "f95checker.json"), copyFile().parentFile!!.list()!!.sorted())
    }

    @Test
    fun `a copy that cannot be read is no copy, never half of one`() {
        contexts.open("droidtop.f95", "f95checker", decl)
        contexts.publish("droidtop.f95", "f95checker", records("1" to "0.5"))
        copyFile().writeText("{\"generation\": 3, \"records\": {\"1\":")
        assertNull(contexts.copy("droidtop.f95", "f95checker"))
    }

    @Test
    fun `a published context syncs by copies, and a merged copy that differs becomes current and is told`() {
        contexts.open("droidtop.f95", "f95checker", decl)
        contexts.publish("droidtop.f95", "f95checker", records("1" to "0.5"))
        // The computer's copy had another game: the merged copy holds both.
        reply = { JSONObject().put("device", records("1" to "0.5", "2" to "1.0")).put("baseline", records("1" to "0.5", "2" to "1.0")).put("sent", 1).put("generation", 7) }
        val out = contexts.sync("droidtop.f95", "f95checker")
        assertEquals("sync_context_copy", calls.single().first)
        assertEquals("the published copy is what is sent", "0.5", calls.single().second.getJSONObject("device").getJSONObject("1").getString("installed"))
        val copy = contexts.copy("droidtop.f95", "f95checker")!!
        assertEquals(2L, copy.generation)
        assertEquals(setOf("1", "2"), copy.records.keys().asSequence().toSet())
        assertEquals(listOf(2L), received)
        assertEquals(2L, out.getLong("generation"))
        assertTrue(out.getJSONArray("lines").getString(0).startsWith("Desk PC: in step"))
    }

    @Test
    fun `a sync that changes nothing here tells the plugin nothing`() {
        contexts.open("droidtop.f95", "f95checker", decl)
        contexts.publish("droidtop.f95", "f95checker", records("1" to "0.5"))
        reply = { JSONObject().put("device", records("1" to "0.5")).put("baseline", records("1" to "0.5")).put("sent", 1) }
        contexts.sync("droidtop.f95", "f95checker")
        assertEquals(1L, contexts.copy("droidtop.f95", "f95checker")!!.generation)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `a failed or stale sync leaves the copy and the baseline as they were`() {
        contexts.open("droidtop.f95", "f95checker", decl)
        contexts.publish("droidtop.f95", "f95checker", records("1" to "0.5"))
        val before = File(copyFile().parentFile, "f95checker.${pc.id}.baseline.json")
        reply = { JSONObject().put("error", "the computer's copy of f95checker kept changing (stale); it is left for the next sync") }
        val out = contexts.sync("droidtop.f95", "f95checker")
        assertEquals(1L, contexts.copy("droidtop.f95", "f95checker")!!.generation)
        assertTrue("no baseline was written", !before.exists())
        assertTrue(out.getJSONArray("lines").getString(0).contains("left for the next sync"))
        assertTrue(received.isEmpty())
    }

    @Test
    fun `an agent library without the copy sync says so in plain words`() {
        contexts.open("droidtop.f95", "f95checker", decl)
        contexts.publish("droidtop.f95", "f95checker", records("1" to "0.5"))
        reply = { JSONObject().put("error", "unknown op sync_context_copy") }
        val line = contexts.sync("droidtop.f95", "f95checker").getJSONArray("lines").getString(0)
        assertEquals("Desk PC: this droidtop's sync library is older than this context's sync; update droidtop", line)
    }

    @Test
    fun `a context never published keeps protocol 1`() {
        contexts.open("droidtop.f95", "f95checker", decl)
        contexts.put("droidtop.f95", "f95checker", "1", JSONObject().put("installed", "0.5"))
        reply = { JSONObject().put("device", records("1" to "0.5")).put("baseline", records("1" to "0.5")) }
        contexts.sync("droidtop.f95", "f95checker")
        assertEquals("sync_context", calls.single().first)
        assertNull(contexts.copy("droidtop.f95", "f95checker"))
    }

    @Test
    fun `two copies compare by what they hold, not by key order`() {
        val a = JSONObject().put("x", 1).put("y", JSONObject().put("b", "2").put("a", JSONObject.NULL))
        val b = JSONObject().put("y", JSONObject().put("a", JSONObject.NULL).put("b", "2")).put("x", 1)
        assertEquals(PluginContexts.canonical(a), PluginContexts.canonical(b))
    }
}
