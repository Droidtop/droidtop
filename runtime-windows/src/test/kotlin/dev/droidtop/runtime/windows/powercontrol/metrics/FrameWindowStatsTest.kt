package dev.droidtop.runtime.windows.powercontrol.metrics

import org.junit.Assert.assertEquals
import org.junit.Test

/** Frame statistics from raw timestamps: too few samples is EMPTY, steady 10 ms frames read 100 fps. */
class FrameWindowStatsTest {

    private val scratch = LongArray(16)

    @Test
    fun `fewer than two timestamps is empty`() {
        assertEquals(FrameWindowStats.EMPTY, computeFrameWindowStats(longArrayOf(5L), 1, 0L, scratch))
    }

    @Test
    fun `steady ten millisecond frames read one hundred fps`() {
        val ts = longArrayOf(0L, 10_000_000L, 20_000_000L, 30_000_000L, 40_000_000L)
        val stats = computeFrameWindowStats(ts, ts.size, 0L, scratch)
        assertEquals(4, stats.totalFrameCount)
        assertEquals(100f, stats.fps, 0.01f)
        assertEquals(10f, stats.p50Ms, 0.001f)
        assertEquals(10f, stats.maxMs, 0.001f)
        assertEquals(0, stats.slowFrameCount)
    }

    @Test
    fun `a frame over the threshold is counted slow`() {
        val ts = longArrayOf(0L, 10_000_000L, 20_000_000L, 70_000_000L)
        val stats = computeFrameWindowStats(ts, ts.size, 33_000_000L, scratch)
        assertEquals(1, stats.slowFrameCount)
        assertEquals(50f, stats.maxMs, 0.001f)
    }
}
