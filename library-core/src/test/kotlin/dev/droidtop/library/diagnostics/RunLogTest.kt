package dev.droidtop.library.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunLogTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the collector keeps the last lines and bounds each one`() {
        val collector = RunLogCollector(maxLines = 3, maxLineChars = 10)
        (1..5).forEach { collector.add("line $it") }
        collector.add("x".repeat(50))
        assertEquals("line 4\nline 5\n" + "x".repeat(10) + "...", collector.text())
        assertEquals(6, collector.linesSeen())
    }

    @Test
    fun `lines from two streams at once are all kept`() {
        val collector = RunLogCollector(maxLines = 1000)
        val threads = (0 until 4).map { t -> Thread { repeat(100) { collector.add("t$t $it") } } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals(400, collector.linesSeen())
        assertEquals(400, collector.text().lines().size)
    }

    @Test
    fun `the echo sees the first lines, one notice, then nothing`() {
        val seen = ArrayList<String>()
        val collector = RunLogCollector(maxLines = 2, echoLimit = 3, echo = { seen.add(it) })
        (1..10).forEach { collector.add("l$it") }
        assertEquals(4, seen.size)
        assertEquals(listOf("l1", "l2", "l3"), seen.take(3))
        assertTrue(seen[3].startsWith("more output follows"))
    }

    @Test
    fun `waiting for quiet returns once nothing has arrived for a while`() {
        var now = 0L
        val collector = RunLogCollector(nowMs = { now++ })
        collector.add("a")
        val before = now
        collector.awaitQuiet(quietMs = 5, maxMs = 1000)
        assertTrue(now - before < 1000)
    }

    @Test
    fun `a record names how the run ended, what was printed and the attached logs`() {
        val log = temp.newFile("Player.log").apply { writeText("first\nsecond\n") }
        val attachment = RunRecords.attach("Unity Player.log", log, runStartedMs = 0)!!
        val record = RunRecords.render(
            "Wine run", listOf("Started" to "/g/x.exe", "Exit code" to "137"), "Wine printed",
            "one\n\ntwo\n", listOf(attachment), nowMs = 0,
        )
        assertTrue(record.startsWith("Wine run ended at 1970-01-01T00:00:00Z"))
        assertTrue(record.contains("Exit code: 137"))
        assertTrue(record.contains("Last 2 lines Wine printed:\none\ntwo\n"))
        assertTrue(record.contains("==== Unity Player.log: ${log.path}"))
        assertTrue(record.contains("written during this run"))
        assertTrue(record.trimEnd().endsWith("first\nsecond"))
    }

    @Test
    fun `an attached log is bounded to its end, and an old one says so`() {
        val big = temp.newFile("big.log")
        big.writeText("A".repeat(RunRecords.ATTACHMENT_BYTES) + "THE END")
        val attachment = RunRecords.attach("big", big, runStartedMs = System.currentTimeMillis() + 10 * 60_000)!!
        assertTrue(attachment.truncated)
        assertFalse(attachment.fromThisRun)
        assertEquals(RunRecords.ATTACHMENT_BYTES, attachment.text.length)
        assertTrue(attachment.text.endsWith("THE END"))
        assertNull(RunRecords.attach("none", File(temp.root, "missing.log"), 0))
        assertNull(RunRecords.attach("empty", temp.newFile("empty.log"), 0))
    }

    @Test
    fun `a utf-16 log is read as text`() {
        val file = temp.newFile("u16.txt")
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hello".toByteArray(Charsets.UTF_16LE))
        assertEquals("hello", RunRecords.attach("u16", file, 0)!!.text)
    }

    @Test
    fun `the largest record stays under what Share diagnostics keeps of a file`() {
        assertTrue(RunRecords.MAX_RECORD_BYTES < DiagnosticsArchive.MAX_FILE_BYTES)
    }

    @Test
    fun `each run is saved as the newest and in a short history per game`() {
        val dir = temp.newFolder("logs")
        (1L..5L).forEach { RunRecords.save(dir, "wine", "game-a", "run $it", nowMs = it) }
        RunRecords.save(dir, "wine", "game b", "other", nowMs = 10)
        assertEquals("other", File(dir, "wine-last.log").readText())
        val names = dir.list()!!.sorted()
        assertEquals(
            listOf("wine-last.log", "wine-run-game-a-3.log", "wine-run-game-a-4.log", "wine-run-game-a-5.log", "wine-run-game_b-10.log"),
            names,
        )
        assertEquals("run 5", File(dir, "wine-run-game-a-5.log").readText())
    }

    @Test
    fun `the history in all is bounded too`() {
        val dir = temp.newFolder("logs")
        (1L..20L).forEach { RunRecords.save(dir, "wine", "g$it", "r", nowMs = it) }
        val history = dir.list()!!.filter { it.startsWith("wine-run-") }
        assertEquals(RunRecords.KEEP_TOTAL, history.size)
        assertTrue(File(dir, "wine-run-g20-20.log").exists())
        assertFalse(File(dir, "wine-run-g1-1.log").exists())
    }

    @Test
    fun `a key becomes a safe short file name part`() {
        assertEquals("a_b_c", RunRecords.safeKey("a/b\\c"))
        assertEquals(40, RunRecords.safeKey("x".repeat(100)).length)
        assertEquals("unknown", RunRecords.safeKey(""))
    }
}
