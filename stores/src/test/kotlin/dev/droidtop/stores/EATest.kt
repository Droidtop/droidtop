package dev.droidtop.stores

import dev.droidtop.stores.ea.EAStore
import dev.droidtop.stores.ea.EaAuth
import dev.droidtop.stores.ea.EaApi
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The EA app: the token, the owned base games and their products (mytop-project/tracker#335). Answers written here in the shape Lutris's ea_app service reads. */
class EATest {
    @Test
    fun `EA desktop credentials keep a refresh token and reject the old cookie session`() {
        val account = EaAuth.fromJson(JSONObject("""{"access_token":"access","refresh_token":"refresh","expires_at":123,"user_id":"42"}"""))!!
        assertEquals("access", account.accessToken)
        assertEquals("refresh", account.refreshToken)
        assertEquals(123L, account.expiresAt)
        assertNull(EaAuth.fromJson(JSONObject("""{"cookies":"session=old"}""")))
        val url = EaAuth.authUrl("device", "code", challenge = "pkce-challenge").toHttpUrl()
        assertEquals("JUNO_PC_CLIENT", url.queryParameter("client_id"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("pkce-challenge", url.queryParameter("code_challenge"))
    }

    @Test
    fun `entitlements keep base games only, and page on`() {
        val (offers, next) = EAStore.entitlementsOf(
            """{"data":{"me":{"ownedGameProducts":{"next":"cursor2","items":[
              {"originOfferId":"Origin.OFR.50.0001","product":{"baseItem":{"gameType":"BASE_GAME"}}},
              {"originOfferId":"Origin.OFR.50.0002","product":{"baseItem":{"gameType":"EXPANSION"}}},
              {"originOfferId":"Origin.OFR.50.0003","product":null}
            ]}}}}""",
        )
        assertEquals(listOf("Origin.OFR.50.0001"), offers)
        assertEquals("cursor2", next)
        assertNull(EAStore.entitlementsOf("""{"data":{"me":{"ownedGameProducts":{"next":null,"items":[]}}}}""").second)
        try {
            EAStore.entitlementsOf("""{"errors":[{"message":"bad query"}]}""")
            fail("a GraphQL error is not an empty library")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("bad query"))
        }
    }

    @Test
    fun `products carry their content id, title and box art`() {
        val games = EAStore.productsOf(
            """{"data":{"legacyOffers":[{"offerId":"Origin.OFR.50.0001","contentId":"1234567"}],
              "gameProducts":{"items":[
                {"id":"p1","originOfferId":"Origin.OFR.50.0001","gameSlug":"mass-effect","baseItem":{"title":"Mass Effect",
                  "packArt":{"largestImage":{"path":"https://media.contentapi.ea.com/pack.jpg"}},"keyArt":{"largestImage":{"path":"https://media.contentapi.ea.com/key.jpg"}}}},
                {"id":"p2","originOfferId":"Origin.OFR.50.0009","baseItem":{"title":"No content id","keyArt":{"largestImage":{"path":"https://x/key.jpg"}}}},
                {"id":"p3","originOfferId":"Origin.OFR.50.0010","baseItem":{"title":"  "}}
              ]}}}""",
        )
        assertEquals(listOf("1234567", "Origin.OFR.50.0009"), games.map { it.id })
        assertEquals(listOf("Origin.OFR.50.0001", "Origin.OFR.50.0009"), games.map { it.offerId })
        assertEquals("https://media.contentapi.ea.com/pack.jpg", games[0].artUrl)
        assertEquals("https://x/key.jpg", games[1].artUrl)
        assertEquals(games, EAStore.decode(EAStore.encode(games)))
    }

    @Test
    fun `the one time EA callback must belong to this sign in`() {
        val callback = "qrc:///html/login_successful.html?code=a%2Bb&state=one-time"
        assertTrue(EAStore.isReturnPage(callback))
        assertEquals("a+b", JSONObject(EAStore.callbackSecret(callback, "one-time")!!).getString("code"))
        assertNull(EAStore.callbackSecret(callback, "another-attempt"))
        assertNull(EAStore.callbackSecret("qrc:///html/login_successful.html?code=code", "one-time"))
        assertNull(EAStore.callbackSecret("https://attacker.example/login_successful.html?code=code&state=one-time", "one-time"))
        assertFalse(EAStore.isReturnPage("https://www.ea.com/games/library"))
        assertFalse(EAStore.isReturnPage("qrc:///html/login_successful.html/other?code=code"))
    }

    @Test
    fun `EA catalogue offers keep the content and download identities distinct`() {
        val offers = EaApi.parseOffers(JSONObject("""{"legacyOffers":[{"offerId":"offer-7","contentId":"content-4","displayName":"Game","downloads":[{"downloadType":"LIVE","gameVersion":"2.0"}]}]}"""))
        assertEquals("offer-7", offers.single().offerId)
        assertEquals("content-4", offers.single().contentId)
        assertEquals("2.0", offers.single().gameVersion)
        assertTrue(EaApi.query("GetUserPlayer").contains("query GetUserPlayer"))
        assertTrue(EaApi.query("downloadUrl").contains("offerId"))
    }
}
