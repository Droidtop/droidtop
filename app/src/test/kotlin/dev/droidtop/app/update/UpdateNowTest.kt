package dev.droidtop.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two things the UPDATE_NOW trigger promises: only shell, root, the
 * system and droidtop itself can fire it, and firing it ignores every gate
 * that holds the scheduled check back.
 */
class UpdateNowTest {
    @Test
    fun `newer published build installs, same or older does not`() {
        assertEquals(UpdateNow.Verdict.INSTALL, UpdateNow.verdict(507, 508))
        assertEquals(UpdateNow.Verdict.ALREADY_CURRENT, UpdateNow.verdict(507, 507))
        assertEquals(UpdateNow.Verdict.ALREADY_CURRENT, UpdateNow.verdict(507, 500))
    }

    @Test
    fun `the forced pass ignores every gate that stops the scheduled one`() {
        // Updates switched off entirely, checked seconds ago, and on a
        // metered connection with the unmetered-only option set: every
        // reason the scheduled pass has to stay quiet, at once.
        val now = 1_000_000L
        val scheduled = AppSelfUpdate.mayCheck(
            forced = false,
            frequency = AppSelfUpdate.Frequency.OFF,
            lastAttemptMs = now - 1_000,
            nowMs = now,
            unmeteredOnly = true,
            metered = true,
        )
        assertFalse(scheduled)
        val forced = AppSelfUpdate.mayCheck(
            forced = true,
            frequency = AppSelfUpdate.Frequency.OFF,
            lastAttemptMs = now - 1_000,
            nowMs = now,
            unmeteredOnly = true,
            metered = true,
        )
        assertTrue(forced)
    }

    @Test
    fun `the scheduled pass still obeys each gate on its own`() {
        val now = 10L * 24 * 60 * 60 * 1000
        fun scheduled(
            frequency: AppSelfUpdate.Frequency = AppSelfUpdate.Frequency.DAILY,
            lastAttemptMs: Long = 0L,
            unmeteredOnly: Boolean = false,
            metered: Boolean = false,
        ) = AppSelfUpdate.mayCheck(false, frequency, lastAttemptMs, now, unmeteredOnly, metered)

        assertTrue(scheduled())
        assertFalse(scheduled(frequency = AppSelfUpdate.Frequency.OFF))
        assertFalse(scheduled(lastAttemptMs = now - 60_000))
        assertFalse(scheduled(unmeteredOnly = true, metered = true))
        assertTrue(scheduled(unmeteredOnly = true, metered = false))
        assertTrue(scheduled(frequency = AppSelfUpdate.Frequency.WEEKLY, lastAttemptMs = 0L))
    }
}
