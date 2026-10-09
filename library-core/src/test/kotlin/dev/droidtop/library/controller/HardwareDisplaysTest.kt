package dev.droidtop.library.controller

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a hardware row says the add-on display sits (hardware/_meta.json `displays[].position`, Droidtop/tracker#258). */
class HardwareDisplaysTest {
    private fun row(displays: String) =
        HardwareDatabase.parse("""{"devices": {"d": {"match": {"model": "M"}, "displays": $displays}}}""").single()

    @Test
    fun theAddOnsOwnPositionIsRead() {
        assertEquals(true, row("""[{"role": "internal", "position": "bottom"}, {"role": "addon", "position": "top"}]""").addonOnTop)
        assertEquals(false, row("""[{"role": "addon", "position": "bottom"}]""").addonOnTop)
    }

    @Test
    fun theInternalPanelsPositionImpliesTheOtherSide() {
        assertEquals(true, row("""[{"role": "internal", "position": "bottom"}]""").addonOnTop)
    }

    @Test
    fun everyPanelIsKeptForNamingScreens() {
        // AYN Thor: two built-in panels, each with its own size and position.
        val panels = row("""[{"role": "internal", "width": 1920, "height": 1080, "position": "top"}, {"role": "internal", "width": 1240, "height": 1080, "position": "bottom"}]""").panels
        assertEquals(
            listOf(
                dev.droidtop.runtime.ScreenPanel(builtIn = true, position = "top", widthPx = 1920, heightPx = 1080),
                dev.droidtop.runtime.ScreenPanel(builtIn = true, position = "bottom", widthPx = 1240, heightPx = 1080),
            ),
            panels,
        )
    }

    @Test
    fun aRowThatDoesNotSayClaimsNothing() {
        assertEquals(null, row("""[{"role": "internal", "width": 1080, "height": 1920}]""").addonOnTop)
        assertEquals(null, HardwareDatabase.parse("""{"devices": {"d": {"match": {"model": "M"}}}}""").single().addonOnTop)
    }
}
