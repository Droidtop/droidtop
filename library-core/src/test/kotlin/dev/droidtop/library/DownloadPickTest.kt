package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** droidtop's own pick among a record's downloads (Droidtop/tracker#838). */
class DownloadPickTest {
    private fun d(section: String, host: String, systems: List<String>, role: String = "build", official: Boolean = true) =
        UpdateSources.Download(section, host, host, "", "https://$host.example/$section/${systems.joinToString()}", systems, role = role, official = official)

    private val record = listOf(
        d("Win/Linux", "MEGA", listOf("windows", "linux")),
        d("Win/Linux", "GOFILE", listOf("windows", "linux")),
        d("Win/Linux", "PIXELDRAIN", listOf("windows", "linux")),
        d("Mac", "MEGA", listOf("mac")),
        d("Android", "GOFILE", listOf("android"), official = false),
        d("Steam Build · Win/Linux", "GOFILE", listOf("windows", "linux")),
        d("Patches", "MEGA", emptyList(), role = "patch"),
    )

    @Test
    fun `the main build for the system droidtop runs, hosts in the person's order`() {
        val picked = DownloadPick.candidates(record, listOf("windows"), steamCopy = false, order = HostOrder.DEFAULT, served = { false })
        assertEquals(listOf("PIXELDRAIN", "GOFILE", "MEGA", "GOFILE"), picked.map { it.host })
        assertEquals("Win/Linux", picked.first().section)
        assertTrue("a Steam build comes after the main one for a non-Steam copy", picked.last().section.startsWith("Steam"))
        assertTrue("never a patch, never another system", picked.none { it.role != "build" || "mac" in it.systems })
    }

    @Test
    fun `a Steam copy takes the Steam build first, and a served link leads`() {
        val picked = DownloadPick.candidates(record, listOf("windows"), steamCopy = true, order = HostOrder.DEFAULT, served = { it.host == "MEGA" })
        assertEquals("Steam Build · Win/Linux", picked.first().section)
        assertEquals("MEGA", picked[1].host)
    }

    @Test
    fun `an unofficial port is never picked`() {
        assertTrue(DownloadPick.candidates(record, listOf("android"), steamCopy = false, order = HostOrder.DEFAULT, served = { false }).isEmpty())
    }
}
