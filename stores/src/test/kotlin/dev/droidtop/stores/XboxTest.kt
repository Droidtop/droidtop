package dev.droidtop.stores

import dev.droidtop.stores.xbox.XboxStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Xbox: the tokens and the played PC games (Droidtop/tracker#335). Answers written here in the shape Playnite's XboxLibrary reads. */
class XboxTest {
    @Test
    fun `the PC games are those played, typed as games, once each, with clean names`() {
        val games = XboxStore.gamesOf(
            """{"xuid":"2535","titles":[
              {"titleId":"1","pfn":"Microsoft.Halo_8wekyb3d8bbwe","name":"Halo Infinite (PC)","type":"Game","devices":["PC","XboxSeries"],
               "displayImage":"https://store-images.s-microsoft.com/halo.png"},
              {"titleId":"2","pfn":"Microsoft.Forza_8wekyb3d8bbwe","name":"Forza Horizon 5™ for Windows 10","type":"Game","devices":["PC"]},
              {"titleId":"3","pfn":"Microsoft.Console_x","name":"Console Only","type":"Game","devices":["XboxOne"]},
              {"titleId":"4","pfn":"Microsoft.App_x","name":"An App","type":"App","devices":["PC"]},
              {"titleId":"5","pfn":null,"name":"No pfn","type":"Game","devices":["PC"]},
              {"titleId":"6","pfn":"Microsoft.Halo_8wekyb3d8bbwe","name":"Halo again","type":"Game","devices":["PC"]}
            ]}""",
        )
        assertEquals(listOf("Halo Infinite", "Forza Horizon 5"), games.map { it.title })
        assertEquals("Microsoft.Halo_8wekyb3d8bbwe", games[0].id)
        assertEquals("https://store-images.s-microsoft.com/halo.png", games[0].artUrl)
        assertNull(games[1].artUrl)
        assertEquals(games, XboxStore.decode(XboxStore.encode(games)))
        assertEquals(emptyList<Any>(), XboxStore.gamesOf("{}"))
    }

    @Test
    fun `the XSTS answer gives the token, user hash and Xbox user id, and without them it is a sign-in gone`() {
        val xsts = XboxStore.xstsOf("""{"Token":"tok","DisplayClaims":{"xui":[{"uhs":"123","xid":"2535","gtg":"Mech"}]}}""")
        assertEquals("tok", xsts.token)
        assertEquals("123", xsts.userHash)
        assertEquals("2535", xsts.xuid)
        for (answer in listOf("""{"XErr":2148916233}""", """{"Token":"t","DisplayClaims":{"xui":[{"uhs":"1"}]}}""", "x")) {
            try {
                XboxStore.xstsOf(answer)
                fail("$answer is no XSTS token")
            } catch (e: XboxStore.SignedOut) {
                assertEquals("Sign in to Xbox again", e.message)
            }
        }
    }

    @Test
    fun `the refresh token is kept from a token answer and the code comes back on the desktop page`() {
        assertEquals("rt", XboxStore.refreshTokenOf("""{"access_token":"at","refresh_token":"rt","expires_in":3600}"""))
        try {
            XboxStore.refreshTokenOf("""{"error":"invalid_grant"}""")
            fail("no refresh token")
        } catch (_: XboxStore.SignedOut) {
        }
        assertTrue(XboxStore.isReturnPage("https://login.live.com/oauth20_desktop.srf?code=M.C123&lc=1033"))
        assertFalse(XboxStore.isReturnPage("https://login.live.com/oauth20_desktop.srf?error=access_denied"))
        assertFalse(XboxStore.isReturnPage("https://login.live.com/oauth20_authorize.srf?client_id=${XboxStore.CLIENT_ID}"))
    }
}
