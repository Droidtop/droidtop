package dev.droidtop.library.consoles

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Droidtop/tracker#931: an unpacked DOS game is one entry for its folder, not one per file. */
class FolderGamesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dosExtensions = setOf("bat", "com", "exe", "conf", "dosz", "zip")

    private fun write(dir: File, name: String, bytes: Int = 1) = File(dir, name).apply { parentFile.mkdirs(); writeBytes(ByteArray(bytes)) }

    @Test
    fun aDosGameFolderIsOneEntryNamedAfterIt() {
        val dos = temp.newFolder("dos")
        val doom = File(dos, "Doom")
        write(doom, "FDOOM.EXE", 700_000)
        write(doom, "FDSETUP.EXE", 90_000)
        write(doom, "AUTOEXEC.BAT")
        write(doom, "CONFIG.BAT")
        write(doom, "SETUP/SETSOUND.EXE", 40_000)
        write(doom, "dosbox.conf")
        write(dos, "Keen.dosz")
        write(dos, "Lonely/GAME.EXE", 10)

        val scan = RomScanWalk.walk(dos, dosExtensions)
        val listed = scan.files.map { it.relativeTo(dos).path.replace('\\', '/') }

        assertEquals(listOf("Doom/FDOOM.EXE", "Keen.dosz", "Lonely/GAME.EXE"), listed)
        assertEquals(doom, scan.folderOf[File(doom, "FDOOM.EXE")])
        assertEquals(1, scan.folderOf.size)
    }

    @Test
    fun theMainProgramIsTheOneNamedLikeTheFolderThenTheLargestGameProgram() {
        val folder = File("/g/dos/Commander Keen")
        val sizes = mapOf("KEEN1.EXE" to 50L, "COMMANDERKEEN.BAT" to 1L, "SETUP.EXE" to 900L)
        fun program(name: String) = File(folder, name)
        val size: (File) -> Long = { sizes[it.name] ?: 0L }

        assertEquals("COMMANDERKEEN.BAT", RomScanWalk.mainProgram(sizes.keys.map(::program), folder.name, size).name)
        assertEquals("KEEN1.EXE", RomScanWalk.mainProgram(listOf(program("KEEN1.EXE"), program("SETUP.EXE")), "Other", size).name)
        // Only helpers: the same choice among them.
        assertEquals("SETUP.EXE", RomScanWalk.mainProgram(listOf(program("SETUP.EXE"), program("INSTALL.BAT")), "Other", size).name)
        // Setups in any spelling are helpers, so the game wins even when they are larger.
        val spelled = mapOf("FDSETUP.EXE" to 900L, "SNDSETUP.EXE" to 800L, "SETUP2.EXE" to 700L, "INSTALL.EXE" to 600L, "TYRIAN.EXE" to 10L)
        assertEquals("TYRIAN.EXE", RomScanWalk.mainProgram(spelled.keys.map(::program), "Other") { spelled[it.name] ?: 0L }.name)
        // Only batch files: the first by name.
        assertEquals("GO.BAT", RomScanWalk.mainProgram(listOf(program("RUN.BAT"), program("GO.BAT")), "Other", size).name)
    }

    @Test
    fun aGameUnpackedOneLevelDownIsItsOuterFolder() {
        val dos = temp.newFolder("dos")
        val doom2 = File(dos, "DOOM2")
        write(doom2, "AUTOEXEC.BAT")
        write(doom2, "GAME/FDOOM.EXE", 700_000)
        write(doom2, "GAME/FDSETUP.EXE", 90_000)
        // A letter folder of two games stays two games.
        write(dos, "K/Keen/KEEN1.EXE", 50)
        write(dos, "K/Keen/SETUP.EXE", 40)
        write(dos, "K/Kroz/KROZ.EXE", 50)
        write(dos, "K/Kroz/INSTALL.EXE", 40)

        val scan = RomScanWalk.walk(dos, dosExtensions)
        val listed = scan.files.map { it.relativeTo(dos).path.replace('\\', '/') }
        assertEquals(listOf("DOOM2/GAME/FDOOM.EXE", "K/Keen/KEEN1.EXE", "K/Kroz/KROZ.EXE"), listed)
        assertEquals(doom2, scan.folderOf[File(doom2, "GAME/FDOOM.EXE")])
        assertEquals(listOf("DOOM2/AUTOEXEC.BAT", "DOOM2/GAME/FDSETUP.EXE"), scan.absorbed.map { it.relativeTo(dos).path.replace('\\', '/') }.filter { it.startsWith("DOOM2/") }.sorted())
    }

    @Test
    fun filesDirectlyInTheSystemFolderStayEntriesOfTheirOwn() {
        val dos = temp.newFolder("dos")
        write(dos, "ONE.EXE")
        write(dos, "TWO.EXE")
        assertEquals(listOf("ONE.EXE", "TWO.EXE"), RomScanWalk.walk(dos, dosExtensions).files.map { it.name })
    }
}
