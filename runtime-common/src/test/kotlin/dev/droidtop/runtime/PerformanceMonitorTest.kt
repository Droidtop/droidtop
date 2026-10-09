package dev.droidtop.runtime

import dev.droidtop.runtime.systemstatus.CpuTimes
import dev.droidtop.runtime.systemstatus.PerformanceMonitor
import dev.droidtop.runtime.systemstatus.RingBuffer
import dev.droidtop.runtime.systemstatus.SystemControls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import kotlinx.coroutines.launch
import org.junit.Test

class PerformanceMonitorTest {
    @Test
    fun `the aggregate cpu line parses and counts idle plus iowait as idle`() {
        val times = PerformanceMonitor.parseCpuStat("cpu  100 0 50 800 50 0 0 0 0 0")
        assertEquals(CpuTimes(total = 1000, idle = 850), times)
    }

    @Test
    fun `a per-core line or junk is not the aggregate`() {
        assertNull(PerformanceMonitor.parseCpuStat("cpu0 1 2 3 4 5"))
        assertNull(PerformanceMonitor.parseCpuStat("intr 12345"))
        assertNull(PerformanceMonitor.parseCpuStat(null))
        assertNull(PerformanceMonitor.parseCpuStat("cpu  1 2"))
    }

    @Test
    fun `load is the busy share between two readings`() {
        val a = CpuTimes(total = 1000, idle = 800)
        val b = CpuTimes(total = 1200, idle = 850)
        assertEquals(75, PerformanceMonitor.cpuPercent(a, b))
        assertNull(PerformanceMonitor.cpuPercent(null, b))
        assertNull(PerformanceMonitor.cpuPercent(b, b))
    }

    @Test
    fun `own cpu share is spread over the cores and clamped`() {
        assertEquals(25, PerformanceMonitor.ownCpuPercent(cpuMs = 500, wallMs = 2000, cores = 1))
        assertEquals(6, PerformanceMonitor.ownCpuPercent(cpuMs = 1000, wallMs = 2000, cores = 8))
        assertEquals(100, PerformanceMonitor.ownCpuPercent(cpuMs = 9000, wallMs = 1000, cores = 1))
        assertNull(PerformanceMonitor.ownCpuPercent(cpuMs = 10, wallMs = 0, cores = 4))
    }

    @Test
    fun `memory used is the share that is not available`() {
        assertEquals(75, PerformanceMonitor.memUsedPercent(totalMb = 8000, availMb = 2000))
        assertEquals(0, PerformanceMonitor.memUsedPercent(totalMb = 0, availMb = 0))
    }

    @Test
    fun `the ring keeps only the newest values in order`() {
        val ring = RingBuffer<Int>(3)
        (1..5).forEach { ring.add(it) }
        assertEquals(listOf(3, 4, 5), ring.toList())
    }

    @Test
    fun `cpuinfo rows become apps and the rest is skipped`() {
        val dump = """
            Load: 4.5 / 3.2 / 2.9
            CPU usage from 5000ms to 0ms ago:
              25% 1234/com.example.one: 20% user + 5% kernel
              3.5% 99/system_server: 2% user + 1.5% kernel
              0% 7/kworker: 0% user
            50% TOTAL: 30% user + 20% kernel
        """.trimIndent()
        val apps = PerformanceMonitor.parseTopApps(dump, limit = 2)
        assertEquals(listOf("com.example.one", "system_server"), apps.map { it.packageName })
        assertEquals(25.0, apps[0].percent, 0.001)
        assertEquals(0, PerformanceMonitor.parseTopApps(null).size)
    }

    @Test
    fun `thermal and temperature text are plain and honest about missing data`() {
        assertEquals("Not reported", PerformanceMonitor.thermalLabel(null))
        assertEquals("Normal", PerformanceMonitor.thermalLabel(0))
        assertEquals("Severe throttling", PerformanceMonitor.thermalLabel(3))
        assertEquals("36.5 C", PerformanceMonitor.tempText(365))
        assertEquals("Not reported", PerformanceMonitor.tempText(null))
    }

    @Test
    fun `radio commands are the shell's own`() {
        assertEquals(listOf("svc", "wifi", "enable"), SystemControls.radioCommand(SystemControls.Radio.WIFI, true))
        assertEquals(listOf("svc", "bluetooth", "disable"), SystemControls.radioCommand(SystemControls.Radio.BLUETOOTH, false))
        assertEquals(
            listOf("cmd", "connectivity", "airplane-mode", "enable"),
            SystemControls.radioCommand(SystemControls.Radio.AIRPLANE, true),
        )
    }

    @Test
    fun `a watcher counts while it runs and the count drops to zero when its tab goes`() {
        kotlinx.coroutines.runBlocking {
            assertEquals(0, PerformanceMonitor.subscribers.value)
            val first = launch { PerformanceMonitor.watchWith(10L) {} }
            val second = launch { PerformanceMonitor.watchWith(10L) { error("a failed reading does not end the loop") } }
            kotlinx.coroutines.delay(50)
            assertEquals(2, PerformanceMonitor.subscribers.value)
            first.cancel()
            first.join()
            assertEquals(1, PerformanceMonitor.subscribers.value)
            second.cancel()
            second.join()
            assertEquals(0, PerformanceMonitor.subscribers.value)
        }
    }

    @Test
    fun `a screen timeout reads in words, the largest value as Never`() {
        assertEquals("Never", SystemControls.timeoutLabel(Int.MAX_VALUE))
        assertEquals("30 seconds", SystemControls.timeoutLabel(30_000))
        assertEquals("1 hour", SystemControls.timeoutLabel(3_600_000))
        assertEquals("2 hours", SystemControls.timeoutLabel(7_200_000))
        assertEquals("20 minutes", SystemControls.timeoutLabel(1_200_000))
        assertEquals("1 h 30 min", SystemControls.timeoutLabel(5_400_000))
    }
}
