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
}
