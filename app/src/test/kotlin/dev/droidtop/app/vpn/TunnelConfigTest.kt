package dev.droidtop.app.vpn

import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelConfigTest {
    @Test
    fun `the stack talks to the relay on loopback and answers DNS itself`() {
        val yaml = TunnelConfig.yaml(41234)
        assertTrue(yaml.contains("socks5:\n  port: 41234\n  address: 127.0.0.1\n"))
        assertTrue(yaml.contains("mapdns:\n  address: ${TunnelConfig.DNS}\n  port: 53\n"))
        assertTrue(yaml.startsWith("tunnel:\n  mtu: ${TunnelConfig.MTU}\n"))
    }
}
