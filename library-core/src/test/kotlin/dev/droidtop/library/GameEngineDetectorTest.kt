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
        File("src/main/assets/engines-database.json").readText(),
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
        // rpgmaker-mvmz: droidtop has no MV-or-MZ engine to map it to.
        touch("js", "main.js")
        touch("index.html")
        assertNull(GameEngineDetector.detect(tmp.root, defs))
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
    fun `every registry row with rules maps to a compiled engine`() {
        // A row this app cannot resolve is legal (future database), but
        // the SHIPPED seed and this app must agree completely -- except
        // the rows the seed itself declares enginehost-only (v5: the
        // database is the shared classification authority for BOTH apps,
        // and some classifications only exist on enginehost's side).
        val enginehostOnly = setOf("rpgmaker-mvmz", "flash-swf")
        org.junit.Assert.assertTrue(defs.isNotEmpty())
        defs.forEach { def ->
            if (def.id in enginehostOnly) {
                org.junit.Assert.assertNull("row ${def.id} must stay enginehost-only", def.engine)
            } else {
                org.junit.Assert.assertNotNull("seed row ${def.id} has no compiled engine", def.engine)
            }
            org.junit.Assert.assertTrue("seed row ${def.id} has no detection rules", def.detect.isNotEmpty())
        }
    }
}
