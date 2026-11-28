package dev.droidtop.stores.ea

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class EaPeerIdentityTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val header = "sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n"
    private fun row(local: String = "0100007F:9C40", remote: String = "0100007F:0C90", state: String = "01", uid: String = "10042") =
        "0: $local $remote $state 00000000:00000000 00:00000000 00000000 $uid 0 1234\n"
    private fun owner(first: String, second: String = header) = EaPeerIdentity.owner(listOf(first, second), loopback, 40000, loopback, 3216)

    @Test fun `only the full established client tuple proves an owner`() {
        assertEquals(10042, owner(header + row()))
        assertNull(owner(header + row(local = "010200C0:9C40")))
        assertNull(owner(header + row(remote = "010200C0:0C90")))
        assertNull(owner(header + row(local = "0100007F:9C41")))
        assertNull(owner(header + row(state = "06")))
        assertNull(owner(header + row(local = "0100007F:0C90", remote = "0100007F:9C40")))
    }

    @Test fun `IPv4 mapped proc words identify the same actual endpoint`() {
        val mapped = "0000000000000000FFFF00000100007F"
        assertEquals(10042, owner(header, header + row(local = "$mapped:9C40", remote = "$mapped:0C90")))
    }

    @Test fun `ambiguous or unreadable tables never prove an owner`() {
        assertNull(owner(header + row(), header + row(uid = "10043")))
        assertNull(owner(header + row() + row()))
        assertNull(owner(header + row(), ""))
        assertNull(owner(header + row().trimEnd()))
        assertNull(owner(header + row(uid = "not-a-uid")))
        assertNull(owner(header + "malformed\n" + row()))
        assertNull(EaPeerIdentity.owner(emptyList(), loopback, 40000, loopback, 3216))
    }

    @Test fun `native IPv6 loopback word order is decoded explicitly`() {
        val v6 = InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })
        val encoded = "00000000000000000000000001000000"
        assertEquals(10042, EaPeerIdentity.owner(listOf(header, header + row(local = "$encoded:9C40", remote = "$encoded:0C90")), v6, 40000, v6, 3216))
    }
}
