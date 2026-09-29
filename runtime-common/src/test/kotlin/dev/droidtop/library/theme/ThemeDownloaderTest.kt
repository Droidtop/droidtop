package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The raw-file index route's own two pure pieces (docs/SPEC.md 7b,
 * "fetched as one raw HTTPS file"): the GitLab raw URL it builds --
 * the index repo, pinned to its real default branch, with the real
 * screenshot paths themes.json carries (one entry has double slashes)
 * -- and the one-time cleanup that drops an old full clone's dead bulk
 * (`.git` history, `screenshots/`) while keeping the list file itself.
 */
class ThemeDownloaderTest {

    @Test
    fun `the index file comes from the repo's raw endpoint on its default branch`() {
        assertEquals(
            "https://gitlab.com/es-de/themes/themes-list/-/raw/master/themes.json",
            ThemeDownloader.themesListFileUrl("themes.json"),
        )
    }

    @Test
    fun `screenshot paths keep only their real segments`() {
        // catppuccin-es-de's own entry in the real themes.json carries
        // the double slashes; a File child path tolerated them, a URL
        // must not.
        assertEquals(
            "https://gitlab.com/es-de/themes/themes-list/-/raw/master/screenshots/catppuccin-es-de/catppuccin-es-de_01.jpg",
            ThemeDownloader.themesListFileUrl("screenshots//catppuccin-es-de//catppuccin-es-de_01.jpg"),
        )
    }

    @Test
    fun `characters a URI may not carry verbatim are percent-encoded, not dropped`() {
        assertEquals(
            "https://gitlab.com/es-de/themes/themes-list/-/raw/master/screenshots/some%20theme/a%20b.jpg",
            ThemeDownloader.themesListFileUrl("screenshots/some theme/a b.jpg"),
        )
    }

    @Test
    fun `the one-time cleanup drops the old clone's history and screenshots, not the list`() {
        val dir = Files.createTempDirectory("themes-list").toFile()
        try {
            File(dir, "themes.json").writeText("{}")
            File(dir, ".git").apply { mkdirs() }.let { File(it, "config").writeText("[core]") }
            File(dir, "screenshots").apply { mkdirs() }.let { File(it, "one.jpg").writeText("x") }
            ThemeDownloader.deleteLegacyCloneLeftovers(dir)
            assertTrue(File(dir, "themes.json").isFile)
            assertFalse(File(dir, ".git").exists())
            assertFalse(File(dir, "screenshots").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
