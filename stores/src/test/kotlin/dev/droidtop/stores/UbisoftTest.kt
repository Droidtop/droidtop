package dev.droidtop.stores

import dev.droidtop.stores.ubisoft.UbisoftStore
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Ubisoft Connect: the stored session, its renewal and the owned games (Droidtop/tracker#335). Answers written here in the shape Lutris's Ubisoft service reads. */
class UbisoftTest {
    private val stored = """{"ticket":"T1","sessionId":"S1","userId":"U1","nameOnPlatform":"Mech","rememberMeTicket":"R1","other":1}"""

    @Test
    fun `the page's stored session gives the ticket, session, account and remember-me ticket`() {
        val session = UbisoftStore.sessionOf(stored)!!
        assertEquals("T1", session.ticket)
        assertEquals("S1", session.sessionId)
        assertEquals("U1", session.userId)
        assertEquals("Mech", session.name)
        assertEquals("R1", session.rememberMe)
        assertNull(UbisoftStore.sessionOf("""{"sessionId":"S1"}"""))
        assertNull(UbisoftStore.sessionOf("null"))
        assertEquals(session, UbisoftStore.decodeSession(UbisoftStore.encodeSession(session)))
    }

    @Test
    fun `a renewal takes the new ticket and is due again before the server's lifetime runs out`() {
        val session = UbisoftStore.sessionOf(stored)!!
        val now = Instant.parse("2026-11-05T10:00:00Z").toEpochMilli()
        val answer = JSONObject("""{"ticket":"T2","sessionId":"S2","serverTime":"2026-11-05T09:00:00.000Z","expiration":"2026-11-05T12:00:00.000Z"}""")
        val renewed = UbisoftStore.renewed(session, answer, now)
        assertEquals("T2", renewed.ticket)
        assertEquals("S2", renewed.sessionId)
        assertEquals("R1", "${renewed.rememberMe}")
        // Three hours by the server's own clock, less five minutes, whatever this device's clock says.
        assertEquals(now + 3 * 3600_000L - 5 * 60_000L, renewed.renewAtMs)
        assertEquals(now + 3600_000L, UbisoftStore.renewed(session, null, now).renewAtMs)
    }

    @Test
    fun `the owned games are each once, by space id, with their covers`() {
        val games = UbisoftStore.gamesOf(
            """{"data":{"viewer":{"id":"U1","ownedGames":{"totalCount":3,"nodes":[
              {"id":"g1","spaceId":"5c7b0d1e-space","name":"Far Cry 5","coverUrl":"https://ubiservices.cdn.ubi.com/cover.jpg"},
              {"id":"g2","spaceId":null,"name":"Old Game","coverUrl":"http://insecure/x.jpg"},
              {"id":"g3","spaceId":"5c7b0d1e-space","name":"Far Cry 5 again"},
              {"id":"g4","name":" "}
            ]}}}}""",
        )
        assertEquals(listOf("5c7b0d1e-space", "g2"), games.map { it.id })
        assertEquals("https://ubiservices.cdn.ubi.com/cover.jpg", games[0].artUrl)
        assertNull(games[1].artUrl)
        assertEquals(games, UbisoftStore.decode(UbisoftStore.encode(games)))
        try {
            UbisoftStore.gamesOf("""{"errors":[{"message":"unauthorized"}]}""")
            fail("a GraphQL error is not an empty library")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("unauthorized"))
        }
    }

    @Test
    fun `the sign-in reads its session on Ubisoft's ready and change-domain pages`() {
        assertTrue(UbisoftStore.isReturnPage("https://connect.ubisoft.com/ready"))
        assertTrue(UbisoftStore.isReturnPage("https://connect.ubisoft.com/change_domain/"))
        assertFalse(UbisoftStore.isReturnPage(UbisoftStore.LOGIN))
        assertTrue(UbisoftStore.SESSION_SCRIPT.contains("PRODloginData"))
        assertTrue(UbisoftStore.LOGIN.contains("appId=${UbisoftStore.APP_ID}"))
    }
}
