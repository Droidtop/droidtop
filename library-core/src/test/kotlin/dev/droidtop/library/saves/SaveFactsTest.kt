package dev.droidtop.library.saves

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SaveFactsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun file(path: String, body: String): File =
        File(temp.root, path).also { it.parentFile?.mkdirs() }.also { it.writeText(body) }

    /** An "RENPY RPC2" container: a slot table, then slot 1 as a zlib stream of [script]. */
    private fun rpyc(script: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(script)
        deflater.finish()
        val packed = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) packed.write(buffer, 0, deflater.deflate(buffer))
        val header = "RENPY RPC2".toByteArray(Charsets.ISO_8859_1)
        val table = 12 * 2
        val out = ByteArrayOutputStream()
        out.write(header)
        fun le(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        le(1); le(header.size + table); le(packed.size())
        le(0); le(0); le(0)
        out.write(packed.toByteArray())
        return out.toByteArray()
    }

    @Test
    fun `renpy options define the save directory`() {
        assertEquals("Game-1637555449", SaveFacts.renpyOptions("define config.name = _(\"G\")\ndefine config.save_directory = \"Game-1637555449\"\n"))
        assertEquals("x", SaveFacts.renpyOptions("    config.save_directory = 'x'"))
        assertNull(SaveFacts.renpyOptions("define config.save_directory = None"))
        assertNull(SaveFacts.renpyOptions("# define config.save_directory = \"old\"\n"))
        assertNull(SaveFacts.renpyOptions("define config.name = \"x\""))
    }

    @Test
    fun `a compiled options file gives the directory Ren'Py's own project creation writes`() {
        val script = "junk\u0000\"gui/window_icon.png\"junk save_directory store.config \u0000\"BareWitness-1615628163\"more".toByteArray(Charsets.ISO_8859_1)
        assertEquals("BareWitness-1615628163", SaveFacts.renpyCompiled(rpyc(script)))
    }

    @Test
    fun `a compiled file with no such name, or damaged, gives nothing and is never guessed`() {
        assertNull(SaveFacts.renpyCompiled(rpyc("\"Name-123\" without the key".toByteArray())))
        assertNull(SaveFacts.renpyCompiled("not a renpy file at all".toByteArray()))
        assertNull(SaveFacts.renpyCompiled(rpyc("save_directory \"far\"".toByteArray())))
        val broken = rpyc("save_directory \"Name-1\"".toByteArray())
        assertNull(SaveFacts.renpyCompiled(broken.copyOf(broken.size - 6)))
    }

    @Test
    fun `reading goes options first and then the compiled file in the game folder`() {
        val game = File(temp.root, "g1").also { it.mkdirs() }
        File(game, "game").mkdirs()
        File(game, "game/options.rpyc").writeBytes(rpyc("save_directory \"Compiled-42\"".toByteArray()))
        assertEquals("Compiled-42", SaveFacts.read(SaveFacts.RENPY_SAVE_DIRECTORY, game))
        File(game, "game/options.rpy").writeText("define config.save_directory = \"Text-7\"")
        assertEquals("Text-7", SaveFacts.read(SaveFacts.RENPY_SAVE_DIRECTORY, game))
        assertNull(SaveFacts.read(SaveFacts.RENPY_SAVE_DIRECTORY, File(temp.root, "none")))
    }

    @Test
    fun `godot names the folder as the engine does`() {
        val plain = SaveFacts.godotProject("[application]\nconfig/name=\"My: Game\"\n[other]\nconfig/name=\"no\"\n")
        assertEquals(mapOf(SaveFacts.GODOT_NAME to "My- Game"), plain)
        val custom = SaveFacts.godotProject("[application]\nconfig/name=\"G\"\nconfig/use_custom_user_dir=true\nconfig/custom_user_dir_name=\"studio/g\"\n")
        assertEquals("G", custom[SaveFacts.GODOT_NAME])
        assertEquals("studio/g", custom[SaveFacts.GODOT_CUSTOM_DIR])
        val fallback = SaveFacts.godotProject("[application]\nconfig/name=\"G\"\nconfig/use_custom_user_dir=true\n")
        assertEquals("G", fallback[SaveFacts.GODOT_CUSTOM_DIR])
        assertEquals(emptyMap<String, String>(), SaveFacts.godotProject("[application]\n"))
        assertEquals("dot", SaveFacts.godotSafeDirName(".", false))
    }

    @Test
    fun `love identity comes from conf lua, in the folder or in a love file`() {
        assertEquals("mygame", SaveFacts.loveConf("function love.conf(t)\n  t.identity = \"mygame\"\nend"))
        assertNull(SaveFacts.loveConf("  -- t.identity = \"commented\"\n"))
        val folder = File(temp.root, "lv").also { it.mkdirs() }
        File(folder, "conf.lua").writeText("t.identity = 'plain'")
        assertEquals("plain", SaveFacts.read(SaveFacts.LOVE_IDENTITY, folder))
        val packed = File(temp.root, "lv2").also { it.mkdirs() }
        ZipOutputStream(File(packed, "game.love").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("conf.lua"))
            zip.write("t.identity = \"zipped\"".toByteArray())
            zip.closeEntry()
        }
        assertEquals("zipped", SaveFacts.read(SaveFacts.LOVE_IDENTITY, packed))
        file("lv3/readme.txt", "x")
        assertNull(SaveFacts.read(SaveFacts.LOVE_IDENTITY, File(temp.root, "lv3")))
    }
}
