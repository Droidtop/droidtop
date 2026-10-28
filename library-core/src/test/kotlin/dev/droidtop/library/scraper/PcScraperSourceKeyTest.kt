package dev.droidtop.library.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a stored PC source choice names, which "Rescrape with…" passes the same way (Droidtop/tracker#67). */
class PcScraperSourceKeyTest {
    @Test fun `a built-in key names its source, and anything else is Lutris`() {
        assertEquals(PcScraperSource.IGDB, PcScraperSourcePrefs.builtin("igdb"))
        assertEquals(PcScraperSource.LUTRIS, PcScraperSourcePrefs.builtin(null))
        assertEquals(PcScraperSource.LUTRIS, PcScraperSourcePrefs.builtin("plugin:dev.example.meta"))
    }

    @Test fun `a plugin key names its plugin, and a built-in key none`() {
        assertEquals("dev.example.meta", PcScraperSourcePrefs.pluginOf("plugin:dev.example.meta"))
        assertNull(PcScraperSourcePrefs.pluginOf("steamgriddb"))
        assertNull(PcScraperSourcePrefs.pluginOf("plugin:"))
        assertNull(PcScraperSourcePrefs.pluginOf(null))
    }
}
