package dev.droidtop.app

import dev.droidtop.runtime.systemstatus.NotificationsStore
import org.junit.Assert.assertEquals
import org.junit.Test

/** System > Display's cards and the message wake (CompanionDisplays.kt, CompanionScreenIdle.kt; slice C14). */
class CompanionDisplaysTest {
    @Test fun `each screen's role`() {
        assertEquals(DisplayRole.MAIN, displayRole(0, mainDisplayId = 0, companionDisplayId = 9))
        assertEquals(DisplayRole.COMPANION, displayRole(9, mainDisplayId = 0, companionDisplayId = 9))
        assertEquals(DisplayRole.OTHER, displayRole(11, mainDisplayId = 0, companionDisplayId = 9))
    }

    // Droidtop/tracker#786 S7: Android's brightness is on the built-in screen's card in every arrangement.
    @Test fun `the built-in screen always has Android's brightness`() {
        // Built-in is Main, companion on an add-on screen.
        assertEquals(listOf(BrightnessRow.ANDROID), brightnessRows(0, ownDisplay = 9, builtInId = 0))
        assertEquals(listOf(BrightnessRow.WINDOW), brightnessRows(9, ownDisplay = 9, builtInId = 0))
        // Companion on the built-in screen: both, Android's first.
        assertEquals(listOf(BrightnessRow.ANDROID, BrightnessRow.WINDOW), brightnessRows(0, ownDisplay = 0, builtInId = 0))
        // Main and companion both on add-on screens: the built-in screen still has it.
        assertEquals(listOf(BrightnessRow.ANDROID), brightnessRows(0, ownDisplay = 11, builtInId = 0))
        // Another external screen has none of droidtop's.
        assertEquals(emptyList<BrightnessRow>(), brightnessRows(11, ownDisplay = 9, builtInId = 0))
    }

    @Test fun `only a second screen in its safe mode shows the fault`() {
        assertEquals("480\u00D7640 of 1080\u00D71920", lowModeOf(true, true, "480\u00D7640 of 1080\u00D71920"))
        assertEquals(null, lowModeOf(true, false, "1080\u00D71920 of 1080\u00D71920"))
        assertEquals(null, lowModeOf(false, true, "480\u00D7640 of 1080\u00D71920"))
    }

    @Test fun `refresh rates are offered only when there is a choice`() {
        assertEquals(emptyList<Float>(), offeredRates(listOf(60f, 60.0001f)))
        assertEquals(listOf(120f, 90f, 60f), offeredRates(listOf(60f, 120f, 90f, 59.99f)))
    }

    @Test fun `the facts line`() {
        val card = DisplayCard(9, "Add-on screen", builtIn = false, widthPx = 1080, heightPx = 1920, role = DisplayRole.COMPANION, rates = emptyList(), rate = 60f)
        assertEquals("External, 1080x1920, portrait", displayFacts(card))
    }

    @Test fun `only message notifications count as a new message`() {
        fun item(time: Long, message: Boolean) = NotificationsStore.Item(
            key = "k$time", packageName = "p", appLabel = "A", title = "t", text = "x", postTime = time,
            contentIntent = null, clearable = true, message = message,
        )
        assertEquals(5L, newestMessage(listOf(item(5, true), item(9, false))))
        assertEquals(0L, newestMessage(listOf(item(9, false))))
    }
}
