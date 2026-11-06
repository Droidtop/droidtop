package dev.droidtop.app

import dev.droidtop.runtime.systemstatus.NetworkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Droidtop/tracker#786 S9: the Network pin names the network and does something when tapped. */
class NetworkPinTest {
    @Test fun `the pin names the Wi-Fi network where it can`() {
        assertEquals("Home 5G", networkPinValue(NetworkKind.WIFI, "Home 5G", wifiOn = true))
        assertEquals("Wi-Fi", networkPinValue(NetworkKind.WIFI, null, wifiOn = true))
        assertEquals("Wi-Fi", networkPinValue(NetworkKind.WIFI, " ", wifiOn = true))
        assertEquals("Ethernet", networkPinValue(NetworkKind.ETHERNET, null, wifiOn = false))
        assertEquals("Mobile data", networkPinValue(NetworkKind.CELLULAR, null, wifiOn = null))
    }

    @Test fun `offline says whether Wi-Fi is off`() {
        assertEquals("Wi-Fi off", networkPinValue(NetworkKind.NONE, null, wifiOn = false))
        assertEquals("Offline", networkPinValue(NetworkKind.NONE, null, wifiOn = true))
        assertEquals("Offline", networkPinValue(NetworkKind.NONE, null, wifiOn = null))
    }

    @Test fun `a tap switches Wi-Fi only with the helper app`() {
        assertTrue(networkPinSwitches(helper = true))
        assertFalse(networkPinSwitches(helper = false))
    }
}
