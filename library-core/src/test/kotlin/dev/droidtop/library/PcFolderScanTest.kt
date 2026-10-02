package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The rig's own layout, folder for folder: every case here is a real
 * folder under the user's games root on 2026-09-16, where build 531
 * listed seven wrapper folders as games and hid the games inside them.
 */
class PcFolderScanTest {

    @get:Rule
    val temp = TemporaryFolder()

    // The real shipped rules, so a registry edit that changes which
    // folders are engine games shows up here too.
    private val defs = EngineRegistryParser.parse(SeedAssets.read("engines-database.json"))

    private fun game(path: String, exe: String = "Game.exe") {
        val dir = File(temp.root, path)
        dir.mkdirs()
        File(dir, exe).writeText("MZ")
    }

    private fun dir(path: String) {
        File(temp.root, path).mkdirs()
    }

    private fun file(path: String, contents: String = "x") {
        val file = File(temp.root, path)
        file.parentFile?.mkdirs()
        file.writeText(contents)
    }

    private fun found(): List<String> =
        PcFolderScan.gamesUnder(temp.root, defs).map { it.toRelativeString(temp.root).replace(File.separatorChar, '/') }

    @Test
    fun `a container folder contributes the games below it and never itself`() {
        game("EA/SimCity")
        dir("EA/.gamenative")
        game("Ubisoft/Far Cry 5")
        game("Ubisoft/Far Cry New Dawn")
        assertEquals(listOf("EA/SimCity", "Ubisoft/Far Cry 5", "Ubisoft/Far Cry New Dawn"), found())
    }

    @Test
    fun `a folder the slow pass skips is listed, unwalked, with the stamp it was skipped on`() {
        game("EA/SimCity")
        game("Ubisoft/Far Cry 5")
        val ea = File(temp.root, "EA")
        val tops = PcFolderScan.gamesByTopLevelFolder(temp.root, defs) { folder, mtime ->
            folder == ea && mtime == ea.lastModified()
        }
        val byName = tops.associateBy { it.folder.name }
        assertEquals(setOf("EA", "Ubisoft"), byName.keys)
        assertTrue(byName.getValue("EA").skipped)
        assertEquals(emptyList<File>(), byName.getValue("EA").games)
        assertEquals(ea.lastModified(), byName.getValue("EA").mtime)
        assertFalse(byName.getValue("Ubisoft").skipped)
        assertEquals(listOf("Far Cry 5"), byName.getValue("Ubisoft").games.map { it.name })
    }

    @Test
    fun `a store folder holding no games contributes nothing`() {
        dir("Epic/.gamenative")
        dir("GamePass/.gamenative")
        dir("battle.net/.gamenative")
        assertEquals(emptyList<String>(), found())
    }

    @Test
    fun `a rom tree is not a PC game`() {
        dir("roms/ps2")
        dir("roms/wiiu")
        assertEquals(emptyList<String>(), found())
    }

    @Test
    fun `a hidden marker folder is not a PC game`() {
        dir(".stfolder")
        game("Eternum-0.9.5-pc")
        assertEquals(listOf("Eternum-0.9.5-pc"), found())
    }

    @Test
    fun `a game's own subfolders are not further games`() {
        game("Ghost Recon Breakpoint")
        game("Ghost Recon Breakpoint/benchmark", exe = "benchmark.exe")
        assertEquals(listOf("Ghost Recon Breakpoint"), found())
    }

    @Test
    fun `an executable two levels down still names the outer folder`() {
        dir("Unreal Game/Binaries/Win64")
        File(temp.root, "Unreal Game/readme.txt").writeText("hi")
        File(temp.root, "Unreal Game/Binaries/Win64/Game-Win64-Shipping.exe").writeText("MZ")
        assertEquals(listOf("Unreal Game"), found())
    }

    @Test
    fun `a store tree yields its installed games and never its own folders`() {
        dir("Steam/steamapps/common")
        File(temp.root, "Steam/libraryfolder.vdf").writeText("{}")
        File(temp.root, "Steam/steam.dll").writeText("MZ")
        File(temp.root, "Steam/steamapps/appmanifest_1.acf").writeText("{}")
        game("Steam/steamapps/common/REPO")
        assertEquals(listOf("Steam/steamapps/common/REPO"), found())
    }

    @Test
    fun `a store's non-game tree is never walked`() {
        dir("Steam/steamapps/common")
        File(temp.root, "Steam/libraryfolder.vdf").writeText("{}")
        game("Steam/steamapps/workshop/content/1234/mod")
        assertEquals(emptyList<String>(), found())
    }

    @Test
    fun `a game keeping its executables in payload folders is the game, not the folders`() {
        // Every Ubisoft install on the rig: the launcher's own files sit
        // in the game folder and the executables two levels down, in
        // more than one payload folder. The one-game-below form of rule 4
        // listed `bin` and `bin_plus` and never Far Cry 5.
        file("Ubisoft/Far Cry 5/uplay_install.manifest")
        file("Ubisoft/Far Cry 5/uplay_install.state")
        game("Ubisoft/Far Cry 5/bin", exe = "FarCry5.exe")
        game("Ubisoft/Far Cry 5/bin_plus", exe = "FarCry5.exe")
        assertEquals(listOf("Ubisoft/Far Cry 5"), found())
    }

    @Test
    fun `a store wrapper contributes the game inside it, whose own payload folders stay hidden`() {
        // EA/SimCity: no executable in the game folder, three below it.
        file("EA/.gamenative")
        file("EA/SimCity/Setup.ini")
        game("EA/SimCity/SimCity", exe = "SimCity.exe")
        game("EA/SimCity/SimCityData", exe = "helper.exe")
        game("EA/SimCity/Support", exe = "support.exe")
        assertEquals(listOf("EA/SimCity"), found())
    }

    @Test
    fun `a game whose only subfolder is a web payload is still listed, once`() {
        // Build 540 listed NEITHER of these anywhere: the engine walk
        // correctly stopped at the game folder, and detectGame then read
        // the payload's index.html one level down, called the folder
        // engine-owned, and PcGameProvider dropped the PC entry as a
        // duplicate of an engine entry that was never created.
        game("Ubisoft/Ghost Recon Breakpoint", exe = "GRB.exe")
        file("Ubisoft/Ghost Recon Breakpoint/benchmark/index.html", contents = "<html>")
        game("Pirated/The Movies", exe = "MoviesSE.exe")
        file("Pirated/The Movies/Docs/index.html", contents = "<html>")

        assertEquals(listOf("Pirated/The Movies", "Ubisoft/Ghost Recon Breakpoint"), found())
        // ...and nothing suppresses them, because engine detection does
        // not claim a folder whose only engine evidence is its payload's.
        for (path in listOf("Ubisoft/Ghost Recon Breakpoint", "Pirated/The Movies")) {
            val folder = File(temp.root, path)
            assertTrue(path, GameEngineDetector.isPlainPcGameFolder(folder, defs))
            assertFalse(path, GameEngineDetector.engineOwnsInstall(folder, defs))
            assertNull(path, GameEngineDetector.detectGame(folder, defs))
        }
    }

    @Test
    fun `a category folder holding engine games is not a game, whatever is loose in it`() {
        // adult/godot on the rig: two Godot games and one Godot Linux
        // build left loose beside them. The loose build made the folder
        // itself look like a game and hid both.
        file("adult/godot/Anomalous_Coffee_Machine_2-1.0.00_deluxe_linux.x86_64", contents = "ELF")
        file("adult/godot/Anomalous/acm2.pck")
        file("adult/godot/Anomalous/acm2.exe", contents = "MZ")
        file("adult/godot/Goodbye Eternity/goodbye.pck")
        file("adult/godot/Goodbye Eternity/goodbye.exe", contents = "MZ")
        assertEquals(
            listOf("adult/godot/Anomalous", "adult/godot/Goodbye Eternity"),
            found(),
        )
    }

    @Test
    fun `a Windows game in a version folder under a part folder is found past the depth bound`() {
        // Five folders down: category, series, game, part, version. Only
        // the first three are levels of the library.
        game("Adult/unity/Story/Chapter 2/1.0", "Story.exe")
        // A stray readme in the part folder does not make the part the game.
        file("Adult/unity/Story/Chapter 2/readme.txt")
        assertEquals(listOf("Adult/unity/Story/Chapter 2/1.0"), found())
    }

    @Test
    fun `a game whose executable sits below its own files is found without reading its whole tree`() {
        file("Shape/Some Game/readme.txt")
        game("Shape/Some Game/Binaries/Win64")
        for (i in 1..5) file("Shape/Some Game/Content/Pack$i/data.pak")
        val top = PcFolderScan.gamesByTopLevelFolder(File(temp.root, "Shape"), defs).single()
        assertEquals(listOf("Some Game"), top.games.map { it.name })
        // The root and the game folder, then the level the executable is in: never the Content packs' folders.
        assertTrue(top.work.listings <= 4)
    }

    @Test
    fun `a remembered listing answers an unchanged folder and a changed one is read again`() {
        game("Cache/Some Game")
        val cache = PcFolderScan.ListingCache()
        val options = PcFolderScan.Options(cache = cache)
        val folder = File(temp.root, "Cache")
        val first = PcFolderScan.scanTopLevel(folder, defs, options)
        assertTrue(first.work.listings > 0)
        val second = PcFolderScan.scanTopLevel(folder, defs, options)
        assertEquals(first.games, second.games)
        assertEquals(0, second.work.listings)
        assertTrue(second.work.cachedListings > 0)
        // A new game beside it moves the folder's time, so the folder is read again.
        game("Cache/Other Game")
        File(temp.root, "Cache").setLastModified(File(temp.root, "Cache").lastModified() + 5_000)
        val third = PcFolderScan.scanTopLevel(folder, defs, options)
        assertEquals(listOf("Other Game", "Some Game"), third.games.map { it.name })
    }

    @Test(expected = java.util.concurrent.CancellationException::class)
    fun `a cancelled walk stops at its next listing`() {
        game("Stop/Some Game")
        PcFolderScan.scanTopLevel(File(temp.root, "Stop"), defs, PcFolderScan.Options(cancelled = { true }))
    }

    /** A category folder with a program of its own (so rule 6 is asked) and [count] engine folders below it. */
    private fun categoryWithEngineFolders(path: String, count: Int, engineFolders: Int = count) {
        file("$path/Game.exe", "MZ")
        for (i in 1..count) {
            if (i <= engineFolders) file("$path/Title$i/data.pck") else file("$path/Title$i/notes.txt")
        }
    }

    @Test
    fun `an engine verdict kept in a file answers a cold start without checking the folder again`() {
        categoryWithEngineFolders("Cat", count = 6)
        val folder = File(temp.root, "Cat")
        val kept = File(temp.root, "verdicts.tsv")
        val first = PcFolderScan.EngineVerdicts.load(kept, defs)
        val cold = PcFolderScan.scanTopLevel(folder, defs, PcFolderScan.Options(verdicts = first))
        assertTrue(cold.work.engineChecks > 0)
        first.save(kept)

        // A new process: no listing cache, only the file.
        val restarted = PcFolderScan.EngineVerdicts.load(kept, defs)
        val warm = PcFolderScan.scanTopLevel(folder, defs, PcFolderScan.Options(verdicts = restarted))
        assertEquals(0, warm.work.engineChecks)
        assertTrue(warm.work.engineVerdictsKept > 0)
        assertEquals(0, warm.work.engineVerdictMisses)
        assertEquals(cold.games, warm.games)
    }

    @Test
    fun `a kept engine verdict is dropped when the folder or the rules changed`() {
        categoryWithEngineFolders("Cat", count = 6)
        val folder = File(temp.root, "Cat")
        val kept = File(temp.root, "verdicts.tsv")
        val first = PcFolderScan.EngineVerdicts.load(kept, defs)
        PcFolderScan.scanTopLevel(folder, defs, PcFolderScan.Options(verdicts = first))
        first.save(kept)

        // Other engine rules: the file was written for different ones.
        val otherRules = PcFolderScan.EngineVerdicts.load(kept, defs.drop(1))
        val otherRun = PcFolderScan.scanTopLevel(folder, defs.drop(1), PcFolderScan.Options(verdicts = otherRules))
        assertTrue(otherRun.work.engineChecks > 0)

        // An entry added to the folder changes its entry count, even when its time is unchanged.
        val sameRules = PcFolderScan.EngineVerdicts.load(kept, defs)
        val stamp = folder.lastModified()
        file("Cat/Extra/readme.txt")
        folder.setLastModified(stamp)
        val changed = PcFolderScan.scanTopLevel(folder, defs, PcFolderScan.Options(verdicts = sameRules))
        assertTrue(changed.work.engineChecks > 0)
    }

    @Test
    fun `a file of verdicts that is missing or garbled is simply empty`() {
        val garbled = File(temp.root, "garbled.tsv")
        garbled.writeText("not a fingerprint\nnonsense")
        categoryWithEngineFolders("Cat", count = 3)
        val top = PcFolderScan.scanTopLevel(
            File(temp.root, "Cat"),
            defs,
            PcFolderScan.Options(verdicts = PcFolderScan.EngineVerdicts.load(garbled, defs)),
        )
        assertTrue(top.work.engineChecks > 0)
        assertEquals(0, top.work.engineVerdictsKept)
        PcFolderScan.EngineVerdicts.load(File(temp.root, "missing.tsv"), defs)
    }

    @Test
    fun `an engine check counts engine folders the same sequentially and in parallel`() {
        // Three folders are checked one after another, six at once.
        for (count in listOf(3, 6)) {
            categoryWithEngineFolders("Two$count", count = count, engineFolders = 2)
            categoryWithEngineFolders("One$count", count = count, engineFolders = 1)
            assertTrue(GameEngineDetector.holdsSeveralGames(File(temp.root, "Two$count"), defs))
            assertFalse(GameEngineDetector.holdsSeveralGames(File(temp.root, "One$count"), defs))
        }
    }

    @Test
    fun `an engine check reports every folder it was going to look at`() {
        categoryWithEngineFolders("Cat", count = 6, engineFolders = 1)
        val planned = java.util.concurrent.atomic.AtomicInteger()
        val finished = java.util.concurrent.atomic.AtomicInteger()
        val hooks = GameEngineDetector.ProbeHooks(planned = { planned.addAndGet(it) }, finished = { finished.incrementAndGet() })
        assertFalse(GameEngineDetector.holdsSeveralGames(File(temp.root, "Cat"), defs, hooks = hooks))
        assertEquals(6, planned.get())
        assertEquals(6, finished.get())
    }

    @Test
    fun `a cancelled engine check stops whether it runs one by one or at once`() {
        for (count in listOf(3, 6)) {
            categoryWithEngineFolders("Cat$count", count = count)
            val hooks = GameEngineDetector.ProbeHooks(cancelled = { true })
            val stopped = try {
                GameEngineDetector.holdsSeveralGames(File(temp.root, "Cat$count"), defs, hooks = hooks)
                false
            } catch (_: java.util.concurrent.CancellationException) {
                true
            }
            assertTrue("count $count", stopped)
        }
    }

    @Test
    fun `folder facts read a folder once and ask the disk about a name only when it is listed`() {
        file("Probe/Game.exe", "MZ")
        file("Probe/data.pck")
        val facts = FolderListing(File(temp.root, "Probe"))
        assertEquals(setOf("Game.exe", "data.pck"), facts.entryNames().toSet())
        // Added after the listing was read: the one read is what every rule is answered from.
        file("Probe/late.exe", "MZ")
        assertFalse(facts.hasFile { it == "late.exe" })
        assertTrue(facts.hasFile { it.endsWith(".pck") })
        assertFalse(facts.hasPath("nothing/here.txt", folderWanted = false))
        assertTrue(facts.hasPath("Game.exe", folderWanted = false))
        assertFalse(facts.hasPath("Game.exe", folderWanted = true))
    }

    @Test
    fun `a folder that holds nothing to run is not an engine game root by its subtree evidence`() {
        // The compiled Ren'Py archive two levels down names a game only beside a program.
        file("Compiled/game/archive.rpa")
        assertNull(GameEngineDetector.engineHere(File(temp.root, "Compiled"), defs))
        file("Compiled/Start.exe", "MZ")
        assertEquals(GameEngine.RENPY, GameEngineDetector.engineHere(File(temp.root, "Compiled"), defs))
    }
}
