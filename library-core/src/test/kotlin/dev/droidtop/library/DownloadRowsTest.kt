package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Downloads tab's rows: one per build and platform, the copy's build first (Droidtop/tracker#838). */
class DownloadRowsTest {
    private val hosts = listOf("GOFILE", "MEGA", "MIXDROP", "PIXELDRAIN", "UPLOADHAVEN")

    private fun line(section: String, vararg systems: String) =
        hosts.map { UpdateSources.Download(section, it, it, "", "https://${it.lowercase()}.example/$section", systems.toList()) }

    private fun item(label: String, role: String) = UpdateSources.Download(if (role == "patch") "Patches" else "Extras", "MEGA", label, "", "https://mega.example/$label", role = role)

    /** The thread in Droidtop/tracker#838: a default build for three platforms, a Steam build for one, patches and extras. */
    private val thread = UpdateSources.Record(
        externalId = "1",
        title = "Game",
        downloads = line("Win/Linux", "windows", "linux") + line("Mac", "mac") + line("Android", "android") +
            line("Steam Build · Win/Linux", "windows", "linux") + item("Incest+", "patch") + item("WALKTHROUGH", "extra") + item("Fan Sig", "extra"),
    )

    @Test
    fun `a Steam copy sees its build first, marked, with the default build folded under Other builds`() {
        val rows = downloadRows(thread, "windows", steamCopy = true).drop(2) // Get the update, System
        assertEquals(listOf("Steam Build · Win/Linux", "Other builds", "Win/Linux", "Patches", "MEGA · Incest+", "Extras", "MEGA · WALKTHROUGH", "MEGA · Fan Sig"), rows.map { it.title })
        assertEquals("Your copy", rows[0].value)
        assertEquals("GOFILE, MEGA, MIXDROP, PIXELDRAIN, UPLOADHAVEN", rows[0].subtitle)
        assertEquals(5, (rows[0].action as RecordAction.Group).downloads.size)
        assertEquals("The default build", rows[1].subtitle)
        assertNull("only the copy's build is marked", rows[2].value)
    }

    @Test
    fun `any other copy sees the default build first`() {
        val rows = downloadRows(thread, "windows", steamCopy = false).drop(2)
        assertEquals(listOf("Win/Linux", "Other builds", "Steam Build · Win/Linux"), rows.take(3).map { it.title })
        assertEquals("Your copy", rows[0].value)
        assertEquals("Steam Build", rows[1].subtitle)
    }

    @Test
    fun `with no copy nothing is marked and the thread's order stands`() {
        val rows = downloadRows(thread, "windows").drop(2)
        assertEquals(listOf("Win/Linux", "Other builds", "Steam Build · Win/Linux"), rows.take(3).map { it.title })
        assertTrue(rows.none { it.value == "Your copy" })
    }

    @Test
    fun `one build, the usual case, is one row per platform and no heading`() {
        val one = thread.copy(downloads = thread.downloads.filterNot { it.section.startsWith("Steam") })
        val rows = downloadRows(one, "mac", steamCopy = false).drop(2)
        assertEquals(listOf("Mac", "Patches", "MEGA · Incest+", "Extras", "MEGA · WALKTHROUGH", "MEGA · Fan Sig"), rows.map { it.title })
        assertNull("one build needs no mark", rows[0].value)
    }

    @Test
    fun `a group's mirrors are tried served first, then in the person's order`() {
        val group = line("Win/Linux", "windows")
        val ordered = DownloadPick.hostOrdered(group, HostOrder.DEFAULT) { it.host == "UPLOADHAVEN" }
        assertEquals(listOf("UPLOADHAVEN", "PIXELDRAIN", "GOFILE", "MEGA", "MIXDROP"), ordered.map { it.host })
    }
}
