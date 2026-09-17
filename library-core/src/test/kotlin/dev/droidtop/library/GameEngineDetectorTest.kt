package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameEngineDetectorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // The REAL shipped seed registry: tests exercise the exact data
    // file the app bundles, so a registry edit that breaks detection
    // fails here before it ships.
    private val defs = EngineRegistryParser.parse(
        SeedAssets.read("engines-database.json"),
    )

    private fun touch(vararg segments: String) {
        val file = segments.fold(tmp.root) { dir, seg -> File(dir, seg) }
        file.parentFile?.mkdirs()
        file.createNewFile()
    }

    @Test
    fun `detects RenPy via renpy plus game directories`() {
        touch("renpy", ".keep")
        touch("game", ".keep")

        assertEquals(GameEngine.RENPY, GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `does not detect RenPy from game dir alone`() {
        touch("game", "script.rpy")
        assertNull(GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `detects RPG Maker MV via js-rpg_core-dot-js`() {
        touch("js", "rpg_core.js")
        assertEquals(GameEngine.RPG_MAKER_MV, GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `detects RPG Maker MV wrapped in www folder`() {
        touch("www", "js", "rpg_core.js")
        assertEquals(GameEngine.RPG_MAKER_MV, GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `detects RPG Maker MZ via js-rmmz_core-dot-js`() {
        touch("js", "rmmz_core.js")
        assertEquals(GameEngine.RPG_MAKER_MZ, GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `detects RPG Maker VX Ace via a renamed rgss3a backup file`() {
        // Pythia's own real-world fix: a patcher renamed Game.rgss3a to
        // Game.rgss3a.old — substring match, not exact-suffix.
        touch("Game.rgss3a.old")
        assertEquals(GameEngine.RPG_MAKER_VX_ACE, GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `detects RPG Maker 2000-2003 via RPG_RT exe plus ldb`() {
        touch("RPG_RT.exe")
        touch("RPG_RT.ldb")
        assertEquals(GameEngine.RPG_MAKER_2000_2003, GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `detects RPG Maker 2000-2003 via ldb plus lmt without the exe`() {
        // A real distribution can ship without the .exe (Linux/EasyRPG-only
        // release) -- the .ldb database plus the .lmt map tree.
        touch("RPG_RT.ldb")
        touch("RPG_RT.lmt")
        assertEquals(GameEngine.RPG_MAKER_2000_2003, GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `a stray RPG_RT exe without its database is not a game`() {
        // v5 tightening (aligned with enginehost's verified pairing): the
        // exe alone proves nothing without the .ldb it loads.
        touch("RPG_RT.exe")
        assertNull(GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `detects Kirikiri via startup tjs with no archive`() {
        touch("startup.tjs")
        assertEquals(GameEngine.KIRIKIRI, GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `detects CatSystem2 via a loose cst script`() {
        touch("scene00.cst")
        assertEquals(GameEngine.CATSYSTEM2, GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `detects CMVS via a ps3 script`() {
        touch("script.ps3")
        assertEquals(GameEngine.CMVS, GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `detects Buriko via its archive-set head`() {
        touch("data01000.arc")
        assertEquals(GameEngine.BURIKO, GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `detects Godot via project-dot-godot`() {
        touch("project.godot")
        assertEquals(GameEngine.GODOT, GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `detects compiled-only RenPy via a nested rpa archive`() {
        // The renpy-fallback row: no renpy/ runtime dir at all, just the
        // compiled archive where Ren'Py really puts it (game/).
        touch("game", "archive.rpa")
        assertEquals(GameEngine.RENPY, GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `enginehost-only rows are skipped by droidtop`() {
        // flash-swf: plain .swf stays droidtop's players-database path.
        touch("movie.swf")
        assertNull(GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `an ambiguous mvmz export falls through to the last row, html`() {
        // rpgmaker-mvmz is enginehost-only -- droidtop has no MV-or-MZ
        // engine to map that row to, so it is skipped -- and what the
        // export really is from droidtop's side is a page in the root.
        touch("js", "main.js")
        touch("index.html")
        assertEquals(GameEngine.HTML, GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `detects an html game from a page in the root, no markers needed`() {
        touch("A Story.html")
        assertEquals(GameEngine.HTML, GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `a godot web export is godot, not html - the html row is last`() {
        touch("index.html")
        touch("game.pck")
        assertEquals(GameEngine.GODOT, GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `detects Kirikiri via any xp3 file`() {
        touch("data.xp3")
        assertEquals(GameEngine.KIRIKIRI, GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `unrecognized folder detects as null`() {
        touch("readme.txt")
        assertNull(GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `scan finds every matching subfolder of a games root`() {
        File(tmp.root, "VN1/renpy").mkdirs()
        File(tmp.root, "VN1/game").mkdirs()
        File(tmp.root, "VN1/game/.keep").createNewFile()
        File(tmp.root, "RPG1/js").mkdirs()
        File(tmp.root, "RPG1/js/rpg_core.js").createNewFile()
        File(tmp.root, "NotAGame").mkdirs()

        val results = GameEngineDetector.scan(tmp.root, emptyMap(), defs)

        assertEquals(2, results.size)
        val vn1 = results.first { it.displayFolder.name == "VN1" }
        assertEquals(GameEngine.RENPY, vn1.engine)
        assertEquals(vn1.displayFolder, vn1.gameRoot)
        val rpg1 = results.first { it.displayFolder.name == "RPG1" }
        assertEquals(GameEngine.RPG_MAKER_MV, rpg1.engine)
        assertEquals(rpg1.displayFolder, rpg1.gameRoot)
    }

    @Test
    fun `scan finds a game nested one folder deeper than the outer display folder`() {
        // Real, confirmed shape: some Ren'Py distribution zips wrap the
        // actual game in an extra version-named folder
        // ("BeingADik/BeingADIK-0.8.3-scrappy/{renpy,game}") -- the outer
        // folder should stay the display name, the inner one is where the
        // real launch file actually lives.
        File(tmp.root, "BeingADik/BeingADIK-0.8.3-scrappy/renpy").mkdirs()
        File(tmp.root, "BeingADik/BeingADIK-0.8.3-scrappy/game").mkdirs()
        File(tmp.root, "BeingADik/BeingADIK-0.8.3-scrappy/game/.keep").createNewFile()

        val results = GameEngineDetector.scan(tmp.root, emptyMap(), defs)

        assertEquals(1, results.size)
        val game = results.first()
        assertEquals("BeingADik", game.displayFolder.name)
        assertEquals("BeingADIK-0.8.3-scrappy", game.gameRoot.name)
        assertEquals(GameEngine.RENPY, game.engine)
    }

    @Test
    fun `scan walks a root added above the engine folders down to the games`() {
        // The reported case: the user added a root ABOVE the engine
        // folders, so every game sits at GameSync/Adult/<engine>/<game>.
        // That used to be ONE game named "Adult" -- the wrapper three
        // levels up matched the compiled-Ren'Py subtree fallback.
        File(tmp.root, "Adult/RenPy/Amnesia/renpy").mkdirs()
        File(tmp.root, "Adult/RenPy/Amnesia/game").mkdirs()
        File(tmp.root, "Adult/RenPy/Amnesia/game/.keep").createNewFile()
        File(tmp.root, "Adult/RenPy/Compiled/game").mkdirs()
        File(tmp.root, "Adult/RenPy/Compiled/game/archive.rpa").createNewFile()
        File(tmp.root, "Adult/RPGMaker/Celeste/js").mkdirs()
        File(tmp.root, "Adult/RPGMaker/Celeste/js/rpg_core.js").createNewFile()

        val results = GameEngineDetector.scan(tmp.root, emptyMap(), defs)

        assertEquals(
            listOf("Amnesia", "Celeste", "Compiled"),
            results.map { it.displayFolder.name }.sorted(),
        )
        // No wrapper folder became a game, at any level.
        assertEquals(emptyList<String>(), results.map { it.displayFolder.name }.filter { it in setOf("Adult", "RenPy", "RPGMaker") })
        val compiled = results.first { it.displayFolder.name == "Compiled" }
        assertEquals(GameEngine.RENPY, compiled.engine)
        assertEquals(compiled.displayFolder, compiled.gameRoot)
        assertEquals(GameEngine.RPG_MAKER_MV, results.first { it.displayFolder.name == "Celeste" }.engine)
    }

    @Test
    fun `a game's own subfolders are not scanned for further games`() {
        // www/ is part of an RPG Maker MV game, not a second game inside
        // it: a folder that detects stops the walk.
        File(tmp.root, "RPG1/www/js").mkdirs()
        File(tmp.root, "RPG1/www/js/rpg_core.js").createNewFile()

        val results = GameEngineDetector.scan(tmp.root, emptyMap(), defs)

        assertEquals(1, results.size)
        assertEquals("RPG1", results.single().displayFolder.name)
    }

    @Test
    fun `the walk skips console system folders at every level`() {
        val n3ds = dev.droidtop.library.consoles.ConsoleSystemDef(
            id = "n3ds",
            displayName = "Nintendo 3DS",
            extensions = setOf("3ds"),
            retroArchCore = null,
        )
        // A ROM system folder deeper than the top level -- skipped for the
        // same reason as at the top: it is provably ROMs, and one real
        // j2me folder had 18,126 entries to listFiles() through.
        File(tmp.root, "Roms/n3ds/renpy").mkdirs()
        File(tmp.root, "Roms/n3ds/game").mkdirs()
        File(tmp.root, "Roms/n3ds/game/.keep").createNewFile()
        File(tmp.root, "Roms/VN1/renpy").mkdirs()
        File(tmp.root, "Roms/VN1/game").mkdirs()
        File(tmp.root, "Roms/VN1/game/.keep").createNewFile()

        val results = GameEngineDetector.scan(tmp.root, mapOf("n3ds" to n3ds), defs)

        assertEquals(listOf("VN1"), results.map { it.displayFolder.name })
    }

    @Test
    fun `the walk stops at a bounded depth`() {
        var dir = tmp.root
        repeat(GameEngineDetector.MAX_SCAN_DEPTH + 1) { dir = File(dir, "deep") }
        File(dir, "renpy").mkdirs()
        File(dir, "game").mkdirs()
        File(dir, "game/.keep").createNewFile()

        assertEquals(emptyList<DetectedGame>(), GameEngineDetector.scan(tmp.root, emptyMap(), defs))
    }

    @Test
    fun `detects RPG Maker XP via rgssad archive`() {
        touch("Game.rgssad")
        org.junit.Assert.assertEquals(GameEngine.RPG_MAKER_XP, GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `detects RPG Maker VX via rgss2a archive`() {
        touch("Game.rgss2a")
        org.junit.Assert.assertEquals(GameEngine.RPG_MAKER_VX, GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `Game ini RGSS declaration classifies the generation`() {
        val ini = java.io.File(tmp.root, "Game.ini")
        ini.writeText("[Game]\r\nLibrary=System\\RGSS102E.dll\r\n")
        org.junit.Assert.assertEquals(GameEngine.RPG_MAKER_XP, GameEngineDetector.detect(tmp.root, defs))
        ini.writeText("[Game]\r\nLibrary=System\\RGSS301.dll\r\n")
        org.junit.Assert.assertEquals(GameEngine.RPG_MAKER_VX_ACE, GameEngineDetector.detect(tmp.root, defs))
    }

    @Test
    fun `a subtree rule still names this folder as the root when nothing more precise exists`() {
        // The renpy-fallback row through detectGame: no precise match at
        // this folder and none in any subfolder either, so the folder
        // holding the compiled archive stays the game root. The tiering
        // in detectGame must not turn a subtree match into no match.
        touch("game", "archive.rpa")

        val detected = GameEngineDetector.detectGame(tmp.root, defs)
        org.junit.Assert.assertEquals(GameEngine.RENPY, detected?.engine)
        assertEquals(tmp.root, detected?.displayFolder)
        assertEquals(tmp.root, detected?.gameRoot)
    }

    @Test
    fun `every compiled engine has a row in the shipped seed, and every row has rules`() {
        // The direction of this check flipped when the seed became the
        // WHOLE platform registry rather than a hand-picked subset of it
        // (2026-09-10). Rows droidtop cannot map are now normal and
        // expected -- enginehost-only classifications, and families with a
        // detection rule but no droidtop launch route yet -- and asserting
        // every row maps would mean the platform repo could not add one
        // without breaking this app's build. What must still hold, and is
        // what actually catches a typo or a rename, is the other
        // direction: every engine compiled into this app is reachable,
        // because a GameEngine with no row can never be detected.
        org.junit.Assert.assertTrue(defs.isNotEmpty())
        val mapped = defs.mapNotNull { it.engine }.toSet()
        org.junit.Assert.assertEquals(
            "compiled engines with no row in the shipped registry",
            emptySet<GameEngine>(),
            GameEngine.entries.toSet() - mapped,
        )
        defs.forEach { def ->
            org.junit.Assert.assertTrue("seed row ${def.id} has no detection rules", def.detect.isNotEmpty())
        }
    }

    @Test
    fun `a PC game's own subfolders are not engine games`() {
        // Ghost Recon Breakpoint on the rig: the game folder holds its
        // executables, and its benchmark folder holds an index.html --
        // which the database's last row, "there is a page here", read as
        // a game called "benchmark", while the game it sits inside was
        // never listed at all.
        File(tmp.root, "Ghost Recon Breakpoint/benchmark").mkdirs()
        File(tmp.root, "Ghost Recon Breakpoint/GRB.exe").writeText("MZ")
        File(tmp.root, "Ghost Recon Breakpoint/benchmark/index.html").writeText("<html>")

        assertEquals(emptyList<String>(), GameEngineDetector.scan(tmp.root, emptyMap(), defs).map { it.displayFolder.name })
    }

    @Test
    fun `a category folder holding two engine games is a container, not a game`() {
        // adult/godot: a loose Godot Linux build beside two Godot games
        // made the category folder itself the precise match, so it was
        // listed as a game called "godot" and both games vanished.
        File(tmp.root, "godot/Anomalous").mkdirs()
        File(tmp.root, "godot/Goodbye Eternity").mkdirs()
        File(tmp.root, "godot/loose_linux.x86_64").writeText("ELF")
        File(tmp.root, "godot/Anomalous/acm2.pck").writeText("pck")
        File(tmp.root, "godot/Goodbye Eternity/goodbye.pck").writeText("pck")

        val results = GameEngineDetector.scan(tmp.root, emptyMap(), defs)

        assertEquals(listOf("Anomalous", "Goodbye Eternity"), results.map { it.displayFolder.name }.sorted())
        org.junit.Assert.assertTrue(results.all { it.engine == GameEngine.GODOT })
    }

    @Test
    fun `a game folder with one payload folder below it is still the game`() {
        // The other side of the container rule: ONE game below is a
        // wrapper or a payload folder, never a container, so a Ren'Py
        // game whose own game/ folder detects stays one entry.
        File(tmp.root, "VN1/renpy").mkdirs()
        File(tmp.root, "VN1/game").mkdirs()
        File(tmp.root, "VN1/game/.keep").createNewFile()
        File(tmp.root, "VN1/VN1.exe").writeText("MZ")

        assertEquals(listOf("VN1"), GameEngineDetector.scan(tmp.root, emptyMap(), defs).map { it.displayFolder.name })
    }
}
