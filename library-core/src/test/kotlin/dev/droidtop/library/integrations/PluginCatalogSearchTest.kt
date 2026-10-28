package dev.droidtop.library.integrations

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The plugin store search is a filter over the cached index (Droidtop/tracker#485): every word must be somewhere in the plugin. */
class PluginCatalogSearchTest {
    private val plugin = PluginCatalogPlugin(
        id = "dev.droidtop.plugin.nyaa",
        label = "Nyaa",
        description = "Searches torrents for retro games",
        releases = emptyList(),
    )

    @Test
    fun `a blank query keeps every plugin`() {
        assertTrue(PluginCatalogScreen.matchesQuery(plugin, "droidtop", ""))
        assertTrue(PluginCatalogScreen.matchesQuery(plugin, "droidtop", "   "))
    }

    @Test
    fun `a word matches the name, the description, the id or the origin in any case`() {
        assertTrue(PluginCatalogScreen.matchesQuery(plugin, "droidtop", "NYAA"))
        assertTrue(PluginCatalogScreen.matchesQuery(plugin, "droidtop", "torrent"))
        assertTrue(PluginCatalogScreen.matchesQuery(plugin, "gamegrab-sources", "gamegrab"))
        assertTrue(PluginCatalogScreen.matchesQuery(plugin, "droidtop", "plugin.nyaa"))
    }

    @Test
    fun `every word has to match, not just one`() {
        assertTrue(PluginCatalogScreen.matchesQuery(plugin, "droidtop", "retro torrents"))
        assertFalse(PluginCatalogScreen.matchesQuery(plugin, "droidtop", "retro emulator"))
    }

    @Test
    fun `the Plugins page's search matches installed plugins the same way, skipping fields they lack`() {
        assertTrue(PluginCatalogScreen.matchesWords("nyaa", listOf("Nyaa", "dev.plugin.nyaa", null, "droidtop")))
        assertTrue(PluginCatalogScreen.matchesWords("  ", listOf(null)))
        assertFalse(PluginCatalogScreen.matchesWords("steam", listOf("Nyaa", "dev.plugin.nyaa", null, "droidtop")))
    }
}
