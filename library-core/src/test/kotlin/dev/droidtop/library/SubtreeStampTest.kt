package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The slow pass's stamp for a top-level folder (docs/SPEC.md 7g, step 4; tracker#577): a game added further down moves
 * it though the folder's own time does not.
 */
class SubtreeStampTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(path: String, mtime: Long): File = File(tmp.root, path).apply { mkdirs(); setLastModified(mtime) }

    @Test
    fun `a game added two levels down moves the stamp of the folder above`() {
        val manual = dir("Manual", 1_000_000)
        dir("Manual/love2d", 1_000_000)
        manual.setLastModified(1_000_000)
        val before = GameEngineDetector.subtreeStamp(manual)
        assertEquals(1_000_000L, before)

        // Thermomorph is added under love2d: love2d's time moves, Manual's does not.
        dir("Manual/love2d/Thermomorph", 2_000_000)
        File(tmp.root, "Manual/love2d").setLastModified(2_000_000)
        manual.setLastModified(1_000_000)

        assertEquals(2_000_000L, GameEngineDetector.subtreeStamp(manual))
    }

    @Test
    fun `the stamp stops at the walk's own depth, and a version folder costs none`() {
        val top = dir("A", 1_000_000)
        // Depth 4 is the deepest title level the walk looks at; a folder past it is not stamped.
        dir("A/b/c/d/e", 9_000_000)
        dir("A/b/c/d", 3_000_000)
        dir("A/b/c", 1_000_000)
        dir("A/b", 1_000_000)
        top.setLastModified(1_000_000)
        assertEquals(3_000_000L, GameEngineDetector.subtreeStamp(top))

        // A version folder (12.0-scrappy) costs the walk no depth, so what is under it is still stamped.
        val game = dir("G", 1_000_000)
        dir("G/x/y/12.0-scrappy/Game", 5_000_000)
        dir("G/x/y/12.0-scrappy", 1_000_000)
        dir("G/x/y", 1_000_000)
        dir("G/x", 1_000_000)
        game.setLastModified(1_000_000)
        assertTrue(GameEngineDetector.subtreeStamp(game) >= 5_000_000L)
    }

    @Test
    fun `a folder that cannot be read is unknown`() {
        assertEquals(0L, GameEngineDetector.subtreeStamp(File(tmp.root, "missing")))
    }
}
