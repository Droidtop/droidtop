package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `net.get_links` (docs/plugin-api.md 3 D12): what a plugin with its own sockets is told about the device's networks. */
class NetLinksTest {
    private val wifi = NetLinks.Link(
        "wlan0", "wifi", active = true, metered = false,
        addresses = listOf(NetLinks.Address("192.168.1.20", 24), NetLinks.Address("fe80::1", 64)),
        dns = listOf("192.168.1.1", "1.1.1.1"), searchDomains = listOf("lan"),
    )
    private val cell = NetLinks.Link("rmnet0", "cellular", active = false, metered = true, addresses = listOf(NetLinks.Address("10.0.0.5", 32)), dns = listOf("8.8.8.8"))

    @Test
    fun `the active link comes first and gives the dns servers`() {
        val json = NetLinks.toJson(listOf(cell, wifi))
        val links = json.getJSONArray("links")
        assertEquals("wlan0", links.getJSONObject(0).getString("name"))
        assertEquals(24, links.getJSONObject(0).getJSONArray("addresses").getJSONObject(0).getInt("prefixLength"))
        assertEquals(listOf("192.168.1.1", "1.1.1.1"), (0 until json.getJSONArray("dns").length()).map { json.getJSONArray("dns").getString(it) })
        assertTrue(links.getJSONObject(1).getBoolean("metered"))
    }

    @Test
    fun `with no active link every link's servers are offered once, and unnamed or repeated links are dropped`() {
        val json = NetLinks.toJson(listOf(cell, wifi.copy(active = false, dns = listOf("8.8.8.8", "1.1.1.1")), wifi.copy(name = ""), cell))
        assertEquals(2, json.getJSONArray("links").length())
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), (0 until json.getJSONArray("dns").length()).map { json.getJSONArray("dns").getString(it) })
    }
}
