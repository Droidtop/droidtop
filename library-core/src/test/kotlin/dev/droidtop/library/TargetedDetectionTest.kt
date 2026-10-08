package dev.droidtop.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The folder-level halves of targeted indexing (docs/SPEC.md 7g, Droidtop/tracker#354): the walk's own rules asked
 * of ONE reported folder. The real shipped engine rules are used, as in [PcFolderScanTest].
 */
class TargetedDetectionTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val defs = EngineRegistryParser.parse(SeedAssets.read("engines-database.json"))

    private fun program(path: String) {
        val dir = File(temp.root, path)
        dir.mkdirs()
        File(dir, "Game.exe").writeText("MZ")
    }

    private fun file(path: String) {
        val file = File(temp.root, path)
        file.parentFile?.mkdirs()
        file.writeText("x")
    }

    private fun relative(files: List<File>?) = files?.map { it.toRelativeString(temp.root).replace(File.separatorChar, '/') }

    @Test
    fun `a new PC game folder is found by looking at that folder only`() {
        program("Adult/NewGame")
        program("Adult/Old")
        val found = PcFolderScan.scanPath(temp.root, File(temp.root, "Adult/NewGame"), defs)
        assertEquals(listOf("Adult/NewGame"), relative(found))
    }

    @Test
    fun `a new category folder holding games yields the games below it`() {
        program("Ubisoft/Far Cry 5")
        program("Ubisoft/Far Cry New Dawn")
        assertEquals(
            listOf("Ubisoft/Far Cry 5", "Ubisoft/Far Cry New Dawn"),
            relative(PcFolderScan.scanPath(temp.root, File(temp.root, "Ubisoft"), defs)),
        )
    }

    @Test
    fun `a folder inside a game is that game, because what is inside a game is its payload`() {
        program("Games/Shooter")
        file("Games/Shooter/data/level1.bin")
        assertEquals(listOf("Games/Shooter"), relative(PcFolderScan.scanPath(temp.root, File(temp.root, "Games/Shooter/data"), defs)))
    }

    @Test
    fun `a folder the walk would never enter is not looked at`() {
        program(".hidden/Game")
        program("Games/a/b/c/d/e/Deep")
        assertNull(PcFolderScan.scanPath(temp.root, File(temp.root, ".hidden/Game"), defs))
        assertNull("deeper than the walk goes", PcFolderScan.scanPath(temp.root, File(temp.root, "Games/a/b/c/d/e/Deep"), defs))
        assertNull("not below the root", PcFolderScan.scanPath(temp.root, File(temp.root.parentFile, "elsewhere"), defs))
    }

    @Test
    fun `a folder with no program anywhere below is not a game`() {
        file("Games/Empty/readme.txt")
        assertEquals(emptyList<String>(), relative(PcFolderScan.scanPath(temp.root, File(temp.root, "Games/Empty"), defs)))
    }

    @Test
    fun `placeOf names the root, the top-level folder and the folder to look at`() {
        program("Adult/renpy/NewGame")
        val place = GameEngineDetector.placeOf(File(temp.root, "Adult/renpy/NewGame/Game.exe"), listOf(temp.root), emptyMap())
        assertNotNull(place)
        assertEquals(temp.root, place!!.root)
        assertEquals(File(temp.root, "Adult"), place.part)
        assertEquals(File(temp.root, "Adult/renpy/NewGame"), place.folder)
    }

    @Test
    fun `placeOf takes the deepest root and refuses what a walk would not enter`() {
        val inner = File(temp.root, "Games/Steam").also { it.mkdirs() }
        program("Games/Steam/Platformer")
        val deepest = GameEngineDetector.placeOf(File(inner, "Platformer"), listOf(temp.root, inner), emptyMap())
        assertEquals(inner, deepest!!.root)
        assertEquals(File(inner, "Platformer"), deepest.part)

        program("Games/.sync/Game")
        assertNull("a hidden folder on the way", GameEngineDetector.placeOf(File(temp.root, "Games/.sync/Game"), listOf(temp.root), emptyMap()))
        program("a/b/c/d/e/Deep")
        assertNull("past the depth bound", GameEngineDetector.placeOf(File(temp.root, "a/b/c/d/e/Deep"), listOf(temp.root), emptyMap()))
        assertNull("the root itself is a walk", GameEngineDetector.placeOf(temp.root, listOf(temp.root), emptyMap()))
    }

    @Test
    fun `a part or version folder costs the depth bound nothing`() {
        program("Games/Title/Chap3+/12.0-scrappy/Deep")
        assertNotNull(GameEngineDetector.placeOf(File(temp.root, "Games/Title/Chap3+/12.0-scrappy/Deep"), listOf(temp.root), emptyMap()))
    }
}
