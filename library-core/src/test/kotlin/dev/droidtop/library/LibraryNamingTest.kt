package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The two library-naming defects the rig showed on one screen: a
 * Syncthing marker folder listed as a game (".STFOLDER [PC]") and four
 * rows all reading "LIBRARY [FLASH]".
 */
class LibraryNamingTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** The real shipped registry, as GameEngineDetectorTest uses. */
    private val defs = EngineRegistryParser.parse(SeedAssets.read("engines-database.json"))

    // --- hidden and marker folders are not games -------------------------

    @Test
    fun `sync markers and hidden folders are never scanned`() {
        listOf(
            ".stfolder",
            ".stversions",
            ".stignore",
            ".Trash-1000",
            ".thumbnails",
            "System Volume Information",
            "\$RECYCLE.BIN",
            "lost+found",
        ).forEach { name ->
            assertFalse(
                "$name must not be scanned as a game folder",
                GameEngineDetector.isScannableFolder(File(temp.root, name)),
            )
        }
    }

    @Test
    fun `an ordinary game folder is still scanned`() {
        listOf("Sonic Adventure", "renpy-game", "Adult", "found", "recycle bin of doom").forEach { name ->
            assertTrue(name, GameEngineDetector.isScannableFolder(File(temp.root, name)))
        }
    }

    @Test
    fun `a marker folder beside a real game does not become an entry`() {
        val root = temp.newFolder("games")
        // A real Ren'Py game: renpy/ plus game/ is the precise evidence
        // the detector keys on.
        val game = File(root, "Nice Game").apply { mkdirs() }
        File(game, "renpy").mkdirs()
        File(game, "game").mkdirs()
        // Syncthing's marker, and a hidden cache, in the same root.
        File(root, ".stfolder").mkdirs()
        File(root, ".stfolder/x.dat").writeText("marker")
        File(root, ".thumbnails").mkdirs()
        File(root, ".thumbnails/a.jpg").writeText("junk")

        val found = GameEngineDetector.scan(root, emptyMap(), defs)
        assertEquals(listOf("Nice Game"), found.map { it.displayFolder.name })
    }

    // --- duplicate titles ------------------------------------------------

    private fun romEntry(path: String) = LibraryEntry(
        id = path,
        title = File(path).nameWithoutExtension,
        kind = LibraryEntryKind.CONSOLE_ROM,
        systemId = "flash",
    )

    @Test
    fun `four library-swf files are named by their own folders`() {
        val system = File("/roms/flash")
        val entries = listOf(
            romEntry("/roms/flash/Alien Hominid/library.swf"),
            romEntry("/roms/flash/Meat Boy/library.swf"),
            romEntry("/roms/flash/Fancy Pants/library.swf"),
            romEntry("/roms/flash/Motherload/library.swf"),
        ).disambiguateTitles(system)

        assertEquals(
            listOf("Alien Hominid", "Meat Boy", "Fancy Pants", "Motherload"),
            entries.map { it.title },
        )
    }

    @Test
    fun `titles that were already unique are left alone`() {
        val system = File("/roms/snes")
        val entries = listOf(
            romEntry("/roms/snes/Chrono Trigger.sfc"),
            romEntry("/roms/snes/Super Metroid.sfc"),
        ).disambiguateTitles(system)
        assertEquals(listOf("Chrono Trigger", "Super Metroid"), entries.map { it.title })
    }

    @Test
    fun `colliding folder names fall back to the path, which cannot collide`() {
        val system = File("/roms/flash")
        val entries = listOf(
            romEntry("/roms/flash/a/disc/library.swf"),
            romEntry("/roms/flash/b/disc/library.swf"),
        ).disambiguateTitles(system)
        assertEquals(listOf("a/disc/library", "b/disc/library"), entries.map { it.title })
        assertEquals(2, entries.map { it.title }.toSet().size)
    }

    @Test
    fun `a file directly in the system folder keeps a path label rather than the system name`() {
        val system = File("/roms/flash")
        val entries = listOf(
            romEntry("/roms/flash/library.swf"),
            romEntry("/roms/flash/deep/library.swf"),
        ).disambiguateTitles(system)
        assertEquals(listOf("library", "deep"), entries.map { it.title })
    }
}
