package dev.droidtop.app

import dev.droidtop.pluginhost.TileState
import dev.droidtop.pluginhost.WidgetTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a Plugin widget draws for each C10 template and for a tile (docs/plugin-api.md 3 C10, Droidtop/tracker#563). */
class PluginWidgetContentTest {
    @Test
    fun aValueIsItsTitleAndOneRow() {
        val c = PluginWidgetContent.of(WidgetTemplate.Value("VPN", "Connected", "Home", null, "open"), "Net")
        assertEquals("VPN", c.title)
        assertEquals(listOf("Connected · Home"), c.lines.map { it.text })
        assertEquals("open", c.action)
    }

    @Test
    fun aListKeepsItsItemsActionsAndStopsAtTheRendererRows() {
        val items = (1..6).map { WidgetTemplate.Item("i$it", "Item $it", null, "v$it", if (it == 2) "go2" else null) }
        val c = PluginWidgetContent.of(WidgetTemplate.ListOf(null, items, null, null), "Source")
        assertEquals("Source", c.title)
        assertEquals(DroidtopWidgets.MAX_ROWS, c.lines.size)
        assertEquals("Item 1 · v1", c.lines[0].text)
        assertEquals("go2", c.lines[1].action)
        assertNull(c.lines[0].action)
    }

    @Test
    fun anEmptyListSaysWhatThePluginSaid() {
        val c = PluginWidgetContent.of(WidgetTemplate.ListOf("Queue", emptyList(), "Nothing queued", null), "S")
        assertTrue(c.lines.isEmpty())
        assertEquals("Nothing queued", c.empty)
    }

    @Test
    fun aGaugeShowsItsPercentOrThatItIsWorking() {
        assertEquals("42% · Syncing", PluginWidgetContent.of(WidgetTemplate.Gauge("Sync", 42, "Syncing", null), "S").lines.single().text)
        assertEquals("Working", PluginWidgetContent.of(WidgetTemplate.Gauge("Sync", -1, null, null), "S").lines.single().text)
    }

    @Test
    fun mediaButtonsAreRowsThatSendTheirIds() {
        val c = PluginWidgetContent.of(
            WidgetTemplate.Media("Song", "Artist", null, listOf(WidgetTemplate.Button("prev", "Back"), WidgetTemplate.Button("play", "Play")), null),
            "S",
        )
        assertEquals(listOf("Artist", "Back", "Play"), c.lines.map { it.text })
        assertEquals(listOf(null, "prev", "play"), c.lines.map { it.action })
    }

    @Test
    fun aQuickTileRowIsItsPressAndAStatusTileRowIsNot() {
        val quick = PluginWidgetContent.tile("Wi-Fi", TileState("Wi-Fi", null, true, null), quick = true)
        assertEquals("Wi-Fi · On", quick.lines.single().text)
        assertTrue(quick.lines.single().press)
        val status = PluginWidgetContent.tile("Battery", TileState("Battery", "80%", null, null), quick = false)
        assertEquals("Battery · 80%", status.lines.single().text)
        assertFalse(status.lines.single().press)
        assertEquals("Battery", PluginWidgetContent.tile("Battery", null, quick = false).lines.single().text)
    }
}
