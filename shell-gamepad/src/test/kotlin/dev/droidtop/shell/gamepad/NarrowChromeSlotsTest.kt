package dev.droidtop.shell.gamepad

import org.junit.Assert.assertEquals
import org.junit.Test

class NarrowChromeSlotsTest {
    @Test
    fun keepsSelectedTabRoomByDroppingSideSlotsInPriorityOrder() {
        assertEquals(
            NarrowChromeSlots(context = false, shoulders = false, status = true, quickMenu = true),
            narrowChromeSlots(
                availableWidthDp = 250f,
                selectedTabWidthDp = 110f,
                contextWidthDp = 40f,
                shoulderWidthDp = 88f,
                statusWidthDp = 120f,
                quickMenuWidthDp = 52f,
            ),
        )
    }

    @Test
    fun dropsEverySideSlotWhenNeededToKeepTheSelectedTabWhole() {
        assertEquals(
            NarrowChromeSlots(context = false, shoulders = false, status = false, quickMenu = false),
            narrowChromeSlots(
                availableWidthDp = 140f,
                selectedTabWidthDp = 140f,
                contextWidthDp = 40f,
                shoulderWidthDp = 88f,
                statusWidthDp = 120f,
                quickMenuWidthDp = 52f,
            ),
        )
    }
}
