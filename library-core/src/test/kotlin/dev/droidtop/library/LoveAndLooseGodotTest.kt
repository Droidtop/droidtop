package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** LOVE games without a program, single-file Godot builds and Flashpoint downloads, on synthetic trees. */
class LoveAndLooseGodotTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val defs = EngineRegistryParser.parse(SeedAssets.read("engines-database.json"))

    private fun file(path: String, text: String = "x") {
        val f = File(tmp.root, path)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    /** A program whose last 12 bytes are Godot's embedded-pack trailer: the pack's offset, then `GDPC`. */
    private fun godotExport(path: String) {
        val f = File(tmp.root, path)
        f.parentFile?.mkdirs()
        val body = ByteArray(64) { 7 }
        val offset = ByteArray(8).also { it[0] = 16 }
        f.writeBytes(body + offset + "GDPC".toByteArray())
    }

    @Test
    fun `a LOVE folder with no program is a game`() {
        file("Manual/love2d/Thermomorph/main.lua", "love.load = function() end")
        file("Manual/love2d/Thermomorph/conf.lua", "function love.conf(t)\n  t.title = 'x'\nend")
        file("Manual/love2d/Mari0/game.love", "PK")

        val found = GameEngineDetector.scan(tmp.root, emptyMap(), defs)

        assertEquals(listOf("Mari0", "Thermomorph"), found.map { it.displayFolder.name }.sorted())
        assertTrue(found.all { it.engine == GameEngine.LOVE2D })
    }

    @Test
    fun `a main lua without love conf is not a LOVE game`() {
        file("Other/Thing/main.lua", "print('hi')")
        assertEquals(emptyList<DetectedGame>(), GameEngineDetector.scan(tmp.root, emptyMap(), defs))
    }

    @Test
    fun `a single-file Godot export beside Godot games is a game of its own`() {
        file("godot/Goodbye Eternity/game.pck")
        file("godot/Coffee_v1.2-windows/game.pck")
        godotExport("godot/Coffee-1.0-linux.x86_64")

        val found = GameEngineDetector.scan(tmp.root, emptyMap(), defs)

        assertEquals(
            listOf("Coffee-1.0-linux.x86_64", "Coffee_v1.2-windows", "Goodbye Eternity"),
            found.map { it.displayFolder.name }.sorted(),
        )
        val single = found.single { it.displayFolder.isFile }
        assertEquals(GameEngine.GODOT, single.engine)
        assertEquals(single.displayFolder, single.gameRoot)
    }

    @Test
    fun `a single-file build is its own Linux program for the runner`() {
        godotExport("godot/Coffee-1.0-linux.x86_64")
        val facts = GameLaunchStrategyResolver.facts(GameEngine.GODOT, File(tmp.root, "godot/Coffee-1.0-linux.x86_64"))
        assertTrue(facts.hasLinuxBuild)
    }

    @Test
    fun `a program without Godot's trailer beside Godot games is not a game`() {
        file("godot/A/game.pck")
        file("godot/B/game.pck")
        file("godot/tool.x86_64", "ELF")
        val found = GameEngineDetector.scan(tmp.root, emptyMap(), defs)
        assertEquals(listOf("A", "B"), found.map { it.displayFolder.name }.sorted())
    }

    @Test
    fun `a Flashpoint install lists the games it downloaded, named from its database`() {
        file("Flashpoint/version.txt", "Flashpoint 14.0.3 Infinity - Kingfisher\n")
        File(tmp.root, "Flashpoint/FPSoftware").mkdirs()
        file("Flashpoint/Data/Games/1a394056-abac-9481-7121-1c89417a6666-1651458251057.zip", "zip")
        file("Flashpoint/Data/Games/not-a-game.txt")
        file("Flashpoint/Data/Games/212b79c7-d3d0-47b4-af41-c776244aafad-1687957096040.zip", "zip")

        val downloads = FlashpointInstall.downloads(File(tmp.root, "Flashpoint")) { _, _ ->
            mapOf("1a394056-abac-9481-7121-1c89417a6666" to "A Flash Game")
        }

        assertEquals(
            listOf("A Flash Game", "212b79c7-d3d0-47b4-af41-c776244aafad"),
            downloads.map { it.title },
        )
    }

    @Test
    fun `a folder that is not a Flashpoint install has no downloads`() {
        file("Other/Data/Games/1a394056-abac-9481-7121-1c89417a6666-1.zip", "zip")
        assertEquals(emptyList<FlashpointInstall.Download>(), FlashpointInstall.downloads(File(tmp.root, "Other")))
        assertEquals("1a394056-abac-9481-7121-1c89417a6666", FlashpointInstall.gameId("1a394056-abac-9481-7121-1c89417a6666-1.zip"))
        assertEquals(null, FlashpointInstall.gameId("readme.zip"))
    }
}
