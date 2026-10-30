package dev.droidtop.library.saves

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Saves between a game's copies (docs/SPEC.md 7o, "Saves between copies", Droidtop/tracker#745): only the files a
 * save rule matched move, the target is backed up first and can be put back, and the source is only read.
 */
class SaveCopiesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun file(path: String, text: String, modified: Long = 1_000_000L): File =
        File(temp.root, path).apply {
            parentFile.mkdirs()
            writeText(text)
            setLastModified(modified)
        }

    private fun side(id: String, vararg files: File, newest: Long = 0L) =
        SaveCopies.sideOf(id, SaveStatus.Found(listOf(FoundPlace("p", temp.root.path, files.size, 0, newest, files.map { it.path })), emptyList(), emptyList(), false))!!

    @Test
    fun `a copy's saves are the folder its matched files share, and nothing else in it`() {
        val a = file("steam/AppData/Roaming/RenPy/BW-1/1-1-LT1.save", "a")
        val b = file("steam/AppData/Roaming/RenPy/BW-1/persistent", "p")
        val s = side("steam:1", a, b)
        assertEquals(File(temp.root, "steam/AppData/Roaming/RenPy/BW-1"), s.folder)
        assertEquals(listOf(a, b), s.files)
        assertNull(SaveCopies.sideOf("x", SaveStatus.NoneFound(listOf("p"))))
    }

    @Test
    fun `the plan keeps each file's path below its folder and names what is replaced`() {
        val from = side("folder", file("own/game/saves/1-1-LT1.save", "new"), file("own/game/saves/2-1-LT1.save", "new2"))
        val to = side("steam:1", file("steam/RenPy/BW-1/1-1-LT1.save", "old"))
        val plan = SaveCopies.plan(from, to)
        assertEquals(
            listOf(File(to.folder, "1-1-LT1.save"), File(to.folder, "2-1-LT1.save")),
            plan.copies.map { it.second },
        )
        assertEquals(listOf(File(to.folder, "1-1-LT1.save")), plan.replaced)
    }

    @Test
    fun `copying backs the target up, leaves the source alone, and a backup puts it back`() = runBlocking {
        val source1 = file("own/game/saves/1-1-LT1.save", "from own", modified = 5_000_000L)
        val source2 = file("own/game/saves/2-1-LT1.save", "only in own", modified = 6_000_000L)
        val target1 = file("steam/RenPy/BW-1/1-1-LT1.save", "steam's own")
        val from = side("folder", source1, source2)
        val to = side("steam:1", target1)
        val backups = File(temp.root, "backups")

        assertEquals(2, SaveCopies.copy(backups, from, to))

        assertEquals("from own", target1.readText())
        assertEquals("only in own", File(to.folder, "2-1-LT1.save").readText())
        assertEquals("from own", source1.readText())
        val backup = SaveCopies.backups(backups, "steam:1").single()
        assertEquals(1, backup.files)
        assertEquals(to.folder, backup.folder)

        assertEquals(1, SaveCopies.restore(backup))
        assertEquals("steam's own", target1.readText())
        // The save the copy added, untouched since, is gone again.
        assertFalse(File(to.folder, "2-1-LT1.save").exists())
        assertTrue(source2.exists())
    }

    @Test
    fun `a save made after the copy is kept when the backup is put back`() = runBlocking {
        val from = side("folder", file("own/saves/3-1.save", "a", modified = 5_000_000L))
        val to = side("steam:1", file("steam/saves/1-1.save", "b"))
        val backups = File(temp.root, "backups")
        SaveCopies.copy(backups, from, to)
        val added = File(to.folder, "3-1.save")
        added.writeText("played since")
        added.setLastModified(9_000_000L)

        SaveCopies.restore(SaveCopies.backups(backups, "steam:1").single())

        assertEquals("played since", added.readText())
    }

    @Test
    fun `only the newest backups are kept`() = runBlocking {
        val from = side("folder", file("own/saves/1.save", "a"))
        val to = side("steam:1", file("steam/saves/1.save", "b"))
        val backups = File(temp.root, "backups")
        repeat(SaveCopies.KEEP + 2) {
            SaveCopies.copy(backups, from, to)
            Thread.sleep(2)
        }
        assertEquals(SaveCopies.KEEP, SaveCopies.backups(backups, "steam:1").size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a copy into the folder it comes from is refused`() = runBlocking<Unit> {
        val one = side("a", file("same/saves/1.save", "a"))
        SaveCopies.copy(File(temp.root, "backups"), one, one.copy(entryId = "b"))
    }

    @Test
    fun `a path that climbs out of its folder is never a relative path`() {
        val folder = File("/s/saves")
        assertEquals("1.save", SaveCopies.relative(folder, File("/s/saves/1.save")))
        assertNull(SaveCopies.relative(folder, File("/s/saves/../game/x.rpa")))
        assertNull(SaveCopies.relative(folder, File("/s/savesX/1.save")))
    }
}
