package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Data kinds (docs/plugin-api.md 3 H3, Droidtop/tracker#793): a copy carries files beside its records, written in
 * droidtop's staging folder, checked against their size and SHA-256, and made current with the records in one
 * rename; droidtop moves a whole copy to an equivalent plugin and only reads the source.
 */
class DataKindTransferTest {
    private val root: File = Files.createTempDirectory("datakinds793").toFile()
    private val contexts = PluginContexts(root = { root }, computers = { emptyList() }, call = { _, _, _ -> JSONObject() }, noteSync = { _, _ -> })
    private val kind = "syncthing.state"
    private val decl = JSONObject().put("id", kind).put("fields", JSONArray()).put("presence", "both")

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun stage(plugin: String, name: String, bytes: ByteArray): PluginContexts.CopyFile {
        val file = contexts.stagingFile(plugin, kind, name)
        file.writeBytes(bytes)
        return PluginContexts.CopyFile(name, bytes.size.toLong(), PluginContexts.sha256(file))
    }

    private fun records() = JSONObject().put("device", JSONObject().put("id", "ABCDEF-123456"))

    @Test
    fun aPublishedCopyCarriesItsFilesAndTheyAreReadBack() {
        contexts.open("fork", kind, decl)
        val key = stage("fork", "key.pem", "secret".toByteArray())
        val index = stage("fork", "index-v2", ByteArray(200_000) { (it % 251).toByte() })
        val copy = contexts.publish("fork", kind, records(), listOf(key, index))
        assertEquals(1L, copy.generation)
        assertEquals(listOf("key.pem", "index-v2"), contexts.copy("fork", kind)!!.files.map { it.name })
        assertEquals("secret", contexts.copyFileOf("fork", kind, "key.pem")!!.readText())
        assertEquals(200_000L, contexts.copyFileOf("fork", kind, "index-v2")!!.length())
        assertNull(contexts.copyFileOf("fork", kind, "other"))
    }

    @Test
    fun aFileThatIsNotWhatWasDeclaredRefusesTheWholeCopy() {
        contexts.open("fork", kind, decl)
        contexts.publish("fork", kind, records())
        val key = stage("fork", "key.pem", "secret".toByteArray())
        try {
            contexts.publish("fork", kind, records(), listOf(key.copy(sha256 = "0".repeat(64))))
            fail("a file with another SHA-256 was taken")
        } catch (e: BrokerException) {
            assertEquals(PluginErrorCode.INVALID_ARGS, e.code)
        }
        try {
            contexts.publish("fork", kind, records(), listOf(PluginContexts.CopyFile("never-written", 1, "a".repeat(64))))
            fail("a file never written was taken")
        } catch (e: BrokerException) {
            assertEquals(PluginErrorCode.INVALID_ARGS, e.code)
        }
        // Nothing changed: still the first copy, with no files.
        assertEquals(1L, contexts.copy("fork", kind)!!.generation)
        assertTrue(contexts.copy("fork", kind)!!.files.isEmpty())
    }

    @Test
    fun aNameThatIsNotOnePlainFileIsRefused() {
        contexts.open("fork", kind, decl)
        for (bad in listOf("../key.pem", ".hidden", "a/b", "")) {
            try {
                contexts.stagingFile("fork", kind, bad)
                fail("$bad was taken as a file name")
            } catch (e: BrokerException) {
                assertEquals(PluginErrorCode.INVALID_ARGS, e.code)
            }
        }
    }

    @Test
    fun aTransferGivesTheReceiverTheWholeCopyAndLeavesTheSourceAsItWas() {
        contexts.open("fork", kind, decl)
        val cert = stage("fork", "cert.pem", "cert".toByteArray())
        contexts.publish("fork", kind, records(), listOf(cert))
        val sourceBefore = contexts.copy("fork", kind)!!

        val taken = contexts.transfer("fork", "native", kind)
        assertNotNull(taken)
        assertEquals(1L, taken!!.generation)
        assertEquals(records().toString(), contexts.copy("native", kind)!!.records.toString())
        assertEquals("cert", contexts.copyFileOf("native", kind, "cert.pem")!!.readText())

        // The source: the same copy, the same file, untouched.
        val sourceAfter = contexts.copy("fork", kind)!!
        assertEquals(sourceBefore.generation, sourceAfter.generation)
        assertEquals(sourceBefore.records.toString(), sourceAfter.records.toString())
        assertEquals(sourceBefore.files, sourceAfter.files)
        assertEquals("cert", contexts.copyFileOf("fork", kind, "cert.pem")!!.readText())
        // A second transfer is the receiver's next copy.
        assertEquals(2L, contexts.transfer("fork", "native", kind)!!.generation)
    }

    @Test
    fun aSourceWithNoCopyTransfersNothing() {
        assertNull(contexts.transfer("fork", "native", kind))
        assertNull(contexts.copy("native", kind))
    }

    @Test
    fun onlyTheCurrentFilesetAndTheOneBeforeAreKept() {
        contexts.open("fork", kind, decl)
        repeat(4) { n -> contexts.publish("fork", kind, records(), listOf(stage("fork", "cert.pem", "v$n".toByteArray()))) }
        val sets = File(root, "fork/contexts/$kind.files").list()!!.sorted()
        assertEquals(2, sets.size)
        assertEquals("v3", contexts.copyFileOf("fork", kind, "cert.pem")!!.readText())
    }

    @Test
    fun equivalentKindsAreTheSameKindAndVersionFromAnExporterToAnImporter() {
        val export = DataKind(kind, 1, export = true, import = false)
        val import = DataKind(kind, 1, export = false, import = true)
        assertTrue(export.feeds(import))
        assertFalse(import.feeds(export))
        assertFalse(export.feeds(import.copy(version = 2)))
        assertNull(DataKind.fromJson(JSONObject().put("kind", "NotAKind").put("version", 1)))
        assertNull(DataKind.fromJson(JSONObject().put("kind", kind).put("version", 0)))
        assertEquals(export, DataKind.fromJson(export.toJson()))
    }
}
