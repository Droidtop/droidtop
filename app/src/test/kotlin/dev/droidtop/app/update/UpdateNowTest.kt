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

    @Test
    fun `a pass already running is joined, not started twice`() {
        assertTrue(UpdateNow.begin())
        assertTrue(UpdateNow.pass.value.running)
        assertFalse(UpdateNow.begin())
        UpdateNow.finish("done")
        assertFalse(UpdateNow.pass.value.running)
        assertEquals("done", UpdateNow.pass.value.outcome)
        assertTrue(UpdateNow.begin())
        assertEquals(null, UpdateNow.pass.value.outcome)
        UpdateNow.finish("again")
    }

    @Test
    fun `a started pass runs off the caller and hands off only once it has run`() {
        val spawned = mutableListOf<Runnable>()
        var ran = false
        var handedOff = 0
        assertTrue(UpdateNow.launch({ ran = true }, { handedOff++ }) { spawned += it })
        assertFalse(ran)
        assertEquals(0, handedOff)
        assertEquals(1, spawned.size)
        spawned.single().run()
        assertTrue(ran)
        assertEquals(1, handedOff)
        UpdateNow.finish("done")
    }

    @Test
    fun `joining a running pass hands off at once and starts nothing`() {
        assertTrue(UpdateNow.begin())
        val spawned = mutableListOf<Runnable>()
        var handedOff = 0
        assertFalse(UpdateNow.launch({ error("must not run") }, { handedOff++ }) { spawned += it })
        assertEquals(1, handedOff)
        assertTrue(spawned.isEmpty())
        UpdateNow.finish("done")
    }

    @Test
    fun `the hand off still happens when the pass throws`() {
        val spawned = mutableListOf<Runnable>()
        var handedOff = 0
        UpdateNow.launch({ error("boom") }, { handedOff++ }) { spawned += it }
        runCatching { spawned.single().run() }
        assertEquals(1, handedOff)
        UpdateNow.finish("done")
    }

    @Test
    fun `the UPDATE_NOW broadcast is held under Android's broadcast timeouts`() {
        // BROADCAST_FG_TIMEOUT is 10 s and BROADCAST_BG_TIMEOUT 60 s; adb's am broadcast is a background one.
        assertTrue(UpdateNow.broadcastHoldMs(foreground = true) < 10_000L)
        assertTrue(UpdateNow.broadcastHoldMs(foreground = false) < 60_000L)
        assertTrue(UpdateNow.broadcastHoldMs(foreground = false) > UpdateNow.broadcastHoldMs(foreground = true))
    }
}
