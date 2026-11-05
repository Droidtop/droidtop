package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** D22, peer addresses (docs/plugin-api.md 3, Droidtop/tracker#645): what a VPN plugin may give droidtop. */
class HostPeersApisTest {
    private fun args(vararg a: String) = JSONObject().put("addresses", JSONArray(a.toList()))

    @Test
    fun `IP addresses are taken, once each`() {
        assertEquals(listOf("100.101.102.103", "fd7a:115c:a1e0::1"), HostPeersApis.parse(args("100.101.102.103", " fd7a:115c:a1e0::1", "100.101.102.103")))
        assertEquals(emptyList<String>(), HostPeersApis.parse(args()))
    }

    @Test
    fun `names and anything else are refused`() {
        for (bad in listOf("pc.tailnet.ts.net", "256.1.1.1", "1.2.3", "", "http://1.2.3.4", "::g")) {
            assertFalse(bad, HostPeersApis.isIpLiteral(bad))
            try {
                HostPeersApis.parse(args(bad))
                fail("took $bad")
            } catch (e: BrokerException) {
                assertEquals(PluginErrorCode.INVALID_ARGS, e.code)
            }
        }
        assertTrue(HostPeersApis.isIpLiteral("::1"))
    }

    @Test
    fun `it is one dangerous permission, always audited`() {
        assertTrue(HostPeersApis.ops.all { it.permission == "peers.addresses" && it.alwaysAudit })
        assertEquals(PermissionTier.DANGEROUS, PluginPermissions.find("peers.addresses")!!.tier)
    }
}
