package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.PcInfo
import dev.droidtop.shell.gamepad.query.LibraryQuery
import dev.droidtop.shell.gamepad.query.LibrarySortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PC Games home's shelves and the tab's own lines (docs/SPEC.md 7i),
 * held to their rules on the JVM: what shows, in what order, and when a
 * shelf that would repeat the whole library is left out.
 */
class PcShelvesTest {
    private val now = 1_700_000_000_000L

    private fun game(
        id: String,
        kind: LibraryEntryKind = LibraryEntryKind.RENPY,
        lastPlayed: Long? = null,
        firstSeen: Long = 0L,
        favorite: Boolean = false,
        update: String? = null,
        pcInfo: PcInfo? = null,
        missing: Boolean = false,
    ) = LibraryEntry(
        id = id,
        title = id,
        kind = kind,
        firstSeenEpochMs = firstSeen,
        lastPlayedEpochMs = lastPlayed,
        favorite = favorite,
        availableUpdate = update,
        pcInfo = pcInfo,
        missing = missing,
    )

    @Test
    fun `continue playing leads, newest first, and only games that were played`() {
        val shelves = pcShelves(
            listOf(game("/a", lastPlayed = now - 10), game("/b"), game("/c", lastPlayed = now - 5)),
            now,
        )

        assertEquals(SHELF_CONTINUE, shelves.first().id)
        assertEquals(listOf("/c", "/a"), shelves.first().entries.map { it.id })
    }

    @Test
    fun `continue playing stays ahead of recently added, which keeps its own shelf`() {
        val shelves = pcShelves(
            listOf(game("/a", lastPlayed = now - 10, firstSeen = now - 100), game("/b", firstSeen = now - 5)),
            now,
        )

        assertEquals(listOf(SHELF_CONTINUE, SHELF_RECENTLY_ADDED), shelves.map { it.id }.take(2))
        assertEquals(listOf("/b", "/a"), shelves[1].entries.map { it.id })
    }

    @Test
    fun `the backdrop prefers the landscape hero and preloads the neighbours either side`() {
        val list = (0..6).map { game("/g$it").copy(artworkUri = "box$it", heroUri = if (it == 3) "hero3" else null) }

        assertEquals("hero3", list[3].backdropArt())
        assertEquals("box2", list[2].backdropArt())
        assertNull(game("/none").backdropArt())
        // Two either side of the cursor, never the cursor's own, and none off the ends.
        assertEquals(listOf("box0", "box1", "hero3", "box4"), neighbourBackdrops(list, 2, reach = 2))
        assertEquals(listOf("box1", "box2"), neighbourBackdrops(list.take(3), 0, reach = 2))
        assertEquals(emptyList<String>(), neighbourBackdrops(emptyList(), 0))
    }

    @Test
    fun `the hero card is as wide as landscape art is at a capsule's height`() {
        assertEquals(HERO_ASPECT / CAPSULE_ASPECT, heroWidth(androidx.compose.ui.unit.Dp(1f)).value, 0.001f)
    }

    @Test
    fun `recently added is newest first and hidden when timestamps are missing`() {
        val none = pcShelves(listOf(game("/legacy")), now)
        assertNull(none.firstOrNull { it.id == SHELF_RECENTLY_ADDED })

        val shelf = pcShelves(
            listOf(game("/old", firstSeen = now - 20), game("/legacy"), game("/new", firstSeen = now - 2)),
            now,
        ).first { it.id == SHELF_RECENTLY_ADDED }
        assertEquals("Recently added", shelf.title)
        assertEquals(listOf("/new", "/old"), shelf.entries.map { it.id })
    }

    @Test
    fun `recently added is capped like other shelves`() {
        val shelf = pcShelves((1..SHELF_LIMIT + 2).map { game("/g$it", firstSeen = now + it) }, now)
            .first { it.id == SHELF_RECENTLY_ADDED }
        assertEquals(SHELF_LIMIT, shelf.entries.size)
        assertEquals(SHELF_LIMIT + 2, shelf.total)
        assertEquals("/g${SHELF_LIMIT + 2}", shelf.entries.first().id)
    }

    @Test
    fun `updates and favourites shelve only when there is one`() {
        val none = pcShelves(listOf(game("/a")), now)
        assertNull(none.firstOrNull { it.id == SHELF_UPDATES })
        assertNull(none.firstOrNull { it.id == SHELF_FAVOURITES })

        val some = pcShelves(listOf(game("/a", update = "0.9"), game("/b", favorite = true)), now)
        assertEquals(listOf("/a"), some.first { it.id == SHELF_UPDATES }.entries.map { it.id })
        assertEquals(listOf("/b"), some.first { it.id == SHELF_FAVOURITES }.entries.map { it.id })
    }

    @Test
    fun `an installed shelf appears only when something is not installed`() {
        val allFolders = pcShelves(listOf(game("/a"), game("/b")), now)
        assertNull(allFolders.firstOrNull { it.id == SHELF_INSTALLED })

        val store = PcInfo(source = "Steam", installed = false)
        val mixed = pcShelves(listOf(game("/a"), game("steam:1", kind = LibraryEntryKind.WINE_PROFILE, pcInfo = store)), now)
        assertEquals(listOf("/a"), mixed.first { it.id == SHELF_INSTALLED }.entries.map { it.id })

        val gone = pcShelves(listOf(game("/a"), game("/b", missing = true)), now)
        assertEquals(listOf("/a"), gone.first { it.id == SHELF_INSTALLED }.entries.map { it.id })
    }

    @Test
    fun `store rows shelve per store, folder games per engine family, largest first`() {
        val shelves = pcShelves(
            listOf(
                game("steam:1", kind = LibraryEntryKind.WINE_PROFILE, pcInfo = PcInfo(source = "Steam", installed = true)),
                game("gog:1", kind = LibraryEntryKind.WINE_PROFILE, pcInfo = PcInfo(source = "GOG", installed = true)),
                game("gog:2", kind = LibraryEntryKind.WINE_PROFILE, pcInfo = PcInfo(source = "GOG", installed = true)),
                game("/vn1"),
                game("/vn2"),
                game("/rm", kind = LibraryEntryKind.RPG_MAKER_MV),
            ),
            now,
        )

        assertEquals(listOf("store:GOG", "store:Steam", "kind:Visual Novels", "kind:RPG Maker"), shelves.map { it.id })
        assertEquals("GOG", shelves[0].title)
        assertEquals("Visual Novels", shelves[2].title)
    }

    @Test
    fun `a long shelf shows its first capsules and says how many there are`() {
        val many = (1..SHELF_LIMIT + 6).map { game("/g$it") }
        val shelf = pcShelves(many, now).first { it.id == "kind:Visual Novels" }

        assertEquals(SHELF_LIMIT, shelf.entries.size)
        assertEquals(SHELF_LIMIT + 6, shelf.total)
        assertEquals("Visual Novels · ${SHELF_LIMIT + 6}", shelf.heading)
        assertTrue(pcShelves(listOf(game("/g")), now).all { it.heading == it.title })
    }

    @Test
    fun `the grid's summary names the count, the sort and the search`() {
        assertEquals("171 games · Sort: Name", gridSummary(LibraryQuery(), 171))
        assertEquals("1 game · Sort: Last played · \"zelda\"", gridSummary(LibraryQuery(text = "zelda", sort = LibrarySortKey.RECENT), 1))
    }

    @Test
    fun `play time reads as a person says it`() {
        assertEquals("Never played", playtimeLine(0, 0))
        assertEquals("Under a minute, played once", playtimeLine(30, 1))
        assertEquals("45 min, played 3 times", playtimeLine(45 * 60, 3))
        assertEquals("2 h 5 min, played 7 times", playtimeLine(2 * 3600 + 5 * 60, 7))
        assertEquals("Played once", playtimeLine(0, 1))
    }

    @Test
    fun `the tab holds every game that is not a console system's`() {
        assertTrue(game("/vn").onPcGamesTab)
        assertTrue(game("steam:1", kind = LibraryEntryKind.WINE_PROFILE).onPcGamesTab)
        assertTrue(game("/roms/pc/x", kind = LibraryEntryKind.CONSOLE_ROM).copy(systemId = PC_SYSTEM_ID).onPcGamesTab)
        assertEquals(false, game("/roms/nes/x", kind = LibraryEntryKind.CONSOLE_ROM).copy(systemId = "nes").onPcGamesTab)
    }
}
