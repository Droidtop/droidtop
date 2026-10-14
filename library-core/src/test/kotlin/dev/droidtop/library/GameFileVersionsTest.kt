package dev.droidtop.library

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The version a game's own files declare (Droidtop/tracker#406). */
class GameFileVersionsTest {
    @Test
    fun `Ren'Py's config version is read, comments and other defines aside`() {
        val text = """
            ## The version of the game.
            # define config.version = "0.1"
            define config.name = _("A Game")
            define config.version = "0.9.5b"
        """.trimIndent()
        assertEquals("0.9.5b", GameFileVersions.fromRenPyOptions(text))
        assertEquals("1.2", GameFileVersions.fromRenPyOptions("define config.version = '1.2'"))
        assertNull(GameFileVersions.fromRenPyOptions("define config.version = \"\""))
        assertNull(GameFileVersions.fromRenPyOptions("define build.name = \"Game\""))
    }

    @Test
    fun `an RPG Maker package json names a version only when the developer set one`() {
        assertEquals("1.0.3", GameFileVersions.fromPackageJson("""{"name":"Game","main":"index.html","version":"1.0.3"}"""))
        assertNull(GameFileVersions.fromPackageJson("""{"name":"KADOKAWA/RPGMV","main":"index.html"}"""))
        assertNull(GameFileVersions.fromPackageJson("not json"))
    }

    @Test
    fun `a folder is read for options rpy first, then package json at its root or under www`() {
        val root = Files.createTempDirectory("gfv").toFile()
        try {
            val renpy = File(root, "renpy").apply { File(this, "game").mkdirs() }
            File(renpy, "game/options.rpy").writeText("define config.version = \"2.0\"\n")
            assertEquals("2.0", GameFileVersions.read(renpy))

            val mv = File(root, "mv").apply { File(this, "www").mkdirs() }
            File(mv, "www/package.json").writeText("""{"version":"1.1"}""")
            assertEquals("1.1", GameFileVersions.read(mv))

            assertNull(GameFileVersions.read(File(root, "missing")))
        } finally {
            root.deleteRecursively()
        }
    }
}
