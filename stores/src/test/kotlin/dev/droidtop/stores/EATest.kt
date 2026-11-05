package dev.droidtop.stores

import dev.droidtop.stores.ea.EAStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The EA app: the token, the owned base games and their products (Droidtop/tracker#335). Answers written here in the shape Lutris's ea_app service reads. */
class EATest {
    @Test
    fun `the token comes from the auth answer, and an answer without one is a sign-in gone`() {
        assertEquals("abc", EAStore.tokenOf("""{"access_token":"abc","token_type":"Bearer","expires_in":"3599"}"""))
        for (answer in listOf("""{"error":"login_required"}""", "<html/>")) {
            try {
                EAStore.tokenOf(answer)
                fail("$answer holds no token")
            } catch (e: EAStore.SignedOut) {
                assertEquals("Sign in to the EA app again", e.message)
            }
        }
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
        assertEquals("https://media.contentapi.ea.com/pack.jpg", games[0].artUrl)
        assertEquals("https://x/key.jpg", games[1].artUrl)
        assertEquals(games, EAStore.decode(EAStore.encode(games)))
    }

    @Test
    fun `the sign-in is done when EA returns to its site past the sign-in page`() {
        assertTrue(EAStore.isReturnPage("https://www.ea.com/"))
        assertTrue(EAStore.isReturnPage("https://www.ea.com/games/library"))
        assertFalse(EAStore.isReturnPage("https://www.ea.com/login"))
        assertFalse(EAStore.isReturnPage("https://signin.ea.com/p/juno/login"))
    }
}
