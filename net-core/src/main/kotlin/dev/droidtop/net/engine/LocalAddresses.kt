package dev.droidtop.net.engine

import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI

/**
 * Which addresses are on the device or the network it is on, as the engine's broker judges them (aria2-engine's
 * net_scope.c): loopback, private, carrier NAT and tailnets (100.64.0.0/10), link-local, multicast, reserved and
 * unspecified. The engine reaches none of them unless droidtop gives it a pass for an HTTP download droidtop itself
 * sent there (docs/SPEC.md 12a, "The local network"). Pure.
 */
object LocalAddresses {
    fun isLocal(address: InetAddress): Boolean {
        val b = address.address.map { it.toInt() and 0xff }
        if (address is Inet4Address) return v4(b)
        // IPv4-mapped (Java hands those over as Inet4Address already) and NAT64 carry an IPv4 address in the last four.
        if (b.subList(0, 4) == listOf(0, 0x64, 0xff, 0x9b) && b.subList(4, 12).all { it == 0 }) return v4(b.subList(12, 16))
        if (b.subList(0, 15).all { it == 0 } && b[15] <= 1) return true
        return (b[0] and 0xfe) == 0xfc || (b[0] == 0xfe && (b[1] and 0xc0) in setOf(0x80, 0xc0)) || b[0] == 0xff
    }

    private fun v4(a: List<Int>): Boolean = a[0] == 0 || a[0] == 10 || a[0] == 127 ||
        (a[0] == 100 && (a[1] and 0xc0) == 64) || (a[0] == 169 && a[1] == 254) || (a[0] == 172 && (a[1] and 0xf0) == 16) ||
        (a[0] == 192 && a[1] == 168) || (a[0] == 192 && a[1] == 0 && a[2] == 0) || (a[0] == 198 && (a[1] and 0xfe) == 18) ||
        a[0] >= 224

    /**
     * The local addresses and port [url] reaches, to give the engine a pass for: empty for a public server, an address
     * that does not resolve, or a scheme the engine does not fetch from (HTTP(S), FTP, and a tracker's `udp://` with
     * its port).
     */
    fun passesFor(url: String, lookup: (String) -> List<InetAddress>): List<Pair<InetAddress, Int>> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return emptyList()
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("http", "https", "ftp", "udp")) return emptyList()
        val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return emptyList()
        val port = when {
            uri.port > 0 -> uri.port
            scheme == "https" -> 443
            scheme == "http" -> 80
            scheme == "ftp" -> 21
            else -> return emptyList()
        }
        val addresses = runCatching { lookup(host) }.getOrDefault(emptyList())
        return addresses.filter(::isLocal).distinct().map { it to port }
    }

    /**
     * The addresses a magnet link names itself: its trackers (`tr`), web seeds (`ws`) and sources (`xs`, `as`). droidtop
     * vouches for these because they came with the link it was given, not from the swarm. Pure.
     */
    fun magnetAddresses(magnet: String): List<String> =
        magnet.substringAfter('?', "").split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=').lowercase()
            if (key !in setOf("tr", "ws", "xs", "as") || '=' !in pair) return@mapNotNull null
            runCatching { java.net.URLDecoder.decode(pair.substringAfter('='), "UTF-8") }.getOrNull()?.takeIf { it.isNotBlank() }
        }
}
