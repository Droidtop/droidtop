package dev.droidtop.library.saves

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class SaveTemplatesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun write(path: String, body: String = "x"): File =
        File(temp.root, path).also { it.parentFile?.mkdirs() }.also { it.writeText(body) }

    @Test
    fun `split keeps the token and the path and refuses anything that climbs out`() {
        val split = SaveTemplates.split("<winAppData>/RenPy/Game-1/*.save")!!
        assertEquals("<winAppData>", split.token)
        assertEquals(listOf("RenPy", "Game-1", "*.save"), split.parts)
        assertEquals(emptyList<String>(), SaveTemplates.split("<base>")!!.parts)
        assertNull(SaveTemplates.split("<base>/../x"))
        assertNull(SaveTemplates.split("/etc/passwd"))
        assertNull(SaveTemplates.split("<baseX>/a"))
        assertEquals("<emuSaves>", SaveTemplates.split("<emuSaves>/a.srm")!!.token)
        assertEquals("<winLocalAppDataLow>", SaveTemplates.split("<winLocalAppDataLow>/a")!!.token)
    }

    @Test
    fun `glob matches star and question mark without case`() {
        assertTrue(SaveTemplates.glob("Save??.lsd", "SAVE01.LSD"))
        assertTrue(SaveTemplates.glob("*.rpgsave", "file1.rpgsave"))
        assertFalse(SaveTemplates.glob("Save??.lsd", "Save1.lsd"))
        assertTrue(SaveTemplates.glob("a*b*c", "aXXbYYc"))
        assertFalse(SaveTemplates.glob("a*b", "ac"))
    }

    @Test
    fun `collect finds a file by pattern with its place and time`() {
        val game = write("game/Save01.lsd").parentFile!!
        write("game/Save02.lsd")
        write("game/readme.txt")
        game.listFiles()!!.first { it.name == "Save01.lsd" }.setLastModified(1_700_000_000_000L)
        val scan = SaveTemplates.collect(listOf("<base>/Save??.lsd"), mapOf("<base>" to listOf(game)))
        assertEquals(listOf("<base>/Save01.lsd", "<base>/Save02.lsd"), scan.files.map { it.name })
        assertEquals(game, scan.files.first().root)
        assertEquals(1_700_000_000_000L, scan.files.first().modifiedMs)
        assertEquals(2L, scan.bytes)
        assertFalse(scan.truncated)
    }

    @Test
    fun `a folder pattern takes everything under it and a missing root is skipped`() {
        write("g/savedata/a.ksd")
        write("g/savedata/deep/b.ksd")
        val scan = SaveTemplates.collect(
            listOf("<base>/savedata/", "<home>/nothing"),
            mapOf("<base>" to listOf(File(temp.root, "g")), "<home>" to listOf(File(temp.root, "missing"))),
        )
        assertEquals(setOf("<base>/savedata/a.ksd", "<base>/savedata/deep/b.ksd"), scan.files.map { it.name }.toSet())
    }

    @Test
    fun `names are matched without case and several roots are all searched`() {
        write("one/Save/A.rvdata2")
        write("two/save/b.rvdata2")
        val scan = SaveTemplates.collect(
            listOf("<emuSaves>/SAVE/*.rvdata2"),
            mapOf("<emuSaves>" to listOf(File(temp.root, "one"), File(temp.root, "two"))),
        )
        assertEquals(2, scan.files.size)
    }

    @Test
    fun `a link is never followed`() {
        val outside = write("outside/secret.sav").parentFile!!
        val game = File(temp.root, "game").also { it.mkdirs() }
        val linked = runCatching { Files.createSymbolicLink(File(game, "saves").toPath(), outside.toPath()) }.isSuccess
        if (!linked) return // a file system without links has nothing to plant
        val scan = SaveTemplates.collect(listOf("<base>/saves/"), mapOf("<base>" to listOf(game)))
        assertTrue(scan.files.isEmpty())
    }

    @Test
    fun `the number of files is capped`() {
        val dir = File(temp.root, "many").also { it.mkdirs() }
        repeat(SaveTemplates.MAX_FILES + 5) { File(dir, "f$it.sav").writeText("") }
        val scan = SaveTemplates.collect(listOf("<base>/"), mapOf("<base>" to listOf(dir)))
        assertEquals(SaveTemplates.MAX_FILES, scan.files.size)
        assertTrue(scan.truncated)
    }

    @Test
    fun `fill takes the first alternative that has a value and refuses a climbing one`() {
        assertEquals("a/b", SaveTemplates.fill("a/{x|y}", mapOf("x" to null, "y" to "b")))
        assertNull(SaveTemplates.fill("a/{x}", mapOf("x" to null)))
        assertNull(SaveTemplates.fill("a/{x}", mapOf("x" to "../b")))
        assertEquals("a/b?c", SaveTemplates.fill("a/{x}", mapOf("x" to "b*c")))
        assertEquals(listOf("save_directory", "game_folder"), SaveTemplates.placeholders("{save_directory|game_folder}/x"))
        assertNotNull(SaveTemplates.fill("no placeholders", emptyMap()))
    }
}
