package dev.droidtop.shell.gamepad.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Droidtop/tracker#884: typing "Stardew" into PC Games' search narrowed the grid behind the panel on every key, and
 * on the rig the UI thread stalled and dropped keys. The grid now follows once the text rests.
 */
class GridFollowTest {
    @Test
    fun `a run of keys narrows the grid once, to the last text, after it rests`() {
        val follow = GridFollow(restMs = 300)
        var applied = 0
        // "Stardew" typed by adb: one key every 20 ms, each asking again after the rest.
        "Stardew".indices.forEach { i ->
            val now = i * 20L
            follow.typed("Stardew".substring(0, i + 1), now)
            // A check fired by an earlier key, while keys still come, applies nothing.
            follow.due(now)?.let { applied++ }
        }
        assertEquals(0, applied)
        assertEquals("Stardew", follow.due(6 * 20L + 300))
        assertNull("applied once", follow.due(10_000))
    }

    @Test
    fun `closing the panel applies the text still waiting, at once`() {
        val follow = GridFollow(restMs = 300)
        follow.typed("Sta", 0)
        assertEquals("Sta", follow.flush())
        assertNull(follow.flush())
        assertNull(follow.due(10_000))
    }

    @Test
    fun `nothing typed applies nothing`() {
        val follow = GridFollow()
        assertNull(follow.due(10_000))
        assertNull(follow.flush())
    }
}
