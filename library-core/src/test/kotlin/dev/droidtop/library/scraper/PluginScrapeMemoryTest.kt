package dev.droidtop.library.scraper

import dev.droidtop.library.consoles.GameMetadataEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fewer requests to a metadata plugin's site (docs/SPEC.md 7h, "Fewer requests"). */
class PluginScrapeMemoryTest {
    @Test
    fun `a source's no match holds for a week`() {
        val now = 10L * PluginScrapeMemory.MISS_KEEP_MS
        assertTrue(PluginScrapeMemory.fresh(now - 1000, now))
        assertFalse(PluginScrapeMemory.fresh(now - PluginScrapeMemory.MISS_KEEP_MS, now))
        assertFalse(PluginScrapeMemory.fresh(null, now))
        // A stamp from a clock set ahead is not trusted.
        assertFalse(PluginScrapeMemory.fresh(now + 1000, now))
    }

    @Test
    fun `a game with text and no cover is still asked, one with everything is not`() {
        assertTrue(PluginScrapeMemory.wantsPluginFields(null))
        val text = GameMetadataEntity(id = "g", description = "d", developer = "dev", genre = "Ren'Py")
        assertTrue(PluginScrapeMemory.wantsPluginFields(text))
        val cover = java.io.File.createTempFile("cover", ".png").apply { deleteOnExit() }
        assertFalse(PluginScrapeMemory.wantsPluginFields(text.copy(artworkPath = cover.path)))
        assertTrue(PluginScrapeMemory.wantsPluginFields(text.copy(artworkPath = cover.path, developer = null)))
    }
}
