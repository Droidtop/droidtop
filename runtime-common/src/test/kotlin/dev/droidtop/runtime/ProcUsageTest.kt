package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A running container's use read from /proc (docs/SPEC.md 3d, "Resource usage"; Droidtop/tracker#757 slice 3). */
class ProcUsageTest {
    // Field 2 is the command, which may hold spaces and brackets; utime 120 and stime 30 are fields 14 and 15.
    private val stat = "4242 (Web Content (x)) S 1 4242 4242 0 -1 4194560 100 0 0 0 120 30 0 0 20 0 9 0 5000 1000000 2000 18446744073709551615"

    @Test
    fun `cpu time is utime plus stime, counted after the command's last bracket`() {
        assertEquals(150L, ProcUsage.cpuTicks(stat))
        assertNull(ProcUsage.cpuTicks("not a stat line"))
        assertNull(ProcUsage.cpuTicks("1 (short) S 1"))
    }

    @Test
    fun `memory is the resident set, in bytes`() {
        assertEquals(2048L * 1024, ProcUsage.memoryBytes("Name:\tbash\nVmPeak:\t 9000 kB\nVmRSS:\t    2048 kB\n"))
        assertEquals(0L, ProcUsage.memoryBytes("Name:\tkthreadd\n"))
    }

    @Test
    fun `the processor share is the ticks used over the device's ticks in the time`() {
        val first = listOf(ProcUsage.Process(1, 100, 1000), ProcUsage.Process(2, 50, 500))
        // Process 1 used 50 ticks, process 2 ended, process 3 started: only 1 counts. 100 ticks a second, 1 s, 2 cores.
        val second = listOf(ProcUsage.Process(1, 150, 3000), ProcUsage.Process(3, 999, 700))
        val usage = ProcUsage.usage(first, second, elapsedMs = 1000, ticksPerSecond = 100, cores = 2)
        assertEquals(2, usage.processes)
        assertEquals(3700L, usage.memoryBytes)
        assertEquals(25.0, usage.cpuPercent, 0.001)
        assertEquals(0.0, ProcUsage.usage(first, first, elapsedMs = 0, ticksPerSecond = 100, cores = 2).cpuPercent, 0.0)
    }

    @Test
    fun `the root shell's output reads back, a process that ended between lines left out`() {
        val output = """
            P 10
            $stat
            VmRSS:	    1024 kB
            P 11
            P 12
            12 (sh) S 10 12 12 0 -1 0 0 0 0 0 7 3 0 0 20 0 1 0 1 1 1 1
            END
        """.trimIndent()
        assertEquals(
            listOf(ProcUsage.Process(10, 150, 1024L * 1024), ProcUsage.Process(12, 10, 0)),
            ProcUsage.parse(output),
        )
    }

    @Test
    fun `the root script looks in the init's own PID namespace`() {
        val script = ProcUsage.namespaceScript(4242)
        assertEquals(true, script.startsWith("ns=\$(readlink /proc/4242/ns/pid)"))
        assertEquals(true, script.trimEnd().endsWith("echo END"))
    }
}
