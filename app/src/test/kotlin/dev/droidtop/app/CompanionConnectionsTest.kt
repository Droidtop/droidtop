package dev.droidtop.app

import android.net.NetworkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The companion's Network tab (CompanionNetwork.kt, mytop-project/tracker#583; the network half of slice C19). */
class CompanionConnectionsTest {
    @Test fun `Wi-Fi bands from the frequency`() {
        assertEquals("2.4 GHz", band(2437))
        assertEquals("5 GHz", band(5180))
        assertEquals("6 GHz", band(5955))
        assertNull(band(60_000))
    }

    private fun net(kind: NetKind, name: String? = null, allTraffic: Boolean = false) = NetFact(
        kind = kind, name = name, iface = "tun0", addresses = listOf("10.8.0.2"), dns = listOf("10.8.0.1"),
        allTraffic = allTraffic, metered = false,
    )

    @Test fun `a network's row is its kind, its name as the value, its details in Info`() {
        val rows = networkRows(NetworkState(listOf(net(NetKind.VPN, "WireGuard"), net(NetKind.WIFI)), interfaces = listOf("tun0", "wlan0"), helper = false))
        assertEquals(listOf("VPN", "Wi-Fi", "Check public address", "Interfaces"), rows.map { it.title })
        assertEquals(listOf("WireGuard", "Connected"), rows.take(2).map { it.value })
        assertEquals("2", rows.last().value)
        assertEquals("Offline", networkRows(NetworkState(emptyList(), emptyList(), helper = false)).first().title)
    }

    @Test fun `Details says only what is known`() {
        assertEquals(
            listOf("Interface tun0", "Address 10.8.0.2", "DNS 10.8.0.1", "All traffic goes through it", "Not metered"),
            detailFacts(net(NetKind.VPN, allTraffic = true)),
        )
        val wifi = net(NetKind.WIFI, "Home").copy(signalLevel = 3, bandGhz = "5 GHz", linkMbps = 866)
        assertTrue(detailFacts(wifi).contains("Signal 3 of 4, 5 GHz, 866 Mbps"))
    }

    @Test fun `a VPN is named by its transport before the one it rides on`() {
        val vpnOverWifi = setOf(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI)
        assertEquals(NetKind.VPN, kindOf { it in vpnOverWifi })
        assertEquals(NetKind.CELLULAR, kindOf { it == NetworkCapabilities.TRANSPORT_CELLULAR })
    }
}
