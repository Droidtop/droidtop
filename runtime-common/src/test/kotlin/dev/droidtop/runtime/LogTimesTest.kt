package dev.droidtop.runtime

import dev.droidtop.runtime.LogTimes.Mark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogTimesTest {
    @get:Rule val tmp = TemporaryFolder()

    // The log was 0 bytes at 1000, 10 at 2000, 20 at 3000 and 30 at 4000.
    private val marks = listOf(Mark(0, 1000), Mark(10, 2000), Mark(20, 3000), Mark(30, 4000))

    @Test
    fun `a time reads as the log's length at the mark before or after it`() {
        assertEquals(0L until 30L, LogTimes.range(marks, 30, null, null))
        assertEquals(10L until 30L, LogTimes.range(marks, 30, sinceMs = 2500, untilMs = null))
        assertEquals(0L until 20L, LogTimes.range(marks, 30, sinceMs = null, untilMs = 2500))
        assertEquals(10L until 20L, LogTimes.range(marks, 30, sinceMs = 2000, untilMs = 3000))
        // Before every mark: from the start. After every mark: what came after the last.
        assertEquals(0L until 30L, LogTimes.range(marks, 30, sinceMs = 10, untilMs = null))
        assertEquals(30L until 35L, LogTimes.range(marks, 35, sinceMs = 9000, untilMs = null))
        // No marks (a log from before droidtop kept them): the whole log.
        assertEquals(0L until 35L, LogTimes.range(emptyList(), 35, sinceMs = 9000, untilMs = 1))
    }

    @Test
    fun `a trim moves the marks with the bytes and drops the ones of bytes gone`() {
        assertEquals(listOf(Mark(0, 3000), Mark(10, 4000)), LogTimes.shifted(marks, removed = 20))
        val times = tmp.newFile("p.times")
        marks.forEach { LogTimes.append(times, it) }
        LogTimes.shift(times, removed = 10, maxMarks = 2)
        assertEquals(listOf(Mark(10, 3000), Mark(20, 4000)), LogTimes.read(times))
    }

    @Test
    fun `the lines in a range are whole lines`() {
        val log = tmp.newFile("p.log")
        log.writeText("one\ntwo\nthree\nfour\n")
        assertEquals(listOf("one", "two", "three", "four"), LogTimes.lines(log, 0L until log.length()))
        // From inside "two": its next line; from a line start: that line.
        assertEquals(listOf("three", "four"), LogTimes.lines(log, 5L until log.length()))
        assertEquals(listOf("two", "three", "four"), LogTimes.lines(log, 4L until log.length()))
        assertEquals(listOf("two"), LogTimes.lines(log, 4L until 8L))
        assertEquals(emptyList<String>(), LogTimes.lines(log, 8L until 8L))
        assertEquals("p.times", LogTimes.fileFor(log).name)
    }

    @Test
    fun `times read as Docker's and Podman's clients send them`() {
        val now = 1_800_000_000_000L
        assertEquals(1_698_581_234_000L, EngineTime.parse("1698581234", now))
        assertEquals(1_698_581_234_500L, EngineTime.parse("1698581234.500000000", now))
        assertEquals(1_698_573_600_000L, EngineTime.parse("2023-10-29T10:00:00Z", now))
        assertEquals(1_698_566_400_000L, EngineTime.parse("2023-10-29T10:00:00+02:00", now))
        assertEquals(1_698_537_600_000L, EngineTime.parse("2023-10-29", now))
        assertEquals(now - 600_000, EngineTime.parse("10m", now))
        assertEquals(now - 5_400_000, EngineTime.parse("1h30m", now))
        assertEquals(now - 1_500, EngineTime.parse("1.5s", now))
        assertNull(EngineTime.parse("yesterday", now))
        assertNull(EngineTime.parse("10m later", now))
        assertNull(EngineTime.duration(""))
    }
}
