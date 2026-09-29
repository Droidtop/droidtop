package dev.droidtop.pluginhost

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginAuditTest {
    private val dir: File = Files.createTempDirectory("audit").toFile()
    private val audit = PluginAudit(dir, maxEntries = 50)

    private fun entry(at: Long, permission: String = "priv.shell.adb", target: String = "com.example") =
        AuditEntry(at, permission, "priv.shell", "exec", target, listOf("acme.caller"), "ok")

    @Test
    fun `an entry round trips and the last use of a permission is found`() {
        audit.append("acme.tool", entry(1_000), nowMs = 2_000)
        audit.append("acme.tool", entry(3_000, permission = "net.any"), nowMs = 4_000)
        assertEquals(2, audit.entries("acme.tool").size)
        assertEquals(entry(1_000), audit.entries("acme.tool").first())
        assertEquals(1_000L, audit.lastUsed("acme.tool", "priv.shell.adb"))
        assertEquals(3_000L, audit.lastUsed("acme.tool", "net.any"))
        assertNull(audit.lastUsed("acme.tool", "vibrate"))
    }

    @Test
    fun `the ring keeps only the newest entries`() {
        val now = 10_000_000L
        for (i in 0 until 75) audit.append("acme.tool", entry(now + i), nowMs = now + i)
        val entries = audit.entries("acme.tool")
        assertEquals(50, entries.size)
        assertEquals(now + 25, entries.first().atMs)
        assertEquals(now + 74, entries.last().atMs)
        assertEquals("the production ring is 2,000", 2_000, PluginAudit.MAX_ENTRIES)
    }

    @Test
    fun `entries older than 30 days are dropped`() {
        val day = 24L * 60 * 60 * 1000
        audit.append("acme.tool", entry(0), nowMs = 0)
        audit.append("acme.tool", entry(40 * day), nowMs = 40 * day)
        assertEquals(listOf(40 * day), audit.entries("acme.tool").map { it.atMs })
    }

    @Test
    fun `after an uninstall the ring is kept 7 days and marked removed`() {
        val day = 24L * 60 * 60 * 1000
        audit.append("acme.tool", entry(0), nowMs = 0)
        audit.markRemoved("acme.tool", nowMs = 1 * day)
        assertTrue(audit.isRemoved("acme.tool"))
        audit.purgeExpired(nowMs = 7 * day)
        assertEquals("still inside the 7 days", 1, audit.entries("acme.tool").size)
        audit.purgeExpired(nowMs = 9 * day)
        assertTrue(audit.entries("acme.tool").isEmpty())
        assertFalse(audit.isRemoved("acme.tool"))
    }

    @Test
    fun `a plugin with no ring is not marked removed`() {
        audit.markRemoved("acme.none")
        assertFalse(audit.isRemoved("acme.none"))
    }

    @Test
    fun `a plugin installed again after removal starts a live ring`() {
        audit.append("acme.tool", entry(0), nowMs = 0)
        audit.markRemoved("acme.tool", nowMs = 1)
        audit.append("acme.tool", entry(5), nowMs = 5)
        assertFalse(audit.isRemoved("acme.tool"))
    }
}
