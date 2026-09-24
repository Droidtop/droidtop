package dev.droidtop.app.vpn

/**
 * The tun interface droidtop's VPN presents to Android, and the
 * hev-socks5-tunnel configuration that serves it (vendor/hev-socks5-tunnel
 * conf/main.yml documents every key). Pure, so it is tested as text.
 *
 * DNS goes through hev's mapped DNS: a query to [DNS] is answered on the
 * device with an address from a private range, and a connection to that
 * address reaches the SOCKS5 endpoint by name. Name resolution then needs
 * nothing of the container's proxy beyond TCP CONNECT, which every SOCKS5
 * server has; UDP ASSOCIATE is asked for too and works where the server
 * offers it.
 */
internal object TunnelConfig {
    const val IPV4 = "198.18.0.1"
    const val IPV6 = "fc00::1"
    const val DNS = "198.18.0.2"
    const val MTU = 8500

    fun yaml(socksPort: Int): String = """
        |tunnel:
        |  mtu: $MTU
        |  ipv4: $IPV4
        |  ipv6: '$IPV6'
        |socks5:
        |  port: $socksPort
        |  address: 127.0.0.1
        |  udp: 'udp'
        |mapdns:
        |  address: $DNS
        |  port: 53
        |  network: 100.64.0.0
        |  netmask: 255.192.0.0
        |  cache-size: 10000
        |misc:
        |  log-level: warn
        |""".trimMargin()
}
