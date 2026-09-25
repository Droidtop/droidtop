package dev.droidtop.runtime.linux.noroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProotProcessesTest {
    private val proc: File = Files.createTempDirectory("proc").toFile()

    private fun process(pid: Int, parent: Int, command: String, vararg environment: String) {
        val dir = File(proc, pid.toString()).also { it.mkdirs() }
        File(dir, "stat").writeText("$pid ($command) S $parent $pid $pid 0 -1 4194560\n")
        File(dir, "environ").writeBytes(environment.joinToString("\u0000", postfix = "\u0000").toByteArray())
    }

    @Test
    fun `a container's processes are the marked ones and everything beneath them`() {
        process(100, 1, "dev.droidtop.app", "ANDROID_DATA=/data")
        process(200, 100, "libproot.so", "DROIDTOP_CONTAINER=droidtop-primary", "DROIDTOP_SESSION=a")
        process(201, 200, "sway", "DROIDTOP_CONTAINER=droidtop-primary", "DROIDTOP_SESSION=a")
        // Cleared its environment; still sway's child.
        process(202, 201, "swaybar", "PATH=/bin")
        // Detached from a proot that was killed: parent is init now, marker still there.
        process(300, 1, "foot", "DROIDTOP_CONTAINER=droidtop-primary", "DROIDTOP_SESSION=b")
        process(400, 100, "libproot.so", "DROIDTOP_CONTAINER=droidtop-sibling-1", "DROIDTOP_SESSION=c")

        val processes = ProotProcesses(proc) {}
        assertEquals(setOf(200, 201, 202, 300), processes.ofContainer("droidtop-primary"))
        assertEquals(setOf(200, 201, 202), processes.ofSession("a"))
        assertEquals(setOf(400), processes.ofContainer("droidtop-sibling-1"))
        assertTrue(processes.ofContainer("droidtop-sibling-2").isEmpty())
    }

    @Test
    fun `a marker must match a whole entry`() {
        process(200, 1, "libproot.so", "DROIDTOP_CONTAINER=droidtop-sibling-10")
        assertTrue(ProotProcesses(proc) {}.ofContainer("droidtop-sibling-1").isEmpty())
    }

    @Test
    fun `killing repeats until nothing is left`() {
        process(200, 1, "libproot.so", "DROIDTOP_CONTAINER=x", "DROIDTOP_SESSION=s")
        process(201, 200, "sh", "DROIDTOP_CONTAINER=x", "DROIDTOP_SESSION=s")
        val killed = mutableListOf<Int>()
        val processes = ProotProcesses(proc) { pid ->
            killed += pid
            File(proc, pid.toString()).deleteRecursively()
            // A fork that happened between the scan and the kill.
            if (pid == 201 && !File(proc, "202").exists() && 202 !in killed) process(202, 1, "sh", "DROIDTOP_SESSION=s")
        }
        assertTrue(processes.killSession("s"))
        assertEquals(setOf(200, 201, 202), killed.toSet())
    }

    @Test
    fun `the parent is read after a command name holding spaces and parentheses`() {
        assertEquals(42, ProotProcesses.parentFromStat("7 (a (b) c) S 42 7 7 0"))
    }
}
