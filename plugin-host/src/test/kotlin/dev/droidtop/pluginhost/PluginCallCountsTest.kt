package dev.droidtop.pluginhost

import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Everyday plugin calls, counted per day (Droidtop/tracker#61). */
class PluginCallCountsTest {
    private val dir: File = Files.createTempDirectory("counts").toFile()
    private val utc = ZoneOffset.UTC
    private val day = 24L * 60 * 60 * 1000

    @Test fun `calls are counted per day and key, busiest first`() {
        val counts = PluginCallCounts(dir)
        repeat(3) { counts.count("acme.tool", "data.private", nowMs = 1_000L + it, zone = utc) }
        counts.count("acme.tool", "log write", nowMs = 2_000, zone = utc)
        counts.count("acme.tool", "data.private", nowMs = day + 5, zone = utc)
        val days = counts.days("acme.tool", nowMs = day + 6)
        assertEquals(listOf("1970-01-02", "1970-01-01"), days.map { it.first })
        assertEquals(listOf("data.private" to 1), days[0].second)
        assertEquals(listOf("data.private" to 3, "log write" to 1), days[1].second)
    }

    @Test fun `counts held in memory reach a new instance over the same folder, and survive a flush`() {
        PluginCallCounts(dir).count("acme.tool", "data.private", nowMs = 1_000, zone = utc)
        val other = PluginCallCounts(dir)
        assertEquals(listOf("data.private" to 1), other.days("acme.tool", nowMs = 1_001).single().second)
        other.count("acme.tool", "data.private", nowMs = 1_002, zone = utc)
        other.flush("acme.tool")
        assertEquals(listOf("data.private" to 2), PluginCallCounts(dir).days("acme.tool").single().second)
    }

    @Test fun `only the newest days are kept`() {
        val counts = PluginCallCounts(dir)
        for (d in 0 until PluginCallCounts.DAYS + 5) counts.count("acme.tool", "k", nowMs = d * day, zone = utc)
        val days = counts.days("acme.tool")
        assertEquals(PluginCallCounts.DAYS, days.size)
        assertEquals(PluginCallCounts.dayOf((PluginCallCounts.DAYS + 4) * day, utc), days.first().first)
    }

    @Test fun `deleting drops the file and what is held`() {
        val counts = PluginCallCounts(dir)
        counts.count("acme.tool", "k", nowMs = 1_000, zone = utc)
        counts.flush("acme.tool")
        counts.count("acme.tool", "k", nowMs = 1_001, zone = utc)
        assertTrue(File(dir, "acme.tool.counts.json").isFile)
        counts.delete("acme.tool")
        assertFalse(File(dir, "acme.tool.counts.json").isFile)
        assertTrue(counts.days("acme.tool").isEmpty())
    }
}
