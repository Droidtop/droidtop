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
 * extension walk (FileData.h:161: png, jpg, webp)), FileData.cpp:360-379 (the `image`
 * pseudo-type's own miximage -> screenshot -> titlescreen -> cover
 * chain) and FileData.cpp:381-431 (the one-to-one type -> subdirectory
 * names for everything else).
 */
class EsDeArtworkImageTypeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var gamesRoot: File

    private fun media(system: String, subdir: String, name: String, ext: String = "png"): File {
        val f = File(gamesRoot, "downloaded_media/$system/$subdir/$name.$ext")
        f.parentFile.mkdirs()
        f.writeText("x")
        return f
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
    fun `the image pseudo-type is its own fallback chain`() {
        setUpRoot()
        // FileData::getImagePath order: miximage, screenshot,
        // titlescreen, cover. With only a titlescreen and a cover
        // present, "image" must pick the titlescreen.
        val titlescreen = media("megadrive", "titlescreens", "Sonic")
        media("megadrive", "covers", "Sonic")
        assertEquals(
            titlescreen.absolutePath,
            EsDeArtwork.resolveImageTypes(locator(), listOf("image")),
        )
        // ...and a miximage outranks all three.
        val mix = media("megadrive", "miximages", "Sonic")
        assertEquals(mix.absolutePath, EsDeArtwork.resolveImageTypes(locator(), listOf("image")))
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
