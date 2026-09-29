package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Switch fold (docs/SPEC.md 7m, "Switch content"): an update and a
 * DLC are parts of their base game, never games of their own. These
 * tests drive [SwitchGameGrouping.fold] through the filename-tag route
 * of [dev.droidtop.library.consoles.SwitchContent] -- the container's
 * ticket-name route is the classification's own test's job
 * (SwitchContentTest), not this one's.
 *
 * Every title ID here has the shape real ones have: 16 hex characters,
 * last three `000` (base) / `800` (update) / add-on index.
 */
class SwitchGameGroupingTest {

    private fun switchEntry(name: String): LibraryEntry = LibraryEntry(
        id = "/sdcard/Roms/switch/$name",
        title = name.substringBeforeLast('.'),
        kind = LibraryEntryKind.CONSOLE_ROM,
        systemId = "switch",
    )

    private fun fold(vararg names: String): List<LibraryEntry> =
        SwitchGameGrouping.fold(names.map(::switchEntry))

    @Test
    fun `an update and a dlc fold into the base game, which says what it carries`() {
        val rows = fold(
            "Zelda [01007ef00011e000].xci",
            "Zelda [01007ef00011e800][v131072].nsp",
            "Zelda [01007ef00011f000].nsp",
        )
        assertEquals(1, rows.size)
        val facts = rows.single().switchFacts!!
        assertEquals("01007ef00011e000", facts.baseTitleId)
        assertTrue(facts.hasUpdate)
        assertEquals("131072", facts.updateVersion)
        assertEquals(1, facts.dlcCount)
        assertEquals(listOf("/sdcard/Roms/switch/Zelda [01007ef00011e800][v131072].nsp"), facts.updatePaths)
        assertEquals(listOf("/sdcard/Roms/switch/Zelda [01007ef00011f000].nsp"), facts.dlcPaths)
        assertEquals("Update v131072 · 1 DLC", facts.line())
    }

    @Test
    fun `a base game with no update and no dlc carries empty facts, and says nothing`() {
        val rows = fold("Zelda [01007ef00011e000].xci")
        val facts = rows.single().switchFacts!!
        assertEquals("01007ef00011e000", facts.baseTitleId)
        assertTrue(!facts.hasUpdate)
        assertEquals(0, facts.dlcCount)
        assertEquals("", facts.line())
    }

    @Test
    fun `the newest version tag wins when several update files are on the device`() {
        val rows = fold(
            "Game [0100aa000000e000].nsp",
            "Game [0100aa000000e800][v0].nsp",
            "Game [0100aa000000e800][v65536].nsp",
        )
        assertEquals(1, rows.size)
        assertEquals("65536", rows.single().switchFacts!!.updateVersion)
        assertEquals(2, rows.single().switchFacts!!.updatePaths.size)
    }

    @Test
    fun `dlc counts add-on packages, not files`() {
        val rows = fold(
            "Game [0100aa000000e000].nsp",
            "Game [0100aa000000e001].nsp",
            "Game [0100aa000000e002].nsp",
            "Game [0100aa000000e001].nsz",
        )
        // Two distinct add-on indices; the second file of index 001 is
        // the same package twice.
        assertEquals(2, rows.single().switchFacts!!.dlcCount)
        assertEquals(3, rows.single().switchFacts!!.dlcPaths.size)
    }

    @Test
    fun `an update whose base game is not in the library stays its own row, marked loose`() {
        val rows = fold(
            "Game [0100aa000000e000].nsp",
            "Alone [0100bb000000e800][v2].nsp",
        )
        assertEquals(2, rows.size)
        val loose = rows.first { it.id.endsWith("Alone [0100bb000000e800][v2].nsp") }.switchFacts!!
        assertTrue(loose.loose)
        assertTrue(loose.hasUpdate)
        assertEquals("2", loose.updateVersion)
        assertEquals("Update v2 without base game", loose.line())
    }

    @Test
    fun `a dlc whose base game is not in the library stays its own row, marked loose`() {
        val rows = fold("Alone [0100bb000000e001].nsp")
        val facts = rows.single().switchFacts!!
        assertTrue(facts.loose)
        assertEquals(1, facts.dlcCount)
        assertEquals("DLC without base game", facts.line())
    }

    @Test
    fun `a dlc with no title id at all can never match a base, and stays loose`() {
        val rows = fold(
            "Expansion Pass [DLC].xci",
            "Some Game [0100aa000000e000].xci",
        )
        assertEquals(2, rows.size)
        val loose = rows.first { it.id.endsWith("[DLC].xci") }.switchFacts!!
        assertTrue(loose.loose)
        assertNull(loose.baseTitleId)
    }

    @Test
    fun `two base files of one game are both rows, and both carry the game's facts`() {
        val rows = fold(
            "Game [0100aa000000e000].xci",
            "Game [0100aa000000e000].nsp",
            "Game [0100aa000000e800][v1].nsp",
        )
        // Two files of the same game are two rows the person owns; the
        // update is a fact of the TITLE, so both rows carry it.
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.switchFacts!!.hasUpdate && it.switchFacts!!.baseTitleId == "0100aa000000e000" })
    }

    @Test
    fun `files classification says nothing about stay untouched rows`() {
        val rows = fold(
            "Mystery.nsp",
            "Homebrew.nro",
            "Game [0100aa000000e000].xci",
        )
        assertEquals(3, rows.size)
        assertNull(rows.first { it.id.endsWith("Mystery.nsp") }.switchFacts)
        assertNull(rows.first { it.id.endsWith("Homebrew.nro") }.switchFacts)
    }

    @Test
    fun `non-switch entries pass through untouched`() {
        val gba = LibraryEntry(
            id = "/sdcard/Roms/gba/Pokemon.z64",
            title = "Pokemon",
            kind = LibraryEntryKind.CONSOLE_ROM,
            systemId = "n64",
        )
        val nsp = LibraryEntry(
            id = "/sdcard/Roms/switch/Stray.nsp",
            title = "Stray",
            kind = LibraryEntryKind.CONSOLE_ROM,
            systemId = "switch",
        )
        val out = SwitchGameGrouping.fold(listOf(gba, nsp))
        assertEquals(listOf(gba, nsp), out)
        assertNull(out.first().switchFacts)
    }

    @Test
    fun `the input's order is kept`() {
        val rows = fold(
            "B [0100bb000000e000].xci",
            "A [0100aa000000e000].xci",
            "A [0100aa000000e800][v1].nsp",
        )
        assertEquals(listOf("B [0100bb000000e000].xci", "A [0100aa000000e000].xci"), rows.map { it.id.substringAfterLast('/') })
    }
}
