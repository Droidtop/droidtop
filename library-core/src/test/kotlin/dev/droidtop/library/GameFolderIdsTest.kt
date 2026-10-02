package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameFolderIdsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun game(name: String, vararg files: Pair<String, String>): File {
        val dir = File(temp.root, "games/$name").also { it.mkdirs() }
        val list = if (files.isEmpty()) listOf("Game.exe" to "MZ-$name") else files.toList()
        for ((rel, body) in list) {
            File(dir, rel).also { it.parentFile?.mkdirs() }.writeText(body)
        }
        return dir
    }

    private fun ids(stat: (File) -> String? = { null }, legacy: (File) -> Int? = { null }) =
        GameFolderIds(File(temp.root, "own/ids.tsv"), legacy, stat)

    /** Every file under [root] with its bytes, for a byte-for-byte comparison. */
    private fun snapshot(root: File): Map<String, List<Byte>> =
        root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes().toList() }

    @Test
    fun `remembering and asking again never touches the game folder`() {
        val dir = game("Alpha", "Game.exe" to "MZ", "data/a.pak" to "pak")
        val before = snapshot(File(temp.root, "games"))
        val map = ids()
        assertNull(map.idFor(dir))
        map.remember(dir, 42)
        assertEquals(42, map.idFor(dir))
        assertEquals(before, snapshot(File(temp.root, "games")))
        assertTrue(File(dir, ".gamenative").exists().not())
    }

    @Test
    fun `an id survives a restart because it is in droidtop's own file`() {
        val dir = game("Alpha")
        ids().remember(dir, 7)
        assertEquals(7, ids().idFor(dir))
    }

    @Test
    fun `a renamed folder keeps its id by content shape`() {
        val dir = game("Alpha", "Game.exe" to "MZ", "data/a.pak" to "pak")
        val map = ids()
        map.remember(dir, 99)
        val renamed = File(dir.parentFile, "Alpha (renamed)")
        assertTrue(dir.renameTo(renamed))
        assertEquals(99, ids().idFor(renamed))
    }

    @Test
    fun `a renamed folder keeps its id by filesystem identity even after its files changed`() {
        val inodes = HashMap<String, String>()
        val dir = game("Alpha")
        inodes[dir.absolutePath] = "1:500"
        val map = ids(stat = { f -> inodes[f.absolutePath] })
        map.remember(dir, 5)
        val renamed = File(dir.parentFile, "Alpha 2")
        dir.renameTo(renamed)
        inodes[renamed.absolutePath] = "1:500"
        File(renamed, "Patch.bin").writeText("new")
        assertEquals(5, map.idFor(renamed))
    }

    @Test
    fun `a copy beside the original is a new game, not the original`() {
        val dir = game("Alpha")
        val map = ids()
        map.remember(dir, 1)
        val copy = game("Alpha copy", "Game.exe" to "MZ-Alpha")
        assertNull(map.idFor(copy))
    }

    @Test
    fun `an id left in a gamenative file is adopted by reading, and the file is not written`() {
        val dir = game("Alpha")
        val legacyFile = File(dir, ".gamenative").also { it.writeText("""{"appId":123}""") }
        val bytes = legacyFile.readBytes().toList()
        val map = ids(legacy = { Regex("[0-9]+").find(File(it, ".gamenative").readText())?.value?.toInt() })
        assertEquals(123, map.idFor(dir))
        assertEquals(bytes, legacyFile.readBytes().toList())
        // And it stays with the folder after the file is gone.
        legacyFile.delete()
        assertEquals(123, ids().idFor(dir))
    }

    @Test
    fun `two empty folders are never taken for each other`() {
        val a = File(temp.root, "games/A").also { it.mkdirs() }
        val map = ids()
        map.remember(a, 3)
        val b = File(temp.root, "games/B").also { it.mkdirs() }
        a.deleteRecursively()
        assertNull(map.idFor(b))
    }

    @Test
    fun `paths with tabs and percent signs survive the file`() {
        val dir = File(temp.root, "games/we%ird" + Char(9) + "name").also { it.mkdirs() }
        File(dir, "Game.exe").writeText("MZ")
        ids().remember(dir, 11)
        assertEquals(11, ids().idFor(dir))
    }
}
