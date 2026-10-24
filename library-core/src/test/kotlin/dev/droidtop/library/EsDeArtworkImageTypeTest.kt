package dev.droidtop.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Real `<imageType>` RESOLUTION, checked against real ES-DE's own
 * behaviour rather than against droidtop's recorded output:
 * GamelistView.cpp:1255-1330 (walk the theme's declared order, first
 * type that has a file wins, stop), FileData.cpp:332-358 (the
 * `<media root>/<system>/<subdir>/<name>.<ext>` layout and the
 * extension walk (FileData.h:161: png, jpg, webp)) and FileData.cpp:381-431
 * (the one-to-one type -> subdirectory names). Two rules are droidtop's
 * own (owner, Droidtop/tracker#632, "we never want screenshots to be
 * what's displayed first"): screenshots and title screens come after any
 * other art a slot accepts, and the `image` pseudo-type is droidtop's own
 * order rather than ES-DE's miximage-then-screenshot chain.
 */
class EsDeArtworkImageTypeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var gamesRoot: File

    private fun media(system: String, subdir: String, name: String, ext: String = "png"): File {
        val f = File(gamesRoot, "downloaded_media/$system/$subdir/$name.$ext")
        f.parentFile.mkdirs()
        f.writeText("x")
        // What every droidtop media writer does after writing; without it
        // the folder's listing from an earlier lookup stands for up to
        // its revalidation interval.
        EsDeArtwork.mediaWritten(f)
        return f
    }

    @Test
    fun `a file written after a lookup is found once the writer says so`() {
        setUpRoot()
        assertNull(EsDeArtwork.resolveImageTypes(locator(), listOf("cover")))
        val cover = File(gamesRoot, "downloaded_media/megadrive/covers/Sonic.png")
        cover.parentFile.mkdirs()
        cover.writeText("x")
        EsDeArtwork.mediaWritten(cover)
        assertEquals(cover.absolutePath, EsDeArtwork.resolveImageTypes(locator(), listOf("cover")))
    }

    @Test
    fun `a name differing only in case still matches, as it does on Android shared storage`() {
        setUpRoot()
        val cover = media("megadrive", "covers", "SONIC", ext = "PNG")
        assertEquals(cover.absolutePath, EsDeArtwork.resolveImageTypes(locator(), listOf("cover")))
    }

    private fun locator(name: String = "Sonic") = GameMediaLocator(gamesRoot.absolutePath, "megadrive", name)

    private fun setUpRoot() {
        gamesRoot = temp.newFolder("Roms")
    }

    @Test
    fun `first declared type that exists wins`() {
        setUpRoot()
        val marquee = media("megadrive", "marquees", "Sonic")
        val cover = media("megadrive", "covers", "Sonic")
        assertEquals(
            marquee.absolutePath,
            EsDeArtwork.resolveImageTypes(locator(), listOf("marquee", "cover")),
        )
        assertEquals(
            cover.absolutePath,
            EsDeArtwork.resolveImageTypes(locator(), listOf("cover", "marquee")),
        )
    }

    @Test
    fun `a missing preferred type falls through to the next declared one, not to a fixed priority`() {
        setUpRoot()
        val screenshot = media("megadrive", "screenshots", "Sonic")
        // A miximage exists too. droidtop's own default priority would
        // pick it; the theme asked for marquee then screenshot, so the
        // screenshot is the right answer.
        media("megadrive", "miximages", "Sonic")
        assertEquals(
            screenshot.absolutePath,
            EsDeArtwork.resolveImageTypes(locator(), listOf("marquee", "screenshot")),
        )
    }

    @Test
    fun `nothing declared exists resolves to null, it does not silently substitute the cover`() {
        setUpRoot()
        media("megadrive", "covers", "Sonic")
        assertNull(EsDeArtwork.resolveImageTypes(locator(), listOf("marquee", "3dbox")))
    }

    @Test
    fun `the image pseudo-type is droidtop's order, box art before screens`() {
        setUpRoot()
        // ES-DE's own chain would pick the title screen here; droidtop's order picks the cover.
        val titlescreen = media("megadrive", "titlescreens", "Sonic")
        assertEquals(titlescreen.absolutePath, EsDeArtwork.resolveImageTypes(locator(), listOf("image")))
        val cover = media("megadrive", "covers", "Sonic")
        assertEquals(cover.absolutePath, EsDeArtwork.resolveImageTypes(locator(), listOf("image")))
        // ...and a miximage does not outrank the cover.
        media("megadrive", "miximages", "Sonic")
        assertEquals(cover.absolutePath, EsDeArtwork.resolveImageTypes(locator(), listOf("image")))
    }

    @Test
    fun `a theme's screenshot-first order shows the cover first`() {
        setUpRoot()
        // DEcaffe's own `screenshot,cover,titlescreen`.
        media("megadrive", "screenshots", "Sonic")
        val cover = media("megadrive", "covers", "Sonic")
        assertEquals(cover.absolutePath, EsDeArtwork.resolveImageTypes(locator(), listOf("screenshot", "cover", "titlescreen")))
        assertEquals(listOf("covers", "screenshots", "titlescreens"), EsDeArtwork.gameArtFolders(listOf("screenshot", "cover", "titlescreen")))
    }

    @Test
    fun `a slot that asks only for screens still gets the screenshot`() {
        setUpRoot()
        val screenshot = media("megadrive", "screenshots", "Sonic")
        media("megadrive", "covers", "Sonic")
        assertEquals(screenshot.absolutePath, EsDeArtwork.resolveImageTypes(locator(), listOf("screenshot", "titlescreen")))
    }

    @Test
    fun `a screenshot or title screen is known by its media folder`() {
        setUpRoot()
        assertEquals(true, EsDeArtwork.isScreenshot(media("megadrive", "screenshots", "Sonic").absolutePath))
        assertEquals(true, EsDeArtwork.isScreenshot(media("megadrive", "titlescreens", "Sonic").absolutePath))
        assertEquals(false, EsDeArtwork.isScreenshot(media("megadrive", "covers", "Sonic").absolutePath))
        assertEquals(false, EsDeArtwork.isScreenshot(media("megadrive", "fanart", "Sonic").absolutePath))
    }

    @Test
    fun `the image pseudo-type never reaches marquee`() {
        setUpRoot()
        media("megadrive", "marquees", "Sonic")
        assertNull(EsDeArtwork.resolveImageTypes(locator(), listOf("image")))
    }

    @Test
    fun `3dbox and backcover resolve to their real subdirectory names`() {
        setUpRoot()
        val box = media("megadrive", "3dboxes", "Sonic")
        assertEquals(box.absolutePath, EsDeArtwork.resolveImageTypes(locator(), listOf("3dbox")))
        val back = media("megadrive", "backcovers", "Sonic")
        assertEquals(back.absolutePath, EsDeArtwork.resolveImageTypes(locator(), listOf("backcover")))
    }

    @Test
    fun `jpg is found as well as png`() {
        setUpRoot()
        val cover = media("megadrive", "covers", "Sonic", ext = "jpg")
        assertEquals(cover.absolutePath, EsDeArtwork.resolveImageTypes(locator(), listOf("cover")))
    }

    @Test
    fun `the portable sibling media root is searched too`() {
        setUpRoot()
        val f = File(gamesRoot.parentFile, "ES-DE/downloaded_media/megadrive/marquees/Sonic.png")
        f.parentFile.mkdirs()
        f.writeText("x")
        assertEquals(f.absolutePath, EsDeArtwork.resolveImageTypes(locator(), listOf("marquee")))
    }

    @Test
    fun `the theme's type order outranks which media root a file sits in`() {
        setUpRoot()
        // A cover in the sibling ES-DE root, a marquee in the root under
        // the games folder. The theme asked for marquee first, so the
        // marquee wins even though the other root is searched first for
        // any single given type.
        val siblingCover = File(gamesRoot.parentFile, "ES-DE/downloaded_media/megadrive/covers/Sonic.png")
        siblingCover.parentFile.mkdirs()
        siblingCover.writeText("x")
        val marquee = media("megadrive", "marquees", "Sonic")
        assertEquals(
            marquee.absolutePath,
            EsDeArtwork.resolveImageTypes(locator(), listOf("marquee", "cover")),
        )
    }

    @Test
    fun `an empty type list resolves to nothing at all`() {
        setUpRoot()
        media("megadrive", "covers", "Sonic")
        assertNull(EsDeArtwork.resolveImageTypes(locator(), emptyList()))
    }

    @Test
    fun `none names no media and simply does not match`() {
        setUpRoot()
        val cover = media("megadrive", "covers", "Sonic")
        assertEquals(
            cover.absolutePath,
            EsDeArtwork.resolveImageTypes(locator(), listOf("none", "cover")),
        )
    }

    @Test
    fun `an entry without a media locator answers null for every type`() {
        setUpRoot()
        val entry = LibraryEntry(
            id = "x",
            title = "Sonic",
            kind = LibraryEntryKind.CONSOLE_ROM,
            artworkUri = "/somewhere/cover.png",
        )
        assertNull(entry.mediaForImageTypes(listOf("marquee")))
    }

    @Test
    fun `an entry with a locator resolves through it`() {
        setUpRoot()
        val marquee = media("megadrive", "marquees", "Sonic")
        val entry = LibraryEntry(
            id = "x",
            title = "Sonic",
            kind = LibraryEntryKind.CONSOLE_ROM,
            artworkUri = "/somewhere/cover.png",
            mediaLocator = locator(),
        )
        assertEquals(marquee.absolutePath, entry.mediaForImageTypes(listOf("marquee")))
        assertNull(entry.mediaForImageTypes(listOf("fanart")))
    }
}
