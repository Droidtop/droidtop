package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A folder game keeps its identity across a version bump (docs/SPEC.md 7g,
 * Droidtop/tracker#397 slice E): `MyGame-v0.5` replaced by `MyGame-v0.6` in
 * the same folder is the same game, so its id, and with it every fact the
 * library keys by it, carries over. Two version folders side by side stay two.
 */
class FolderIdentityTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun game(name: String, body: String = "MZ-$name", parent: String = "games"): File {
        val dir = File(temp.root, "$parent/$name").also { it.mkdirs() }
        File(dir, "Game.exe").writeText(body)
        return dir
    }

    private fun ids() = GameFolderIds(File(temp.root, "own/ids.tsv"))

    @Test
    fun `a replaced version folder in the same place is the same game`() {
        val old = game("MyGame-v0.5", body = "MZ-old-build")
        ids().remember(old, 31)
        assertTrue(old.deleteRecursively())
        val new = game("MyGame-v0.6", body = "MZ-new-build-with-more-bytes")
        assertEquals(31, ids().idFor(new))
    }

    @Test
    fun `every recognised version shape keeps the game`() {
        val shapes = listOf(
            "Game v1.2" to "Game v1.3",
            "Game-1.2.0" to "Game-1.3.0",
            "Game [v0.5]" to "Game [v0.6]",
            "Game (0.5b)" to "Game (0.6a)",
            "Game 1.2 PC" to "Game 1.3 PC",
            "Game_v0.6.1a" to "Game_v0.7",
        )
        shapes.forEachIndexed { i, (before, after) ->
            val parent = "lib$i"
            val old = game(before, body = "old-$i", parent = parent)
            val map = ids()
            map.remember(old, 100 + i)
            old.deleteRecursively()
            val new = game(after, body = "new-build-$i-longer", parent = parent)
            assertEquals("$before -> $after", 100 + i, map.idFor(new))
        }
    }

    @Test
    fun `two version folders side by side are two games`() {
        val old = game("MyGame-v0.5", body = "a")
        val map = ids()
        map.remember(old, 1)
        val new = game("MyGame-v0.6", body = "bb")
        assertNull(map.idFor(new))
    }

    @Test
    fun `a different parent folder is a different game`() {
        val old = game("MyGame-v0.5", body = "a", parent = "games/one")
        val map = ids()
        map.remember(old, 1)
        old.deleteRecursively()
        assertNull(map.idFor(game("MyGame-v0.6", body = "bb", parent = "games/two")))
    }

    @Test
    fun `a different title is a different game`() {
        val old = game("MyGame-v0.5", body = "a")
        val map = ids()
        map.remember(old, 1)
        old.deleteRecursively()
        assertNull(map.idFor(game("OtherGame-v0.6", body = "bb")))
    }

    @Test
    fun `two vanished folders of one title are not guessed between`() {
        val map = ids()
        val a = game("MyGame-v0.4", body = "a")
        val b = game("MyGame-v0.5", body = "bb")
        map.remember(a, 1)
        map.remember(b, 2)
        a.deleteRecursively()
        b.deleteRecursively()
        assertNull(map.idFor(game("MyGame-v0.6", body = "ccc")))
    }
}
