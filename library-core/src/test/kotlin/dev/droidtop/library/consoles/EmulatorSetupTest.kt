package dev.droidtop.library.consoles

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The emulator setup helper's pure parts (Droidtop/tracker#248): changing one key of an emulator's config without
 * touching the rest, finding the BIOS folder, naming a picked BIOS file, and the players-database `setup` object.
 */
class EmulatorSetupTest {
    private val cfg = "# comment = \"x\"\nvideo_scale_integer = \"false\"\npause_nonactive = \"true\"\nsystem_directory = \"/storage/emulated/0/RetroArch/system\"\n"

    @Test
    fun `a key-value file reads and replaces one key, keeping every other line`() {
        assertEquals("true", ConfigText.get(cfg, ConfigFormat.KEY_VALUE, null, "pause_nonactive"))
        assertNull(ConfigText.get(cfg, ConfigFormat.KEY_VALUE, null, "pause"))
        val changed = ConfigText.set(cfg, ConfigFormat.KEY_VALUE, null, "pause_nonactive", "false")
        assertEquals(cfg.replace("pause_nonactive = \"true\"", "pause_nonactive = \"false\""), changed)
    }

    @Test
    fun `a key-value file gains a key it did not have, at the end`() {
        val changed = ConfigText.set(cfg, ConfigFormat.KEY_VALUE, null, "network_cmd_enable", "true")
        assertEquals(cfg + "network_cmd_enable = \"true\"\n", changed)
        assertEquals("a = \"1\"\n", ConfigText.set("", ConfigFormat.KEY_VALUE, null, "a", "1"))
    }

    @Test
    fun `an ini file changes the key in its own section only`() {
        val ini = "[A]\nkey = 1\n\n[B]\nkey = 2\n"
        assertEquals("2", ConfigText.get(ini, ConfigFormat.INI, "B", "key"))
        assertEquals("[A]\nkey = 1\n\n[B]\nkey = 3\n", ConfigText.set(ini, ConfigFormat.INI, "B", "key", "3"))
        assertEquals("[A]\nkey = 1\nother = x\n\n[B]\nkey = 2\n", ConfigText.set(ini, ConfigFormat.INI, "A", "other", "x"))
        assertEquals("[A]\nkey = 1\n\n[B]\nkey = 2\n\n[C]\nkey = 4\n", ConfigText.set(ini, ConfigFormat.INI, "C", "key", "4"))
    }

    @Test
    fun `RetroArch's BIOS folder is its config's system_directory, unless it says default`() {
        val spec = EmulatorSetup.retroArch("com.retroarch.aarch64")
        assertEquals("/storage/emulated/0/RetroArch/system", EmulatorSetup.biosFolder(spec, cfg))
        assertNull(EmulatorSetup.biosFolder(spec, "system_directory = \"default\"\n"))
        assertNull(EmulatorSetup.biosFolder(spec, null))
        assertEquals("/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg", spec.config!!.file)
    }

    @Test
    fun `a picked BIOS file takes the database name its md5 matches, else its own`() {
        val bios = SystemBiosSpec("psx", "PlayStation", listOf(BiosFileSpec("bios/scph5501.bin", listOf("490f666e1afb15b7362b406ed1cea246"))))
        assertEquals("scph5501.bin", EmulatorSetup.biosTarget(bios, "my dump.bin", "490F666E1AFB15B7362B406ED1CEA246"))
        assertEquals("scph5501.bin", EmulatorSetup.biosTarget(bios, "SCPH5501.BIN", "00"))
        assertEquals("other.bin", EmulatorSetup.biosTarget(bios, "other.bin", "00"))
        assertEquals("dc/dc_boot.bin", EmulatorSetup.biosRelative("bios/dc/dc_boot.bin"))
    }

    @Test
    fun `a players-database setup object is read with the package in its paths`() {
        val obj = JSONObject(
            """{"biosFolder":"/storage/emulated/0/Android/data/{pkg}/files/bios","biosManual":"Settings > BIOS",
               "config":{"file":"/storage/emulated/0/Android/data/{pkg}/files/x.ini","format":"ini",
               "settings":[{"id":"s","label":"L","section":"UI","key":"k","options":[{"value":"1","label":"On"}],"manual":"Settings > K"}]}}""",
        )
        val spec = EmulatorSetup.parse(obj, "org.example.emu")!!
        assertEquals("/storage/emulated/0/Android/data/org.example.emu/files/bios", spec.biosFolder)
        assertEquals(ConfigFormat.INI, spec.config!!.format)
        assertEquals("UI", spec.config!!.settings.single().section)
        assertNull(EmulatorSetup.parse(null, "x"))
    }

    private val ps2 = SystemBiosSpec("ps2", "PS2", listOf(BiosFileSpec("bios/ps2/ps2-0230a-20080220.bin", listOf("21038400dc633070a78ad53090c53017"))))

    @Test
    fun `a flat BIOS folder takes the file at its top, under the database's base name`() {
        val nether = EmulatorSetup.parse(JSONObject("""{"biosFolder": "/storage/emulated/0/Android/data/{pkg}/files/bios", "biosFlat": true}"""), "xyz.aethersx2.tturnip")!!
        assertEquals("/storage/emulated/0/Android/data/xyz.aethersx2.tturnip/files/bios", EmulatorSetup.biosFolder(nether, null))
        assertEquals("ps2-0230a-20080220.bin", EmulatorSetup.biosTarget(nether, ps2.files.single()))
        // A folder that keeps the database's layout (RetroArch's system folder) keeps the subfolder.
        assertEquals("ps2/ps2-0230a-20080220.bin", EmulatorSetup.biosTarget(EmulatorSetupSpec(biosFolder = "/x"), ps2.files.single()))
        assertEquals(listOf(ps2.files.single()), EmulatorSetup.missingBios(nether, ps2, setOf("ps2/ps2-0230a-20080220.bin")))
        assertEquals(emptyList<BiosFileSpec>(), EmulatorSetup.missingBios(nether, ps2, setOf("PS2-0230A-20080220.BIN")))
    }

    @Test
    fun `droidtop's own BIOS is found in a games folder's bios folder, at the database's path or by name`() {
        val root = java.nio.file.Files.createTempDirectory("games").toFile()
        val bios = java.io.File(root, "bios").apply { mkdirs() }
        assertNull(EmulatorSetup.droidtopBios(listOf(root), ps2.files.single()))
        val flat = java.io.File(bios, "ps2-0230a-20080220.bin").apply { writeBytes(ByteArray(16)) }
        assertEquals(flat, EmulatorSetup.droidtopBios(listOf(root), ps2.files.single()))
        val nested = java.io.File(bios, "ps2/ps2-0230a-20080220.bin").apply { parentFile.mkdirs(); writeBytes(ByteArray(16)) }
        assertEquals(nested, EmulatorSetup.droidtopBios(listOf(root), ps2.files.single()))
        // One file at a time, one folder at a time.
        nested.delete(); nested.parentFile.delete(); flat.delete(); bios.delete(); root.delete()
    }

    @Test
    fun `a launch names the BIOS file still missing and the folder it belongs in`() {
        val sync = EmulatorSetup.BiosSync("/storage/emulated/0/Android/data/xyz.aethersx2.tturnip/files/bios", emptyList(), listOf("ps2-0230a-20080220.bin"))
        val line = biosMissingSentence("NetherSX2", "PlayStation 2", sync)!!
        assertEquals(true, line.contains("ps2-0230a-20080220.bin is not in /storage/emulated/0/Android/data/xyz.aethersx2.tturnip/files/bios"))
        assertNull(biosMissingSentence("NetherSX2", "PlayStation 2", sync.copy(missing = emptyList())))
        // A folder that already holds one of the system's files is not "no BIOS", whatever optional files it lacks.
        assertNull(biosMissingSentence("NooDS", "Nintendo DS", sync.copy(missing = listOf("dsi_bios7.bin"), present = 3)))
        assertNull(biosMissingSentence("NooDS", "Nintendo DS", sync.copy(copied = listOf("bios7.bin"), missing = listOf("dsi_bios7.bin"))))
    }
}
