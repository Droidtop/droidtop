package dev.droidtop.app.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TerminalLaunchTest {
    @Test
    fun `the environment goes to the pseudo-terminal as NAME=value lines, in order`() {
        val lines = TerminalLaunch.environmentLines(linkedMapOf("PATH" to "/system/bin", "TERM" to "xterm-256color", "EMPTY" to ""))
        assertArrayEquals(arrayOf("PATH=/system/bin", "TERM=xterm-256color", "EMPTY="), lines)
    }

    @Test
    fun `a name that cannot be one is left out`() {
        val lines = TerminalLaunch.environmentLines(linkedMapOf("" to "x", "A=B" to "y", "C" to "a\u0000b", "OK" to "1"))
        assertArrayEquals(arrayOf("OK=1"), lines)
    }

    @Test
    fun `the relay runs on Android's shell with the two FIFOs as arguments, never in the script`() {
        val argv = TerminalLaunch.relayArgv("/cache/terminal/x/out", "/cache/terminal/x/in")
        assertEquals("/system/bin/sh", argv[0])
        assertEquals("-c", argv[1])
        assertEquals(listOf("relay", "/cache/terminal/x/out", "/cache/terminal/x/in"), argv.drop(3))
        assertTrue(argv[2].startsWith("stty raw -echo"))
        assertTrue(!argv[2].contains("/cache"))
    }

    /** The relay's copying, run for real by a POSIX shell on two FIFOs: typed bytes reach the shell side, its output the screen, and it ends with 0 when droidtop closes the output. */
    @Test
    fun `the relay carries bytes both ways and ends when the output closes`() {
        org.junit.Assume.assumeTrue(File("/bin/sh").canExecute() && File("/usr/bin/mkfifo").canExecute())
        val dir = kotlin.io.path.createTempDirectory("relay").toFile()
        val out = File(dir, "out")
        val input = File(dir, "in")
        listOf(out, input).forEach { assertEquals(0, ProcessBuilder("/usr/bin/mkfifo", it.path).start().waitFor()) }
        val argv = TerminalLaunch.relayArgv(out.path, input.path).toMutableList().apply { this[0] = "/bin/sh" }
        val relay = ProcessBuilder(argv).start()
        val typed = java.io.FileInputStream(input)
        val screen = java.io.FileOutputStream(out)
        relay.outputStream.write("ls\n".toByteArray())
        relay.outputStream.flush()
        val got = ByteArray(3)
        var n = 0
        while (n < 3) n += typed.read(got, n, 3 - n)
        assertEquals("ls\n", String(got))
        screen.write("hello\n".toByteArray())
        screen.close()
        assertEquals(0, relay.waitFor())
        assertTrue(relay.inputStream.bufferedReader().readText().contains("hello"))
        typed.close()
        dir.deleteRecursively()
    }
}
