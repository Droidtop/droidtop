package dev.droidtop.library.credentials.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-time token, its expiry, the field checks and the rate limit of a phone handoff (docs/SPEC.md 7h). */
class HandoffSessionTest {
    private var now = 1_000L
    private val fields = listOf(
        HandoffField("client_id", "Client ID", secret = false),
        HandoffField("client_secret", "Client Secret", secret = true, maxLength = 64),
    )

    private fun session(ttl: Long = 5 * 60_000L, maxRequests: Int = 20, maxBad: Int = 5) =
        HandoffSession(fields, clock = { now }, ttlMs = ttl, token = "tok", maxRequestsPerWindow = maxRequests, maxBadTokens = maxBad)

    private val good = mapOf("client_id" to "abc", "client_secret" to "def")

    @Test
    fun `tokens are long, url safe and different every time`() {
        val a = HandoffSession.newToken()
        val b = HandoffSession.newToken()
        assertNotEquals(a, b)
        assertTrue(a.length >= 22)
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `the right token with the declared fields is accepted and held for review`() {
        val s = session()
        assertEquals(HandoffSession.Submit.Accepted, s.submit("tok", good))
        assertEquals(good, s.received())
        assertFalse(s.isOpen())
    }

    @Test
    fun `values are trimmed`() {
        val s = session()
        s.submit("tok", mapOf("client_id" to "  abc ", "client_secret" to "def\n"))
        assertEquals(good, s.received())
    }

    @Test
    fun `a wrong token is refused and nothing is kept`() {
        val s = session()
        assertEquals(HandoffSession.Submit.Denied(HandoffSession.Admission.BAD_TOKEN), s.submit("nope", good))
        assertNull(s.received())
    }

    @Test
    fun `the token works once`() {
        val s = session()
        s.submit("tok", good)
        assertEquals(HandoffSession.Submit.Denied(HandoffSession.Admission.USED), s.submit("tok", good))
        assertEquals(HandoffSession.Admission.USED, s.admit("tok"))
    }

    @Test
    fun `it expires after the lifetime`() {
        val s = session(ttl = 60_000)
        now += 59_999
        assertEquals(HandoffSession.Admission.OK, s.admit("tok"))
        now += 2
        assertEquals(HandoffSession.Admission.EXPIRED, s.admit("tok"))
        assertEquals(HandoffSession.Submit.Denied(HandoffSession.Admission.EXPIRED), s.submit("tok", good))
        assertFalse(s.isOpen())
    }

    @Test
    fun `remaining time counts down to zero`() {
        val s = session(ttl = 60_000)
        assertEquals(60_000, s.remainingMs())
        now += 90_000
        assertEquals(0, s.remainingMs())
    }

    @Test
    fun `a field that was not declared is refused`() {
        val s = session()
        val result = s.submit("tok", good + ("extra" to "x"))
        assertEquals(HandoffSession.Submit.Invalid(setOf("extra")), result)
        assertNull(s.received())
    }

    @Test
    fun `a missing, empty or overlong field is refused and the session stays open`() {
        val s = session()
        assertEquals(HandoffSession.Submit.Invalid(setOf("client_secret")), s.submit("tok", mapOf("client_id" to "abc")))
        assertEquals(HandoffSession.Submit.Invalid(setOf("client_id")), s.submit("tok", good + ("client_id" to "  ")))
        assertEquals(HandoffSession.Submit.Invalid(setOf("client_secret")), s.submit("tok", good + ("client_secret" to "x".repeat(65))))
        assertTrue(s.isOpen())
        assertEquals(HandoffSession.Submit.Accepted, s.submit("tok", good))
    }

    @Test
    fun `control characters are refused`() {
        val s = session()
        assertEquals(HandoffSession.Submit.Invalid(setOf("client_id")), s.submit("tok", good + ("client_id" to "ab\u0000c")))
    }

    @Test
    fun `more than the allowed requests in a minute are rate limited, then allowed again`() {
        val s = session(maxRequests = 3)
        repeat(3) { assertEquals(HandoffSession.Admission.OK, s.admit("tok")) }
        assertEquals(HandoffSession.Admission.RATE_LIMITED, s.admit("tok"))
        now += 61_000
        assertEquals(HandoffSession.Admission.OK, s.admit("tok"))
    }

    @Test
    fun `repeated wrong tokens close the session for good`() {
        val s = session(maxBad = 3)
        repeat(3) { assertEquals(HandoffSession.Admission.BAD_TOKEN, s.admit("guess$it")) }
        assertEquals(HandoffSession.Admission.EXPIRED, s.admit("tok"))
        assertFalse(s.isLive())
    }

    @Test
    fun `closing drops what was received`() {
        val s = session()
        s.submit("tok", good)
        s.close()
        assertNull(s.received())
        assertFalse(s.isLive())
    }

    @Test
    fun `a received secret is shown masked on the console`() {
        assertEquals("abc", HandoffSession.maskForReview("abc", secret = false))
        assertEquals("•••• (5)", HandoffSession.maskForReview("12345", secret = true))
        assertEquals("••••wxyz (26)", HandoffSession.maskForReview("abcdefghijklmnopqrstuvwxyz", secret = true))
    }
}
