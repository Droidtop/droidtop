package dev.droidtop.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * droidtop's own card art order (owner, 2026-10-23, Droidtop/tracker#632): box art first, a screenshot only when
 * nothing else exists.
 */
class EsDeArtworkOrderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val gamesRoot: File by lazy { temp.newFolder("Roms") }

    private fun media(subdir: String, name: String = "Sonic"): String {
        val f = File(gamesRoot, "downloaded_media/megadrive/$subdir/$name.png")
        f.parentFile.mkdirs()
        f.writeText("x")
        EsDeArtwork.mediaWritten(f)
        return f.absolutePath
    }

    private fun card(name: String = "Sonic") = EsDeArtwork.resolve(gamesRoot, "megadrive", name)

    @Test
    fun `box art leads, over a miximage and a screenshot`() {
        media("screenshots")
        media("miximages")
        val cover = media("covers")
        assertEquals(cover, card())
    }

    @Test
    fun `a 3D box comes next, then a miximage, before any screenshot`() {
        media("screenshots", "A")
        media("titlescreens", "A")
        val mix = media("miximages", "A")
        assertEquals(mix, card("A"))
        val box = media("3dboxes", "A")
        assertEquals(box, card("A"))
    }

    @Test
    fun `art that is not a screenshot comes before the title screen and the screenshot`() {
        media("screenshots", "B")
        media("titlescreens", "B")
        val fanart = media("fanart", "B")
        assertEquals(fanart, card("B"))
    }

    @Test
    fun `a screenshot is the card only when nothing else exists`() {
        val shot = media("screenshots", "C")
        assertEquals(shot, card("C"))
    }

    @Test
    fun `only box art counts as a cover, so a game with a screenshot still gets one scraped`() {
        media("screenshots", "D")
        assertFalse(EsDeArtwork.hasCover(gamesRoot, "megadrive", "D"))
        media("3dboxes", "D")
        assertTrue(EsDeArtwork.hasCover(gamesRoot, "megadrive", "D"))
    }
}
