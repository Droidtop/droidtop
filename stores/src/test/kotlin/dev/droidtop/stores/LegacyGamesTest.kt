package dev.droidtop.stores

import dev.droidtop.stores.legacy.LegacyApi
import dev.droidtop.stores.legacy.LegacyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Legacy Games: the account, its purchases and giveaways, installer links (Droidtop/tracker#335). Answers written here in the shape the MIT clients read. */
class LegacyGamesTest {
    @Test
    fun `the sign-in is the e-mail and password as a basic token, and the login answer names the account`() {
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("a@b.c:pw".toByteArray()), LegacyApi.basic("a@b.c", "pw"))
        assertEquals("1234", LegacyApi.userIdOf("""{"data":{"userId":1234}}"""))
        for (bad in listOf("""{"data":{}}""", """{"error":"bad"}""", "nope")) {
            try {
                LegacyApi.userIdOf(bad)
                fail("$bad names no account")
            } catch (e: LegacyApi.SignedOut) {
                assertEquals("Sign in to Legacy Games again", e.message)
            }
        }
    }

    @Test
    fun `purchases come from the catalogue by the products bought, giveaways from the packs`() {
        val bought = LegacyApi.productIdsOf("""{"data":[{"product_id":10},{"product_id":"11"}]}""")
        assertEquals(setOf("10", "11"), bought)
        val catalog = LegacyApi.catalogOf(
            """[{"product_id":10,"games":[{"game_id":100,"game_name":"Mystery Case","installer_uuid":"u-100","game_installed_size":"218.1 MB","game_coverart":"https://cdn.legacygames.com/c.jpg"}]},
               {"product_id":12,"games":[{"game_id":120,"game_name":"Not Bought"}]}]""",
        )
        assertEquals(listOf("100", "120"), catalog.map { it.gameId })
        assertEquals("10", catalog[0].productId)
        assertEquals("https://cdn.legacygames.com/c.jpg", catalog[0].coverUrl)
        val giveaways = LegacyApi.giveawaysOf("""{"data":[{"games":[{"game_id":200,"game_name":"Free One","installer_uuid":"g-200"},{"game_id":201,"game_name":"No Installer"}]}]}""")
        assertEquals(listOf("200"), giveaways.map { it.gameId })
        assertTrue(giveaways[0].giveaway)
        assertEquals(218L * (1 shl 20) + (0.1 * (1 shl 20)).toLong(), LegacyApi.bytesOf("218.1 MB"))
        assertEquals(0L, LegacyApi.bytesOf(""))
    }

    @Test
    fun `an installer link is https from either answer`() {
        assertEquals("https://cdn.legacygames.com/a/setup.exe", LegacyApi.fileOf("""{"data":{"id":1,"name":"x","file":"https://cdn.legacygames.com/a/setup.exe"}}"""))
        assertEquals("https://cdn.legacygames.com/b/setup.exe", LegacyApi.fileOf("""{"status":"ok","file":"https://cdn.legacygames.com/b/setup.exe"}"""))
        assertNull(LegacyApi.fileOf("""{"file":"http://insecure/x.exe"}"""))
        assertNull(LegacyApi.fileOf("x"))
    }

    @Test
    fun `the kept library round trips`() {
        val game = LegacyApi.giveawaysOf("""{"data":[{"games":[{"game_id":200,"game_name":"Free One","installer_uuid":"g-200"}]}]}""").single()
        val rows = listOf(LegacyStore.Row(game, installPath = "/games/Free One", installed = true))
        assertEquals(rows, LegacyStore.decode(LegacyStore.encode(rows)))
        assertEquals(emptyList<Any>(), LegacyStore.decode("x"))
    }
}
