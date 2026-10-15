package dev.droidtop.runtime

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** docs/SPEC.md 3 "The image's own program" (Droidtop/tracker#555 D6b): running it, restarting it, its log. */
class ContainerProgramTest {
    @get:Rule val tmp = TemporaryFolder()

    private val nginx = ImageRunConfig(
        env = listOf("NGINX_VERSION=1.27.2"),
        entrypoint = listOf("/docker-entrypoint.sh"),
        cmd = listOf("nginx", "-g", "daemon off;"),
        workingDir = "/srv",
    )

    @Test
    fun `the program runs as argv entries with its output appended to its log`() {
        val argv = ContainerProgram.command(nginx, "droidtop-sibling-1a2b")
        assertEquals("/bin/sh", argv[0])
        assertEquals(listOf("sh", "/run/droidtop-app-storage/container-logs/droidtop-sibling-1a2b.log", "/docker-entrypoint.sh", "nginx", "-g", "daemon off;"), argv.drop(3))
        assertEquals(mapOf("NGINX_VERSION" to "1.27.2", "DROIDTOP_WORKDIR" to "/srv"), ContainerProgram.environment(nginx))
    }

    @Test
    fun `waits grow after each quick failure up to a minute, and a working program restarts only on failure`() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), (1..7).map { ContainerProgram.restartDelayMs(it) })
        assertTrue(ContainerProgram.restarts(restartOnFailure = true, exitCode = 1, failuresInARow = 1))
        assertFalse(ContainerProgram.restarts(restartOnFailure = true, exitCode = 0, failuresInARow = 0))
        assertFalse(ContainerProgram.restarts(restartOnFailure = false, exitCode = 1, failuresInARow = 1))
        assertFalse(ContainerProgram.restarts(restartOnFailure = true, exitCode = 1, failuresInARow = ContainerProgram.MAX_FAILURES_IN_A_ROW))
    }

    @Test
    fun `the log is trimmed in place to its last bytes, from a line start`() {
        val log = tmp.newFile("p.log")
        log.writeText((1..1000).joinToString("") { "line $it\n" })
        // A writer holding the file open, as the running program does, keeps appending at the new end.
        RandomAccessFile(log, "rw").use { writer ->
            assertTrue(ContainerProgram.trim(log, maxBytes = 1000, keepBytes = 100))
            writer.seek(writer.length())
            writer.write("after\n".toByteArray())
        }
        val lines = log.readLines()
        assertEquals("line 1000", lines[lines.size - 2])
        assertEquals("after", lines.last())
        assertTrue(lines.first().startsWith("line "))
        assertTrue(log.length() < 200)
        assertFalse(ContainerProgram.trim(log, maxBytes = 1000, keepBytes = 100))
    }

    @Test
    fun `the page shows the last lines, and nothing for no log`() {
        val log = tmp.newFile("q.log")
        log.writeText((1..100).joinToString("") { "$it\n" })
        assertEquals((61..100).map { "$it" }, ContainerProgram.lastLines(log))
        assertEquals(emptyList<String>(), ContainerProgram.lastLines(File(tmp.root, "none.log")))
    }
}
