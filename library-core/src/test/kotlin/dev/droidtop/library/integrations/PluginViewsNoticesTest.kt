package dev.droidtop.library.integrations

import dev.droidtop.pluginhost.PluginViewLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** droidtop's own notices on plugin pages (Droidtop/tracker#521 items 6, 9 and 10). */
class PluginViewsNoticesTest {
    @Test
    fun `a finished job says so in droidtop's words, with the plugin's sentence as the body`() {
        val plain = PluginViews.finishedNotice(null)
        assertEquals(PluginViews.FINISHED_TITLE, plain.title)
        assertEquals("info", plain.tone)
        assertNull(plain.subtitle)
        assertNull("a bare Done adds nothing to Finished", PluginViews.finishedNotice(" done ").subtitle)
        assertEquals("Installed 3 cores", PluginViews.finishedNotice("Installed 3 cores").subtitle)
        val max = PluginViewLimits.length("noticeSubtitle")
        val long = PluginViews.finishedNotice("x".repeat(max + 50)).subtitle!!
        assertEquals(max, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun `droidtop's notice titles keep to the copy rules and are reserved from plugins`() {
        for (title in listOf(PluginViews.FINISHED_TITLE, PluginViews.EMPTY_TITLE, PluginCatalogScreen.NOTICE_TITLE, PluginCatalogScreen.CHANGED_NOTICE_TITLE)) {
            assertTrue(title, title.length <= 40 && !title.endsWith("."))
        }
        assertTrue(PluginCatalogScreen.NOTICE_TITLE.lowercase() in PluginViewLimits.reservedTitles)
        assertTrue(PluginCatalogScreen.CHANGED_NOTICE_TITLE.lowercase() in PluginViewLimits.reservedTitles)
    }
}
