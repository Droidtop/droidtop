package dev.droidtop.runtime

import dev.droidtop.runtime.systemstatus.NotificationRows
import dev.droidtop.runtime.systemstatus.NotificationsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The notification model's status rules and lines (Droidtop/tracker#786 addendum 2, item 6, slice S13a). */
class NotificationModelTest {
    private fun item(key: String, pkg: String = "com.discord", status: Boolean = false, clearable: Boolean = true) =
        NotificationsStore.Item(
            key = key, packageName = pkg, appLabel = pkg, title = "t", text = "x",
            postTime = 0L, contentIntent = null, clearable = clearable, status = status,
        )

    @Test fun `ongoing, foreground, no-clear and progress are status`() {
        assertTrue(NotificationRows.isStatus(ongoing = true, foregroundService = false, noClear = false, hasProgress = false))
        assertTrue(NotificationRows.isStatus(ongoing = false, foregroundService = true, noClear = false, hasProgress = false))
        assertTrue(NotificationRows.isStatus(ongoing = false, foregroundService = false, noClear = true, hasProgress = false))
        assertTrue(NotificationRows.isStatus(ongoing = false, foregroundService = false, noClear = false, hasProgress = true))
        assertFalse(NotificationRows.isStatus(ongoing = false, foregroundService = false, noClear = false, hasProgress = false))
    }

    @Test fun `Android's own status and a VPN app's are dropped, a message never is`() {
        assertTrue(NotificationRows.dropped(status = true, packageName = "android", vpnProvider = false))
        assertTrue(NotificationRows.dropped(status = true, packageName = "com.tailscale.ipn", vpnProvider = true))
        assertFalse(NotificationRows.dropped(status = true, packageName = "com.github.catfriend1.syncthingandroid", vpnProvider = false))
        assertFalse(NotificationRows.dropped(status = false, packageName = "com.tailscale.ipn", vpnProvider = true))
    }

    @Test fun `progress is a fraction, indeterminate is negative, none is null`() {
        assertEquals(0.25f, NotificationRows.progress(25, 100, false)!!, 0.0001f)
        assertEquals(-1f, NotificationRows.progress(0, 0, true)!!, 0.0001f)
        assertNull(NotificationRows.progress(0, 0, false))
    }

    @Test fun `the line is short and the big text sits behind the expand`() {
        val (line, detail) = NotificationRows.lines("Syncing 3 folders", "Folder A: 12 MiB\nFolder B: idle\ndevice id ABCD", emptyList())
        assertEquals("Syncing 3 folders", line)
        assertEquals("Folder A: 12 MiB\nFolder B: idle\ndevice id ABCD".let(NotificationRows::body), detail)
    }

    @Test fun `without text the line is the big text's first line`() {
        val (line, detail) = NotificationRows.lines(null, "First\nSecond", emptyList())
        assertEquals("First", line)
        assertTrue(detail != null)
    }

    @Test fun `the same text twice has no expand`() {
        assertNull(NotificationRows.lines("gg", "gg", emptyList()).second)
    }

    @Test fun `apps' touch hints are dropped from every line`() {
        assertEquals("", NotificationRows.body("Tap to turn off wireless debugging"))
        assertEquals("Connected to Home Wi-Fi.", NotificationRows.body("Connected to Home Wi-Fi. Tap to see networks."))
        assertEquals("Battery at 15%.", NotificationRows.body("Battery at 15%. touch for options"))
        assertEquals("", NotificationRows.lines("Tap for more options.", null, emptyList()).first)
        assertEquals("Downloading 3 files", NotificationRows.body("Downloading 3 files"))
    }

    @Test fun `rows and status split, Clear all counts only what clears, the heading names each app once`() {
        val items = listOf(item("a"), item("b", status = true, clearable = false), item("c", clearable = false), item("d"))
        assertEquals(listOf("a", "c", "d"), NotificationRows.rows(items).map { it.key })
        assertEquals(listOf("b"), NotificationRows.status(items).map { it.key })
        assertEquals(2, NotificationRows.clearCount(NotificationRows.rows(items)))
        val apps = listOf(item("a", "p1"), item("b", "p1"), item("c", "p2"))
        assertEquals(listOf("p1", "p2"), NotificationRows.headerApps(apps).map { it.packageName })
    }
}
