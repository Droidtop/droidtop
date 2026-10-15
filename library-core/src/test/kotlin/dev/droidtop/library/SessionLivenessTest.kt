package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLivenessTest {
    private fun look(ageMs: Long, gone: Boolean = false, listed: Boolean? = true, own: Boolean = false) =
        SessionObservation(ageMs, gone, listed, own)

    @Test
    fun `a young session is looked at often and an old one rarely`() {
        assertEquals(SessionLiveness.FAST_MS, SessionLiveness.intervalMs(0))
        assertEquals(SessionLiveness.FAST_MS, SessionLiveness.intervalMs(SessionLiveness.FAST_WINDOW_MS - 1))
        assertEquals(SessionLiveness.SLOW_MS, SessionLiveness.intervalMs(SessionLiveness.FAST_WINDOW_MS))
    }

    @Test
    fun `a force-stopped or uninstalled app ends the session at once`() {
        val o = look(ageMs = 1_000, gone = true, listed = null)

        assertTrue(SessionLiveness.ended(o, SessionLiveness.misses(o, 0)))
    }

    @Test
    fun `an app missing from the task list ends the session only after it was given time to appear and was missed twice`() {
        var misses = 0
        // Still inside the grace: not yet counted.
        val early = look(ageMs = SessionLiveness.APPEAR_GRACE_MS - 1, listed = false)
        misses = SessionLiveness.misses(early, misses)
        assertEquals(0, misses)
        assertFalse(SessionLiveness.ended(early, misses))

        val late = look(ageMs = SessionLiveness.APPEAR_GRACE_MS + 3_000, listed = false)
        misses = SessionLiveness.misses(late, misses)
        assertFalse(SessionLiveness.ended(late, misses))
        misses = SessionLiveness.misses(late, misses)
        assertTrue(SessionLiveness.ended(late, misses))
    }

    @Test
    fun `one look that finds the app again resets the count`() {
        val missing = look(ageMs = 60_000, listed = false)
        val present = look(ageMs = 63_000, listed = true)

        val after = SessionLiveness.misses(present, SessionLiveness.misses(missing, 0))

        assertEquals(0, after)
        assertFalse(SessionLiveness.ended(present, after))
    }

    @Test
    fun `without a helper nothing is claimed`() {
        val o = look(ageMs = 10 * 60_000, listed = null)

        assertEquals(0, SessionLiveness.misses(o, 1))
        assertFalse(SessionLiveness.ended(o, 0))
    }

    @Test
    fun `a game screen of droidtop's own is never judged by the task list`() {
        val o = look(ageMs = 60_000, listed = false, own = true)

        assertEquals(0, SessionLiveness.misses(o, 5))
        assertFalse(SessionLiveness.ended(o, 0))
    }
}
