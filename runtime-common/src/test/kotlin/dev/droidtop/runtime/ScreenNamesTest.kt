package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/** How droidtop names screens (docs/SPEC.md 4c, "Screen names", Droidtop/tracker#162). */
class ScreenNamesTest {
    private fun screen(id: Int, name: String, cls: ScreenClass, w: Int, h: Int, unique: String = "local:$id") =
        ScreenFacts(id, unique, name, cls, w, h)

    // An AYN Thor-like device: two built-in panels (6" 1920x1080 above, 3.92" 1240x1080 below) and a monitor.
    private val top = screen(0, "Built-in Screen", ScreenClass.BUILT_IN, 1080, 1920)
    private val bottom = screen(3, "Built-in Screen 2", ScreenClass.BUILT_IN, 1080, 1240)
    private val monitor = screen(7, "DELL U2415", ScreenClass.EXTERNAL, 1920, 1200)
    private val thor = listOf(
        ScreenPanel(builtIn = true, position = "top", widthPx = 1920, heightPx = 1080),
        ScreenPanel(builtIn = true, position = "bottom", widthPx = 1240, heightPx = 1080),
    )

    @Test
    fun `a profile names two built-in screens by position, and a monitor keeps its own name`() {
        assertEquals(
            mapOf(0 to "Top screen", 3 to "Bottom screen", 7 to "DELL U2415"),
            ScreenNames.names(listOf(monitor, bottom, top), thor, emptyMap()),
        )
    }

    @Test
    fun `without a profile several built-in screens are numbered in display-id order`() {
        assertEquals(
            mapOf(0 to "Built-in screen 1", 3 to "Built-in screen 2", 7 to "DELL U2415"),
            ScreenNames.names(listOf(top, bottom, monitor), emptyList(), emptyMap()),
        )
        assertEquals(mapOf(0 to "Built-in screen"), ScreenNames.names(listOf(top), emptyList(), emptyMap()))
    }

    @Test
    fun `the Retroid add-on above is named by position only when it is the screen the profile describes`() {
        val rp5 = listOf(
            ScreenPanel(builtIn = true, position = "bottom", widthPx = 1080, heightPx = 1920),
            ScreenPanel(builtIn = false, position = "top", widthPx = 1080, heightPx = 1920),
        )
        val panel = screen(0, "Built-in Screen", ScreenClass.BUILT_IN, 1080, 1920)
        val addon = screen(9, "DP Screen", ScreenClass.EXTERNAL, 1080, 1920)
        val landscapeMonitor = screen(10, "DP Screen", ScreenClass.EXTERNAL, 1920, 1080)
        assertEquals(mapOf(0 to "Bottom screen", 9 to "Top screen"), ScreenNames.names(listOf(panel, addon), rp5, emptyMap()))
        // A monitor on the same port is not the add-on: it keeps its own name.
        assertEquals(mapOf(0 to "Bottom screen", 10 to "DP Screen"), ScreenNames.names(listOf(panel, landscapeMonitor), rp5, emptyMap()))
    }

    @Test
    fun `two screens with one name are numbered, and a person's own name wins`() {
        val a = screen(4, "HDMI Screen", ScreenClass.EXTERNAL, 1920, 1080)
        val b = screen(5, "HDMI Screen", ScreenClass.EXTERNAL, 1920, 1080)
        assertEquals(
            mapOf(0 to "Built-in screen", 4 to "HDMI Screen 1", 5 to "HDMI Screen 2"),
            ScreenNames.names(listOf(top, a, b), emptyList(), emptyMap()),
        )
        assertEquals(
            mapOf(0 to "Top screen", 3 to "Lower deck", 7 to "DELL U2415"),
            ScreenNames.names(listOf(top, bottom, monitor), thor, mapOf("local:3" to " Lower deck ")),
        )
    }

    @Test
    fun `built-in or external comes from Android's display type before any guess`() {
        assertEquals(ScreenClass.BUILT_IN, ScreenNames.classify(displayId = 3, androidType = 1, presentation = true))
        assertEquals(ScreenClass.EXTERNAL, ScreenNames.classify(displayId = 0, androidType = 2, presentation = false))
        assertEquals(ScreenClass.BUILT_IN, ScreenNames.classify(displayId = 0, androidType = null, presentation = false))
        assertEquals(ScreenClass.EXTERNAL, ScreenNames.classify(displayId = 9, androidType = null, presentation = true))
    }
}
