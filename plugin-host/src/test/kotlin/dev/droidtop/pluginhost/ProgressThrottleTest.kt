package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A download's row moves about four times a second, never misses its last report, and keeps every checkpoint. */
class ProgressThrottleTest {
    private var now = 0L
    private val throttle = ProgressThrottle(250L) { now }

    private fun report(percent: Int, checkpoint: String? = null) = ProgressThrottle.Report(percent, "Downloading", checkpoint)

    @Test
    fun `a thousand reports in one second publish about four times`() {
        var published = 0
        repeat(1000) { i ->
            now = i.toLong()
            if (throttle.offer(report(i / 10))) published++
        }
        assertEquals(4, published)
    }

    @Test
    fun `the report held back is the last one, and is due when the interval is up`() {
        assertTrue(throttle.offer(report(1)))
        now = 100
        assertFalse(throttle.offer(report(2)))
        now = 200
        assertFalse(throttle.offer(report(3)))
        assertEquals(50L, throttle.waitMs())
        now = 250
        assertEquals(3, throttle.takePending()!!.percent)
        assertNull(throttle.takePending())
    }

    @Test
    fun `a new checkpoint is published at once`() {
        assertTrue(throttle.offer(report(10, "chunk-1")))
        now = 10
        assertTrue(throttle.offer(report(11, "chunk-2")))
        now = 20
        assertFalse(throttle.offer(report(12, "chunk-2")))
    }
}
