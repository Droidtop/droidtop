package dev.droidtop.shell.gamepad.theme

import dev.droidtop.library.GameMediaLocator
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Real `CarouselComponent<T>::onDemandTextureLoad`
 * (CarouselComponent.h:549-580; `GridComponent.h:447-520` is the same
 * code) as the per-entry image chain, including the two halves droidtop
 * used to get wrong: the implicit `marquee` when a gamelist element
 * declares no `imageType` (CarouselComponent.h:485-486), and the chain
 * ENDING at `defaultImage` rather than falling back to the entry's
 * pre-resolved artwork (CarouselComponent.h:577-578).
 */
class EsDePrimaryImageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun media(type: String, name: String = "game"): String {
        val dir = File(temp.root, "downloaded_media/nes/$type")
        dir.mkdirs()
        val file = File(dir, "$name.png")
        file.writeText("x")
        return file.absolutePath
    }

    private fun gameItem(logo: String? = "/pre/resolved/cover.png") = EsDeListItem(
        key = "g",
        label = "Game",
        logoPath = logo,
        onSelect = {},
        mediaLocator = GameMediaLocator(temp.root.absolutePath, "nes", "game"),
    )

    @Test
    fun `the theme's own order wins, not a fixed preference`() {
        val marquee = media("marquees")
        media("covers")
        assertEquals(marquee, gameItem().esDePrimaryImage(listOf("marquee", "cover"), null, gamelist = true))
    }

    @Test
    fun `a later type answers when the first has no file`() {
        val cover = media("covers")
        assertEquals(cover, gameItem().esDePrimaryImage(listOf("marquee", "cover"), null, gamelist = true))
    }

    @Test
    fun `a gamelist element with no imageType means marquee, not the pre-resolved artwork`() {
        val marquee = media("marquees")
        assertEquals(marquee, gameItem().esDePrimaryImage(emptyList(), null, gamelist = true))
        // Nothing scraped as a marquee: the chain ends, it does not reach
        // the cover that logoPath happens to point at.
        val bare = EsDeListItem(
            key = "g",
            label = "Game",
            logoPath = "/pre/resolved/cover.png",
            onSelect = {},
            mediaLocator = GameMediaLocator(File(temp.root, "empty").absolutePath, "nes", "game"),
        )
        assertNull(bare.esDePrimaryImage(emptyList(), null, gamelist = true))
    }

    @Test
    fun `a miss ends at defaultImage, never at the pre-resolved artwork`() {
        assertEquals(
            "/theme/default.png",
            gameItem().esDePrimaryImage(listOf("marquee"), "/theme/default.png", gamelist = true),
        )
        assertNull(gameItem().esDePrimaryImage(listOf("marquee"), null, gamelist = true))
    }

    @Test
    fun `none breaks the walk and still lands on defaultImage`() {
        media("covers")
        // ES-DE breaks out of the loop at `none` and falls into the same
        // empty-path default assignment, so the cover after it is never
        // tried.
        assertEquals(
            "/theme/default.png",
            gameItem().esDePrimaryImage(listOf("none", "cover"), "/theme/default.png", gamelist = true),
        )
    }

    @Test
    fun `a system entry keeps its logo, because ES-DE never runs the walk for one`() {
        media("marquees")
        val system = EsDeListItem(key = "s", label = "NES", logoPath = "/theme/nes.svg", onSelect = {})
        assertEquals("/theme/nes.svg", system.esDePrimaryImage(emptyList(), "/theme/default.png", gamelist = false))
        assertEquals(
            "/theme/default.png",
            EsDeListItem(key = "s", label = "NES", logoPath = null, onSelect = {})
                .esDePrimaryImage(emptyList(), "/theme/default.png", gamelist = false),
        )
    }
}
