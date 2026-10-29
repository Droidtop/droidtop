package dev.droidtop.library.consoles

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** docs/SPEC.md 7g, "A multi-disc game is its `.m3u`": a playlist's discs are not entries of their own. */
class MultiDiscHidingTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun write(dir: File, name: String, text: String = "") = File(dir, name).apply { parentFile.mkdirs(); writeText(text) }

    private fun listed(system: File, vararg extensions: String) =
        RomScanWalk.walk(system, extensions.toSet()).files.map { it.relativeTo(system).path }

    @Test
    fun anM3uIsTheEntryAndItsDiscsAreHidden() {
        val psx = temp.newFolder("psx")
        write(psx, "Final Fantasy VII.m3u", "#EXTM3U\nFinal Fantasy VII (Disc 1).chd\r\nFinal Fantasy VII (Disc 2).chd\n\nfinal fantasy vii (disc 3).CHD\n")
        listOf(1, 2, 3).forEach { write(psx, "Final Fantasy VII (Disc $it).chd") }
        write(psx, "Crash Bandicoot.chd")
        assertEquals(listOf("Crash Bandicoot.chd", "Final Fantasy VII.m3u"), listed(psx, "chd", "m3u"))
    }

    @Test
    fun aCueHidesItsTracksAndAnM3uHidesItsCues() {
        val psx = temp.newFolder("psx")
        val game = File(psx, "Metal Gear Solid")
        write(game, "MGS.m3u", "discs\\MGS (Disc 1).cue\ndiscs/MGS (Disc 2).cue\n")
        listOf(1, 2).forEach { disc ->
            write(game, "discs/MGS (Disc $disc).cue", "FILE \"MGS (Disc $disc).bin\" BINARY\n  TRACK 01 MODE2/2352\n")
            write(game, "discs/MGS (Disc $disc).bin")
        }
        write(psx, "Spyro.cue", "file Spyro.bin BINARY\n")
        write(psx, "Spyro.bin")
        write(psx, "Loose.bin")
        assertEquals(
            listOf("Loose.bin", "Metal Gear Solid/MGS.m3u", "Spyro.cue"),
            listed(psx, "bin", "cue", "m3u"),
        )
    }

    @Test
    fun withoutM3uAmongTheExtensionsEveryDiscStays() {
        val psx = temp.newFolder("psx")
        write(psx, "Game.m3u", "Game (Disc 1).chd\nGame (Disc 2).chd\n")
        write(psx, "Game (Disc 1).chd")
        write(psx, "Game (Disc 2).chd")
        assertEquals(listOf("Game (Disc 1).chd", "Game (Disc 2).chd"), listed(psx, "chd"))
    }

    @Test
    fun aBiosImageBesideTheGamesIsNotAGame() {
        // Droidtop/tracker#665: the PS2 BIOS dropped in the system folder showed on Home as "ps2 0230a 20080220".
        val ps2 = temp.newFolder("ps2")
        write(ps2, "ps2-0230a-20080220.bin")
        write(ps2, "Okami.bin")
        val walked = RomScanWalk.walk(ps2, setOf("bin"), notGames = setOf("ps2-0230a-20080220.bin"))
        assertEquals(listOf("Okami.bin"), walked.files.map { it.name })
        assertEquals(listOf("ps2-0230a-20080220.bin"), walked.skipped.map { it.first.name })
    }
}
