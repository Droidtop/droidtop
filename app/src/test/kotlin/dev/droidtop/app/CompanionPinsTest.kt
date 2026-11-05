package dev.droidtop.app

import dev.droidtop.runtime.systemstatus.NetworkKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Network pin's line (Droidtop/tracker#786 addendum 2, item 3: the pin names the network, never "signal 4/4"). */
class CompanionPinsTest {
    @Test
    fun `a named Wi-Fi network is shown by its name`() {
        assertEquals("Home", networkPinLine(NetworkKind.WIFI, validated = true, name = "Home"))
    }

    @Test
    fun `Wi-Fi without a name says Wi-Fi, and no internet when nothing gets through`() {
        assertEquals("Wi-Fi", networkPinLine(NetworkKind.WIFI, validated = true, name = null))
        assertEquals("Home, no internet", networkPinLine(NetworkKind.WIFI, validated = false, name = "Home"))
    }

    @Test
    fun `offline reads Offline, or Wi-Fi off when the radio is off`() {
        assertEquals("Offline", networkPinLine(NetworkKind.NONE, validated = false, name = null))
        assertEquals("Wi-Fi off", networkPinLine(NetworkKind.NONE, validated = false, name = "Wi-Fi off"))
    }

    @Test
    fun `wired and mobile are named by kind`() {
        assertEquals("Ethernet", networkPinLine(NetworkKind.ETHERNET, validated = true, name = null))
        assertEquals("Mobile data", networkPinLine(NetworkKind.CELLULAR, validated = true, name = null))
        assertEquals("Reading…", networkPinLine(null, validated = false, name = null))
    }
}
