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

    private fun touch(vararg segments: String) {
        val file = segments.fold(tmp.root) { dir, seg -> File(dir, seg) }
        file.parentFile?.mkdirs()
        file.createNewFile()
    }

    @Test
    fun `detects RenPy via renpy plus game directories`() {
        touch("renpy", ".keep")
        touch("game", ".keep")

        assertEquals(GameEngine.RENPY, GameEngineDetector.detect(tmp.root))
    }

    @Test
    fun `does not detect RenPy from game dir alone`() {
        touch("game", "script.rpy")
        assertNull(GameEngineDetector.detect(tmp.root))
    }

    @Test
    fun `detects RPG Maker MV via js-rpg_core-dot-js`() {
        touch("js", "rpg_core.js")
        assertEquals(GameEngine.RPG_MAKER_MV, GameEngineDetector.detect(tmp.root))
    }

    @Test
    fun `detects RPG Maker MV wrapped in www folder`() {
        touch("www", "js", "rpg_core.js")
        assertEquals(GameEngine.RPG_MAKER_MV, GameEngineDetector.detect(tmp.root))
    }

    @Test
    fun `detects RPG Maker MZ via js-rmmz_core-dot-js`() {
        touch("js", "rmmz_core.js")
        assertEquals(GameEngine.RPG_MAKER_MZ, GameEngineDetector.detect(tmp.root))
    }

    @Test
    fun `detects RPG Maker VX Ace via a renamed rgss3a backup file`() {
        // Pythia's own real-world fix: a patcher renamed Game.rgss3a to
        // Game.rgss3a.old — substring match, not exact-suffix.
        touch("Game.rgss3a.old")
        assertEquals(GameEngine.RPG_MAKER_VX_ACE, GameEngineDetector.detect(tmp.root))
    }

    @Test
    fun `unrecognized folder detects as null`() {
        touch("readme.txt")
        assertNull(GameEngineDetector.detect(tmp.root))
    }

    @Test
    fun `scan finds every matching subfolder of a games root`() {
        File(tmp.root, "VN1/renpy").mkdirs()
        File(tmp.root, "VN1/game").mkdirs()
        File(tmp.root, "VN1/game/.keep").createNewFile()
        File(tmp.root, "RPG1/js").mkdirs()
        File(tmp.root, "RPG1/js/rpg_core.js").createNewFile()
        File(tmp.root, "NotAGame").mkdirs()

        val results = GameEngineDetector.scan(tmp.root)

        assertEquals(2, results.size)
        assertEquals(GameEngine.RENPY, results.first { it.first.name == "VN1" }.second)
        assertEquals(GameEngine.RPG_MAKER_MV, results.first { it.first.name == "RPG1" }.second)
    }
}
