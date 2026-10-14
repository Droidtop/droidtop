package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's strip (docs/SPEC.md 7i, "Home"; Droidtop/tracker#485 F7a, final design B.2 and D.1). */
class HomeModelTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L

    private fun rom(id: String, system: String = "snes", added: Long = now - day, hidden: Boolean = false, art: String? = "art.png") =
        LibraryEntry(id = id, title = id, kind = LibraryEntryKind.CONSOLE_ROM, systemId = system, firstSeenEpochMs = added, hidden = hidden, artworkUri = art)

    private fun pc(id: String, update: String? = null, added: Long = 0L) =
        LibraryEntry(id = id, title = id, kind = LibraryEntryKind.RENPY, availableUpdate = update, firstSeenEpochMs = added)

    private val names = mapOf("snes" to "SNES", "psx" to "PlayStation")
    private fun added(entries: List<LibraryEntry>) =
        HomeModel.added(entries, now, placeOf = { e -> e.systemId?.let { names[it] } ?: "PC Games" }, systemOf = { it.systemId })

    @Test
    fun `additions are grouped by place and scan and counted as the library counts`() {
        val cards = added(List(212) { rom("/snes/$it") } + rom("/hidden", hidden = true) + rom("/psx/one", system = "psx", added = now - 2 * day))
        val snes = cards.single { it.systemId == "snes" }
        assertEquals("212 games added to SNES", snes.title)
        val one = cards.single { it.systemId == "psx" }
        assertEquals("/psx/one", one.title)
        assertEquals("Added 2 days ago", one.line)
        // Older than the window is not news.
        assertTrue(added(listOf(rom("/old", added = now - 30 * day))).isEmpty())
    }

    @Test
    fun `what's new is newest first, capped, and the attention card is one and always last`() {
        val updates = HomeModel.updates(listOf(pc("/a", update = "0.9.6", added = now - 3 * day), pc("/b")))
        assertEquals("v0.9.6", updates.single().line)
        val list = HomeModel.whatsNew(updates, added(listOf(rom("/x"))), pluginUpdates = 2, attention = 3)
        assertEquals(listOf("added", "update", "plugin-updates", "attention"), list.map { it.id.substringBefore(':') })
        assertEquals("3 things need attention", list.last().title)

        val many = (0 until 20).map { HomeCard.Added("Place $it", null, 1, now - it * 1000L, null, now) }
        val capped = HomeModel.whatsNew(emptyList(), many, pluginUpdates = 0, attention = 1)
        assertEquals(HomeModel.WHATS_NEW_CAP + 1, capped.size)
        assertEquals("+9 more", capped[HomeModel.WHATS_NEW_CAP - 1].title)
        assertTrue(capped.last() is HomeCard.Attention)
        assertTrue(HomeModel.whatsNew(emptyList(), emptyList(), 0, 0).isEmpty())
    }

    @Test
    fun `the strip shows what's new always, friends with a service on, downloads while one runs, and keeps the active tab`() {
        val tabs = HomeModel.tabs(emptyList(), friendsOn = false, friends = emptyList(), downloads = emptyList())
        assertEquals(listOf(HomeTabKind.WHATS_NEW), tabs.map { it.kind })
        assertEquals("You're up to date", tabs.single().empty)
        val job = HomeCard.Download("j", "Hades", 45, 8 * 60, paused = false)
        val busy = HomeModel.tabs(emptyList(), friendsOn = true, friends = emptyList(), downloads = listOf(job))
        assertEquals(listOf("What's new", "Friends", "Downloads · 1"), busy.map { it.label })
        assertEquals("45% · About 8 min left", job.line)
        // The download finished while the cursor was on its tab: the tab stays, empty.
        val kept = HomeModel.tabs(emptyList(), friendsOn = false, friends = emptyList(), downloads = emptyList(), keep = HomeTabKind.DOWNLOADS)
        assertEquals(listOf(HomeTabKind.WHATS_NEW, HomeTabKind.DOWNLOADS), kept.map { it.kind })
    }

    @Test
    fun `BIOS items cover only systems with games and tell missing from wrong`() {
        val items = HomeModel.biosItems(mapOf("psx" to "PlayStation"), missing = mapOf("psx" to 1, "saturn" to 2), wrong = mapOf("psx" to 1))
        assertEquals(listOf("PlayStation needs a BIOS", "PlayStation BIOS doesn't match"), items.map { it.title })
        assertTrue(items.all { it.verb == "Set up PlayStation" })
    }

    @Test
    fun `a seen item stays quiet until its state changes`() {
        val item = HomeModel.artworkItem(34)!!
        assertEquals("34 games have no artwork", item.title)
        assertTrue(HomeModel.unseen(listOf(item), mapOf(item.id to item.state)).isEmpty())
        val more = HomeModel.artworkItem(35)!!
        assertEquals(listOf(more), HomeModel.unseen(listOf(more), mapOf(item.id to item.state)))
    }

    @Test
    fun `missing artwork counts only games with no picture`() {
        assertTrue(HomeModel.lacksArt(rom("/a", art = null)))
        assertTrue(!HomeModel.lacksArt(rom("/b")))
    }

    @Test
    fun `a library of ten thousand games builds in linear time`() {
        val big = List(10_000) { rom("/g/$it", system = if (it % 2 == 0) "snes" else "psx", added = now - (it % 20) * day) }
        val start = System.nanoTime()
        val cards = added(big)
        HomeModel.whatsNew(HomeModel.updates(big), cards, 0, 0)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms < 2_000)
        assertEquals(big.count { now - it.firstSeenEpochMs < HomeModel.ADDED_WINDOW_DAYS * day }, cards.sumOf { it.count })
    }
}
