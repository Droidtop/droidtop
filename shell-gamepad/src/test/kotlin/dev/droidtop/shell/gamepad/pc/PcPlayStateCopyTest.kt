package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.CopyChoices
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.LibraryGrouping
import dev.droidtop.library.PcInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which copy a card's button acts on (docs/SPEC.md 7i, "Which copy",
 * Droidtop/tracker#397 slice H): an installed copy first, the chosen one when
 * it is installed; the chosen copy when nothing is installed; the stores'
 * order otherwise. A choice whose copy left the card is dropped.
 */
class PcPlayStateCopyTest {

    private fun row(id: String, installed: Boolean) = LibraryEntry(
        id = id,
        title = "Hades",
        kind = LibraryEntryKind.WINE_PROFILE,
        pcInfo = PcInfo(storeId = id, installed = installed, installPath = if (installed) "/sd/Games/${id.replace(':', '_')}" else null),
    )

    private val key = CopyChoices.cardKey("Hades")

    private fun acting(vararg rows: LibraryEntry, chosen: String? = null): String {
        val groups = LibraryGrouping.group(rows.toList(), chosen = chosen?.let { mapOf(key to it) }.orEmpty())
        assertEquals(1, groups.size)
        return groups.single().displayEntry.id
    }

    @Test
    fun `an installed copy is what Play plays`() {
        assertEquals("steam:1", acting(row("steam:1", installed = true), row("gog:1", installed = false)))
        assertEquals("gog:1", acting(row("steam:1", installed = false), row("gog:1", installed = true)))
    }

    @Test
    fun `a chosen copy that is not installed does not take the button from an installed one`() {
        assertEquals("steam:1", acting(row("steam:1", installed = true), row("gog:1", installed = false), chosen = "gog:1"))
    }

    @Test
    fun `once the chosen copy is installed, the button plays it`() {
        assertEquals("gog:1", acting(row("steam:1", installed = true), row("gog:1", installed = true), chosen = "gog:1"))
        // Without a choice, the stores' order.
        assertEquals("steam:1", acting(row("steam:1", installed = true), row("gog:1", installed = true)))
    }

    @Test
    fun `with nothing installed the chosen copy is the one Install acts on`() {
        assertEquals("gog:1", acting(row("steam:1", installed = false), row("gog:1", installed = false), chosen = "gog:1"))
        assertEquals("steam:1", acting(row("steam:1", installed = false), row("gog:1", installed = false)))
    }

    @Test
    fun `a choice whose copy left the card is dropped, and nothing else is`() {
        val groups = LibraryGrouping.group(listOf(row("steam:1", installed = true)))
        assertEquals(listOf(key), CopyChoices.stale(groups, mapOf(key to "gog:1")))
        assertTrue(CopyChoices.stale(groups, mapOf(key to "steam:1")).isEmpty())
        assertTrue(CopyChoices.stale(groups, mapOf("card:other" to "gog:9")).isEmpty())
    }

    @Test
    fun `versions lists each copy with what A does to it and which one the button plays`() {
        val steam = row("steam:1", installed = true)
        val gog = row("gog:1", installed = false)
        val rows = copyRows(listOf(steam, gog), chosen = "gog:1", acting = "steam:1", origin = { it.id.substringBefore(':') }, onRun = {}, onOptions = {})
        assertEquals(listOf("steam", "gog"), rows.map { it.title })
        assertEquals(listOf("Play", "Install"), rows.map { it.value })
        assertEquals("The button plays this one · Installed", rows[0].subtitle)
        assertEquals("Chosen for this game · Not installed", rows[1].subtitle)
        // One copy, or a folder game's versions: no copy rows.
        assertTrue(copyRows(listOf(steam), null, "steam:1", { "" }, {}, {}).isEmpty())
    }
}
