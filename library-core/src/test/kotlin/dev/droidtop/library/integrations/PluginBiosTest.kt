package dev.droidtop.library.integrations

import dev.droidtop.library.consoles.BiosFileSpec
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** `emulator.bios@1` (docs/plugin-api.md 3 A11, Droidtop/tracker#415): what a provider's list becomes, and where a downloaded file goes. */
class PluginBiosTest {
    private val dir: File = Files.createTempDirectory("bios").toFile()
    private val md5Of = { bytes: ByteArray -> dev.droidtop.library.consoles.EmulatorSetup.md5(bytes) }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun entry(file: String, vararg md5: String) = BiosFileSpec(file, md5.toList())

    @Test
    fun aListIsReadWithItsLimitsAndBadRowsDropped() {
        val reply = JSONObject(
            """{"files":[
                {"name":"scph5501.bin","md5":["8DD7D5296A650FAC7319BCE665A6A53C","nonsense"],"size":524288,"source":"Internet Archive"},
                {"name":"","md5":[]},
                {"md5":[]},
                {"name":"dc/dc_boot.bin"}
            ]}""",
        )
        val files = PluginBios.parseList(reply)
        assertEquals(listOf("scph5501.bin", "dc/dc_boot.bin"), files.map { it.name })
        assertEquals(listOf("8dd7d5296a650fac7319bce665a6a53c"), files[0].md5)
        assertEquals(524288L, files[0].size)
        assertEquals("Internet Archive", files[0].source)
        assertEquals(emptyList<String>(), files[1].md5)
        assertEquals(emptyList<PluginBios.Candidate>(), PluginBios.parseList(JSONObject("{}")))
    }

    @Test
    fun aFileIsMatchedToTheDatabaseEntryByMd5FirstThenByName() {
        val needed = listOf(
            entry("bios/scph5501.bin", "8dd7d5296a650fac7319bce665a6a53c"),
            entry("bios/dc/dc_boot.bin", "e10c53c2f8b90bab96ead2d368858623"),
            entry("bios/gba_bios.bin"),
        )
        val candidates = parse(
            """{"files":[
                {"name":"renamed.bin","md5":["8dd7d5296a650fac7319bce665a6a53c"]},
                {"name":"DC_BOOT.BIN"},
                {"name":"unrelated.bin"}
            ]}""",
        )
        val offers = PluginBios.offers(needed, candidates)
        assertEquals(listOf("bios/scph5501.bin", "bios/dc/dc_boot.bin"), offers.map { it.entry.file })
        assertEquals("renamed.bin", offers[0].candidate.name)
        assertEquals("DC_BOOT.BIN", offers[1].candidate.name)
    }

    private fun parse(json: String) = PluginBios.parseList(JSONObject(json))

    @Test
    fun aDownloadedFileThatMatchesIsWrittenIntoTheBiosFolderUnderTheDatabasesName() {
        val bytes = "bios".toByteArray()
        val download = File(dir, "downloads/bios_1").also { it.parentFile.mkdirs(); it.writeBytes(bytes) }
        val written = mutableMapOf<String, ByteArray>()
        val line = PluginBios.placeDownloaded(
            download,
            mapOf("biosFolder" to "/emu/bios", "biosTarget" to "dc/dc_boot.bin", "biosMd5s" to md5Of(bytes), "biosApp" to "Flycast"),
        ) { path, data -> written[path] = data; true }
        assertEquals(setOf("/emu/bios/dc/dc_boot.bin"), written.keys)
        assertEquals("Added dc/dc_boot.bin to Flycast's BIOS folder", line)
    }

    @Test
    fun aFileNoDatabaseDumpMatchesIsNeverWrittenAndStaysDownloaded() {
        val download = File(dir, "downloads/bios_2").also { it.parentFile.mkdirs(); it.writeText("something else") }
        var wrote = false
        try {
            PluginBios.placeDownloaded(
                download,
                mapOf("biosFolder" to "/emu/bios", "biosTarget" to "scph5501.bin", "biosMd5s" to "8dd7d5296a650fac7319bce665a6a53c"),
            ) { _, _ -> wrote = true; true }
            fail("a dump the database does not list must be refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("kept at ${download.absolutePath}"))
        }
        assertFalse(wrote)
        assertTrue(download.exists())
    }

    @Test
    fun aFailedWriteSaysWhereTheDownloadIs() {
        val download = File(dir, "downloads/bios_3").also { it.parentFile.mkdirs(); it.writeText("x") }
        try {
            PluginBios.placeDownloaded(download, mapOf("biosFolder" to "/emu/bios", "biosTarget" to "a.bin")) { _, _ -> false }
            fail("a failed write must fail the job")
        } catch (e: IllegalStateException) {
            assertEquals("droidtop could not write to /emu/bios. The download is kept at ${download.absolutePath}", e.message)
        }
        assertTrue(download.exists())
    }
}
