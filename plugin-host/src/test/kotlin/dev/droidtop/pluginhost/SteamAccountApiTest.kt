package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The Steam account for plugins (docs/plugin-api.md 3 G8, Droidtop/tracker#379): Steam's limits kept by droidtop, and the ops' arguments. */
class SteamAccountApiTest {
    @Test
    fun `no more than thirty free licences go in any rolling hour`() {
        val limits = SteamLimits()
        val t0 = 1_000_000L
        assertEquals(30, limits.claimsAllowed(t0, 50))
        limits.claimed(t0, 25)
        assertEquals(5, limits.claimsAllowed(t0 + 1_000, 50))
        limits.claimed(t0 + 1_000, 5)
        assertEquals(0, limits.claimsAllowed(t0 + 2_000, 1))
        // An hour after the first ones, they no longer count.
        assertEquals(25, limits.claimsAllowed(t0 + SteamLimits.HOUR_MS, 50))
    }

    @Test
    fun `after Steam says too many, nothing goes for an hour, keys included`() {
        val limits = SteamLimits()
        limits.rateLimited(0L)
        assertEquals(0, limits.claimsAllowed(10L, 5))
        assertTrue(limits.coolingFor(10L) > 0)
        assertEquals(5, limits.claimsAllowed(SteamLimits.COOL_DOWN_MS, 5))
    }

    @Test
    fun `one key at most every ten seconds`() {
        val limits = SteamLimits()
        assertEquals(0L, limits.keyWaitMs(0L))
        limits.keyRedeemed(0L)
        assertEquals(6_000L, limits.keyWaitMs(4_000L))
        assertEquals(0L, limits.keyWaitMs(SteamLimits.KEY_SPACING_MS))
    }

    @Test
    fun `ids are positive whole numbers, each once, a hundred at most`() {
        assertEquals(listOf(10, 20), HostSteamApis.ids(JSONObject().put("apps", JSONArray(listOf(10, 20, 10))), "apps"))
        assertEquals(emptyList<Int>(), HostSteamApis.ids(JSONObject(), "apps"))
        for (bad in listOf(JSONArray(listOf(0)), JSONArray(listOf("10")), JSONArray((1..101).toList()), JSONArray(listOf(-3)))) {
            try {
                HostSteamApis.ids(JSONObject().put("apps", bad), "apps")
                fail("$bad is not a list of ids")
            } catch (e: BrokerException) {
                assertEquals(PluginErrorCode.INVALID_ARGS, e.code)
            }
        }
    }

    @Test
    fun `a product key is letters, digits and dashes`() {
        assertEquals("AAAAA-BBBBB-CCCCC", HostSteamApis.key(JSONObject().put("key", " aaaaa-bbbbb-ccccc ")))
        for (bad in listOf("", "abc", "AAAA BBBB", "x".repeat(65), "AAA;rm")) {
            try {
                HostSteamApis.key(JSONObject().put("key", bad))
                fail("$bad is not a key")
            } catch (e: BrokerException) {
                assertEquals(PluginErrorCode.INVALID_ARGS, e.code)
            }
        }
    }

    @Test
    fun `the permission is dangerous and every op needs it`() {
        val permission = PluginPermissions.all.first { it.id == HostSteamApis.PERMISSION }
        assertEquals(PermissionTier.DANGEROUS, permission.tier)
        assertTrue(HostSteamApis.ops.all { it.permission == HostSteamApis.PERMISSION })
        assertEquals(setOf("get_status", "list_changes", "get_product_info", "claim_free", "redeem_key", "play", "stop"), HostSteamApis.ops.map { it.op }.toSet())
    }
}
