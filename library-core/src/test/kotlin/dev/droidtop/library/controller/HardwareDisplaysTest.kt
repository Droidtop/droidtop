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
    fun aRowThatDoesNotSayClaimsNothing() {
        assertEquals(null, row("""[{"role": "internal", "width": 1080, "height": 1920}]""").addonOnTop)
        assertEquals(null, HardwareDatabase.parse("""{"devices": {"d": {"match": {"model": "M"}}}}""").single().addonOnTop)
    }
}
