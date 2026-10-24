package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which linked source an update row shows the downloads of (docs/SPEC.md 7g, "Facets in place"; Droidtop/tracker#639). */
class UpdateLinkTest {
    private fun link(source: String, id: String, version: String?, gone: Boolean = false) =
        SourceLink(source, id, version?.let { SourceAnswer(version = it, checkedAtEpochMs = 0L, gone = gone) })

    @Test
    fun `the link that gives the newest version is the one shown`() {
        val links = GameLinks(sources = listOf(link("a.forum", "1", "0.9"), link("b.site", "7", "1.2")))
        assertEquals("b.site", links.updateLink?.source)
    }

    @Test
    fun `a computer's link has no page, so the source with one is shown even when the computer is newer`() {
        val links = GameLinks(sources = listOf(link("computer:pc1", "Game", "2.0"), link("f95", "42", "1.0")))
        assertEquals("f95", links.updateLink?.source)
    }

    @Test
    fun `with no version answered the first link is shown, and with no link nothing is`() {
        assertEquals("f95", GameLinks(sources = listOf(link("f95", "42", null))).updateLink?.source)
        assertNull(GameLinks().updateLink)
    }
}
