package dev.droidtop.runtime.systemstatus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Performance > Logs (LogReader.kt, Droidtop/tracker#414 slice C18). */
class LogReaderTest {
    private val crash = "10-09 23:01:02.345  4321  4321 E AndroidRuntime: FATAL EXCEPTION: main"
    private val info = "10-09 23:01:03.000  1234  1250 I ActivityManager   : Start proc 4321:com.example.game/u0a99"

    @Test fun `threadtime lines are read, headers are not`() {
        val e = LogReader.parse(crash)!!
        assertEquals("10-09 23:01:02.345", e.time)
        assertEquals(4321, e.pid)
        assertEquals(LogReader.Level.ERROR, e.level)
        assertEquals("AndroidRuntime", e.tag)
        assertEquals("FATAL EXCEPTION: main", e.message)
        assertEquals("ActivityManager", LogReader.parse(info)!!.tag)
        assertNull(LogReader.parse("--------- beginning of crash"))
    }

    @Test fun `filters by level, tag and app`() {
        val e = LogReader.parse(crash)!!
        val i = LogReader.parse(info)!!
        val warnings = LogReader.Filter(minLevel = LogReader.Level.WARN)
        assertTrue(LogReader.matches(e, warnings))
        assertFalse(LogReader.matches(i, warnings))
        assertTrue(LogReader.matches(i, LogReader.Filter(tag = "activitymanager")))
        assertFalse(LogReader.matches(e, LogReader.Filter(tag = "ActivityManager")))
        assertFalse(LogReader.matches(i, LogReader.Filter(pids = setOf(4321))))
    }

    @Test fun `the running-game filter keeps the game's processes only`() {
        val pids = LogReader.parsePids("4321 4400\n")
        assertEquals(setOf(4321, 4400), pids)
        val filter = LogReader.Filter(pids = pids)
        assertTrue(LogReader.matches(LogReader.parse(crash)!!, filter))
        assertFalse(LogReader.matches(LogReader.parse(info)!!, filter))
        assertEquals(emptySet<Int>(), LogReader.parsePids(""))
    }

    @Test fun `a refresh adds only lines newer than the last shown`() {
        val both = listOfNotNull(LogReader.parse(crash), LogReader.parse(info))
        assertEquals(listOf(LogReader.parse(info)), LogReader.newerThan(both, "10-09 23:01:02.345"))
        assertEquals(both, LogReader.newerThan(both, null))
    }
}
