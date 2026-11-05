package dev.droidtop.pluginhost

import org.json.JSONObject

/**
 * D22 (docs/plugin-api.md 3, `peers`, Droidtop/tracker#645, #373): a VPN plugin tells droidtop where the person's other
 * devices can be reached on its network, and droidtop reaches them there too. The Tailscale plugin passes its tailnet's
 * online devices (owner, 2026-11-05: "We control tailscale like any other VPN ... Tailcale plugin, not full tailscale
 * client"). Today KDE Connect announces this device to each address (docs/SPEC.md 7q); droidtop's own peer links take
 * them next. Each call replaces what the same plugin said before; an empty list withdraws it. Only IP addresses, never
 * names, so a plugin cannot make droidtop resolve anything, and at most [MAX] of them.
 */
internal object HostPeersApis {
    const val PERMISSION = "peers.addresses"
    const val MAX = 256

    val ops: List<HostOp> = listOf(
        HostOp("peers", "set_addresses", permission = PERMISSION, alwaysAudit = true) { env, record, args ->
            env.setPeerAddresses(record.manifest.id, parse(args))
            JSONObject()
        },
    )

    /** The addresses in [args]: `{addresses: ["100.101.102.103", "fd7a:115c:a1e0::1"]}`. Pure. */
    fun parse(args: JSONObject): List<String> {
        val list = args.optJSONArray("addresses") ?: throw BrokerException(PluginErrorCode.INVALID_ARGS, "addresses is a list of IP addresses")
        if (list.length() > MAX) throw BrokerException(PluginErrorCode.INVALID_ARGS, "at most $MAX addresses")
        return (0 until list.length()).map { i ->
            val a = list.optString(i).trim()
            if (!isIpLiteral(a)) throw BrokerException(PluginErrorCode.INVALID_ARGS, "not an IP address: $a")
            a
        }.distinct()
    }

    /** An IPv4 dotted quad or an IPv6 literal, never a name (nothing is resolved). */
    fun isIpLiteral(a: String): Boolean {
        val v4 = a.split('.')
        if (v4.size == 4 && v4.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() <= 255 }) return true
        return a.contains(':') && a.length <= 45 && a.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' } &&
            a.count { it == ':' } in 2..7
    }
}
