package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject

/**
 * The device's networks as `net.get_links` reports them to a plugin with its own sockets (docs/plugin-api.md 3 D12,
 * 5.3 "Open processes"; Droidtop/tracker#553). An open process with the `net.sockets` hole cannot list interfaces
 * itself (the filter refuses netlink) nor use Android's resolver (netd's socket is a refused Unix socket), so droidtop
 * reads them from Android's own view of each network ([android.net.LinkProperties]) and hands them over: a sync
 * daemon then binds and announces on the right addresses and resolves names against the right DNS servers itself.
 */
object NetLinks {
    /** One address on an interface, with its prefix length ("192.168.1.20" / 24). */
    data class Address(val address: String, val prefixLength: Int)

    /**
     * One network Android has up: its interface's name, what kind it is (wifi, ethernet, cellular, vpn, other),
     * whether it is the active (default) one and metered, its addresses, and the DNS servers and search domains
     * Android uses on it.
     */
    data class Link(
        val name: String,
        val type: String,
        val active: Boolean,
        val metered: Boolean,
        val addresses: List<Address>,
        val dns: List<String>,
        val searchDomains: List<String> = emptyList(),
    )

    /**
     * `{links: [{name, type, active, metered, addresses: [{address, prefixLength}], dns: [...], searchDomains: [...]}],
     * dns: [...]}`, the active network first; the top-level `dns` is the active network's servers (else every link's,
     * in order, without repeats), what a resolver should ask. Links without a name are dropped, and a name appears once.
     */
    fun toJson(links: List<Link>): JSONObject {
        val ordered = links.filter { it.name.isNotBlank() }.distinctBy { it.name }.sortedByDescending { it.active }
        val dns = (ordered.firstOrNull { it.active }?.dns?.takeIf { it.isNotEmpty() } ?: ordered.flatMap { it.dns }).distinct()
        return JSONObject()
            .put(
                "links",
                JSONArray(
                    ordered.map { link ->
                        JSONObject()
                            .put("name", link.name)
                            .put("type", link.type)
                            .put("active", link.active)
                            .put("metered", link.metered)
                            .put("addresses", JSONArray(link.addresses.map { JSONObject().put("address", it.address).put("prefixLength", it.prefixLength) }))
                            .put("dns", JSONArray(link.dns))
                            .put("searchDomains", JSONArray(link.searchDomains))
                    },
                ),
            )
            .put("dns", JSONArray(dns))
    }
}
