package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The record page's downloads as the console showed them on Thief of Hearts (Droidtop/tracker#859): one host under
 * two names, a section heading drawn as a row, two links that read the same, and a build's hosts to choose from.
 */
class RecordHostsTest {
    private fun d(host: String, section: String = "Win/Linux", label: String = host, url: String? = "https://$host.example/x", role: String = "build", systems: List<String> = listOf("windows", "linux")) =
        UpdateSources.Download(section = section, host = host, label = label, link = "", url = url, systems = systems, role = role)

    @Test
    fun `BZZHR is BuzzHeavier, by the name the thread also used`() {
        assertEquals(HostNames.key("BUZZHEAVIER"), HostNames.key("BZZHR"))
        assertEquals(HostNames.key("buzzheavier.com"), HostNames.key("bzzhr.co"))
        assertEquals(listOf("BUZZHEAVIER", "MEGA"), HostNames.distinct(listOf("BZZHR", "BUZZHEAVIER", "MEGA")))
        assertEquals("BuzzHeavier", HostNames.display("BZZHR"))
        assertEquals("GOFILE", HostNames.display("GOFILE"))
        assertEquals(HostNames.key("MEGA"), HostNames.key("mega.co.nz"))
    }

    @Test
    fun `a build's row lists each host once`() {
        val record = UpdateSources.Record(externalId = "1", title = "Thief of Hearts", downloads = listOf(d("BZZHR"), d("BUZZHEAVIER"), d("MEGA")))
        val row = downloadRows(record, picked = "windows").first { it.action is RecordAction.Group }
        assertEquals("BUZZHEAVIER, MEGA", row.subtitle)
    }

    @Test
    fun `a build's host list starts with Try in order, then each host once with its label`() {
        val group = listOf(d("BZZHR"), d("BUZZHEAVIER"), d("MEGA", label = "MEGA (part 1)"))
        val choices = hostChoices(group)
        assertEquals(listOf("Try in order", "BUZZHEAVIER", "MEGA"), choices.map { it.title })
        assertTrue(choices.first().inOrder)
        assertEquals(group, choices.first().downloads)
        assertEquals(listOf(group[0]), choices[1].downloads)
        assertEquals("MEGA (part 1)", choices[2].subtitle)
    }

    @Test
    fun `a section heading is a heading, and focus passes over it`() {
        val record = UpdateSources.Record(
            externalId = "1",
            title = "Thief of Hearts",
            downloads = listOf(
                d("MEGA"),
                d("Italian Translation", section = "Extras", url = "https://mega.nz/a", role = "extra", systems = emptyList()),
                d("Italian Translation", section = "Extras", url = "https://gofile.io/b", role = "extra", systems = emptyList()),
            ),
        )
        val rows = downloadRows(record, picked = "windows")
        val heading = rows.indexOfFirst { it.title == "Extras" }
        assertTrue(rows[heading].heading)
        assertNull(rows[heading].action)
        assertEquals(heading + 1, nextFocusable(rows, heading - 1, 1))
        assertEquals(heading - 1, nextFocusable(rows, heading + 1, -1))
        assertFalse(rows[firstFocusable(rows)].heading)
        // The two "Italian Translation" links say where each goes.
        assertEquals(listOf("mega.nz", "gofile.io"), rows.filter { it.title == "Italian Translation" }.map { it.value })
    }

    @Test
    fun `two links that read the same and point at one host are numbered`() {
        val record = UpdateSources.Record(
            externalId = "1",
            title = "Thief of Hearts",
            downloads = listOf(
                d("Patch", section = "Patches", url = "https://mega.nz/a", role = "patch", systems = emptyList()),
                d("Patch", section = "Patches", url = "https://mega.nz/b", role = "patch", systems = emptyList()),
            ),
        )
        assertEquals(listOf("Link 1", "Link 2"), downloadRows(record, null).filter { it.title == "Patch" }.map { it.value })
    }

    @Test
    fun `versions compare and show in one form`() {
        // Thief of Hearts: the thread's "P3 Ep.10 v0.0.10" beside the folder's "v0.0.9".
        assertEquals("0.0.10", GameUpdates.sameForm("P3 Ep.10 v0.0.10", "v0.0.9"))
        assertEquals("P3 Ep.10 v0.0.10", GameUpdates.sameForm("P3 Ep.10 v0.0.10", null))
        assertNull(GameUpdates.available("P3 Ep.10 v0.0.10", listOf("0.0.10")))
        assertEquals("P3 Ep.10 v0.0.10", GameUpdates.available("P3 Ep.10 v0.0.10", listOf("0.0.9")))
        // A version that does not end in a number is kept whole.
        assertEquals("Steam Build", GameUpdates.comparable("Steam Build"))
        assertEquals("1.0 Part 2", GameUpdates.comparable("1.0 Part 2"))
        assertEquals("1.0 Part 2", GameUpdates.available("1.0 Part 2", listOf("1.0")))
    }
}
