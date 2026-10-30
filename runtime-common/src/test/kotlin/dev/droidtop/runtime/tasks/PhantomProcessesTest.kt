package dev.droidtop.runtime.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Android 12's phantom process limit (docs/SPEC.md "Child processes and Android's limit", Droidtop/tracker#816). */
class PhantomProcessesTest {
    private val proc: File = Files.createTempDirectory("proc").toFile()

    private fun process(pid: Int, uid: Int, cmdline: String) {
        val dir = File(proc, pid.toString()).also { it.mkdirs() }
        File(dir, "status").writeText("Name:\tx\nUmask:\t0077\nState:\tS\nUid:\t$uid\t$uid\t$uid\t$uid\n")
        File(dir, "cmdline").writeText("$cmdline\u0000")
    }

    @Test
    fun `droidtop's children are its own user's processes that are not its app processes`() {
        process(100, 10174, "dev.droidtop.app")
        process(101, 10174, "dev.droidtop.app:pluginhost3")
        process(200, 10174, "/data/app/x/lib/arm64/libproot.so")
        process(201, 10174, "sway")
        process(300, 10200, "com.other.app")
        File(proc, "self").mkdirs()
        assertEquals(2, PhantomProcesses.countOurs(10174, "dev.droidtop.app", proc))
    }

    @Test
    fun `the breakdown names each program, most first, and the line keeps the biggest`() {
        process(100, 10174, "dev.droidtop.app")
        process(200, 10174, "/data/app/x/lib/arm64/libproot.so")
        process(201, 10174, "/data/app/x/lib/arm64/libproot.so")
        process(202, 10174, "/usr/bin/sway")
        process(203, 10174, "Xwayland")
        process(204, 10174, "")
        val breakdown = PhantomProcesses.breakdown(10174, "dev.droidtop.app", proc)
        assertEquals(listOf("libproot.so" to 2, "Xwayland" to 1, "sway" to 1, "x" to 1), breakdown)
        assertEquals("libproot.so 2, Xwayland 1, 2 more", PhantomProcesses.describe(breakdown, top = 2))
        assertEquals("None", PhantomProcesses.describe(emptyList()))
    }

    @Test
    fun `the limit reads as Android's default when nothing set it, and is unknown without an answer`() {
        assertEquals(32, PhantomProcesses.parseLimit("null\n"))
        assertEquals(2147483647, PhantomProcesses.parseLimit("2147483647"))
        assertEquals(null, PhantomProcesses.parseLimit(null))
        assertEquals(false, PhantomProcesses.parseMonitored("false"))
        assertEquals(true, PhantomProcesses.parseMonitored("null"))
        assertEquals(null, PhantomProcesses.parseMonitored(null))
    }

    @Test
    fun `it applies from Android 12 until lifted or unwatched`() {
        assertFalse(PhantomProcesses.Status(30, null, false, 40).applies)
        assertTrue(PhantomProcesses.Status(33, null, null, 40).over)
        assertFalse(PhantomProcesses.Status(33, PhantomProcesses.LIFTED, true, 40).applies)
        assertFalse(PhantomProcesses.Status(34, 32, false, 40).applies)
        assertFalse(PhantomProcesses.Status(33, 32, true, 10).over)
    }

    @Test
    fun `Android 12 and 13 lift it through device_config, 14 through the developer option's flag, and both put it back`() {
        assertEquals("persistent", PhantomProcesses.liftCommands(33)[0].last())
        assertEquals("2147483647", PhantomProcesses.liftCommands(33)[1].last())
        assertEquals(listOf("settings", "put", "global", "settings_enable_monitor_phantom_procs", "false"), PhantomProcesses.liftCommands(34).single())
        assertEquals("none", PhantomProcesses.restoreCommands(33).last().last())
        assertEquals("delete", PhantomProcesses.restoreCommands(34).single()[1])
    }
}
