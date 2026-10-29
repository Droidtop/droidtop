package dev.droidtop.shell.gamepad

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationBodyTest {
    @Test
    fun `a sentence telling a finger what to do is left out, the rest is the app's`() {
        assertEquals("", notificationBody("Tap to turn off wireless debugging"))
        assertEquals("Connected to Home Wi-Fi.", notificationBody("Connected to Home Wi-Fi. Tap to see networks."))
        assertEquals("Battery at 15%.", notificationBody("Battery at 15%. touch for options"))
        assertEquals("Press release out now", notificationBody("Press release out now"))
        assertEquals("Downloading 3 files", notificationBody("Downloading 3 files"))
    }
}
