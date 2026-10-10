package dev.droidtop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** System > Connections (CompanionConnections.kt, Droidtop/tracker#414 slice C19). */
class CompanionConnectionsTest {
    @Test fun `Wi-Fi bands from the frequency`() {
        assertEquals("2.4 GHz", band(2437))
        assertEquals("5 GHz", band(5180))
        assertEquals("6 GHz", band(5955))
        assertNull(band(60_000))
    }

    @Test fun `the network lines say only what is known`() {
        val full = NetworkDetail("Wi-Fi", listOf("192.168.1.20"), vpn = true, signalLevel = 3, bandGhz = "5 GHz", linkMbps = 866, name = "Home", adbWifi = false)
        assertEquals(
            listOf("Wi-Fi: Home", "IP 192.168.1.20", "Signal 3 of 4, 5 GHz, 866 Mbps", "VPN on", "ADB over Wi-Fi: off"),
            networkLines(full),
        )
        // Without the helper app: no name and nothing about ADB.
        val plain = NetworkDetail("Ethernet", emptyList(), vpn = false, signalLevel = null, bandGhz = null, linkMbps = null, name = null, adbWifi = null)
        assertEquals(listOf("Ethernet"), networkLines(plain))
    }
}
