package dev.droidtop.stores

import dev.droidtop.stores.steam.SteamPluginAccount
import `in`.dragonbra.javasteam.types.KeyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Steam answers the plugin API's calls (docs/plugin-api.md 3 G8, Droidtop/tracker#379), read off the network. */
class SteamPluginAccountTest {
    private fun kv(name: String, value: String? = null, vararg children: KeyValue) = KeyValue(name, value).apply { this.children.addAll(children) }

    @Test
    fun `product info is one flat map of its facts, three levels down`() {
        val info = kv(
            "440", null,
            kv("common", null, kv("type", "Game"), kv("name", "Team Fortress 2"), kv("deep", null, kv("deeper", null, kv("deepest", "x")))),
            kv("extended", null, kv("isfreeapp", "1")),
            kv("billingtype", "12"),
        )
        val facts = SteamPluginAccount.flatten(info)
        assertEquals("Game", facts["common/type"])
        assertEquals("1", facts["extended/isfreeapp"])
        assertEquals("12", facts["billingtype"])
        assertFalse("four levels down is left out", facts.keys.any { it.endsWith("deepest") })
    }

    @Test
    fun `a free package is claimed unless Steam refuses or says too many`() {
        assertTrue(SteamPluginAccount.freeLicenseOf(1, 200, "[]").claimed)
        val limited = SteamPluginAccount.freeLicenseOf(1, 429, "")
        assertTrue(limited.rateLimited)
        assertFalse(limited.claimed)
        assertEquals("refused", SteamPluginAccount.freeLicenseOf(1, 200, """{"success": false}""").reason)
        assertEquals("http_403", SteamPluginAccount.freeLicenseOf(1, 403, "").reason)
    }

    @Test
    fun `a key's answer gives Steam's result and what it added`() {
        val ok = SteamPluginAccount.redeemOf("""{"success":1,"purchase_result_details":0,"purchase_receipt_info":{"line_items":[{"packageid":1,"line_item_description":"Portal 2"}]}}""")
        assertEquals("ok", ok.result)
        assertEquals(listOf("Portal 2"), ok.items)
        assertEquals("already_owned", SteamPluginAccount.redeemOf("""{"success":2,"purchase_result_details":9}""").result)
        assertEquals("invalid", SteamPluginAccount.redeemOf("""{"success":2,"purchase_result_details":14}""").result)
        val limited = SteamPluginAccount.redeemOf("""{"success":2,"purchase_result_details":53}""")
        assertTrue(limited.rateLimited)
        assertEquals("failed", SteamPluginAccount.redeemOf("not json").result)
    }
}
