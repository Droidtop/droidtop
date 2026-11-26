package dev.droidtop.runtime.linux.noroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** mytop-project/tracker#909: a stand-in only for each /proc file this process cannot read. */
class ProcStandInsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun onlyUnreadableFilesGetAStandIn() {
        val dir = temp.newFolder("etc")
        val binds = ProcStandIns.binds(dir, readable = { it == "/proc/uptime" }, cpus = 4)
        assertEquals(listOf("/proc/stat", "/proc/loadavg", "/proc/vmstat"), binds.map { it.second })
        val stat = File(binds.first { it.second == "/proc/stat" }.first).readText()
        assertTrue(stat.startsWith("cpu  "))
        assertEquals(4, stat.lines().count { Regex("^cpu[0-9]+ ").containsMatchIn(it) })
    }

    @Test
    fun everyReadableFileIsTheKernels() {
        assertEquals(emptyList<Pair<String, String>>(), ProcStandIns.binds(temp.newFolder("etc"), readable = { true }))
    }
}
