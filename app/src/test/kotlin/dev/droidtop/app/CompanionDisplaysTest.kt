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
