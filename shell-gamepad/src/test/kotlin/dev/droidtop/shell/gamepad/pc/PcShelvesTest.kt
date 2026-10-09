package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.InstalledAppFacts
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.PcInfo
import dev.droidtop.shell.gamepad.query.LibraryQuery
import dev.droidtop.shell.gamepad.query.LibrarySortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `retro games and game apps merge into continue playing and recently added by their times`() {
        val pc = game("/pc", lastPlayed = now - 30, firstSeen = now - 300)
        val rom = game("/rom", kind = LibraryEntryKind.CONSOLE_ROM, lastPlayed = now - 10, firstSeen = now - 200)
            .copy(systemId = "snes")
        val app = game("com.example.game", kind = LibraryEntryKind.NATIVE_ANDROID_APP, lastPlayed = now - 20)
            .copy(appFacts = InstalledAppFacts(firstInstalledEpochMs = now - 5))

        val shelves = pcShelves(listOf(pc), now, others = listOf(rom, app))

        assertEquals(listOf("/rom", "com.example.game", "/pc"), shelves.first { it.id == SHELF_CONTINUE }.entries.map { it.id })
        // The app has no first-seen stamp, so its install time places it.
        assertEquals(listOf("com.example.game", "/rom", "/pc"), shelves.first { it.id == SHELF_RECENTLY_ADDED }.entries.map { it.id })
        // Other shelves are the PC fold's own.
        assertTrue(shelves.filter { it.id != SHELF_CONTINUE && it.id != SHELF_RECENTLY_ADDED }.flatMap { it.entries }.all { it.id == "/pc" })
    }

    @Test
    fun `a kind badge names pc, engine, app or retro with its store or system`() {
        val names = mapOf("snes" to "Super Nintendo")
        val rom = game("/rom", kind = LibraryEntryKind.CONSOLE_ROM).copy(systemId = "snes")
        val app = game("com.example.game", kind = LibraryEntryKind.NATIVE_ANDROID_APP).copy(appFacts = InstalledAppFacts())
        val engine = game("/vn", kind = LibraryEntryKind.RENPY)

        assertEquals(KindBadge(BadgeKind.PC, null), kindBadgeOf(game("/pc", kind = LibraryEntryKind.WINE_PROFILE), names))
        assertEquals(KindBadge(BadgeKind.APP, null), kindBadgeOf(app, names))
        assertEquals(KindBadge(BadgeKind.ENGINE, null), kindBadgeOf(engine, names))
        assertEquals("Retro · Super Nintendo", kindBadgeOf(rom, names).text)
        assertEquals("Retro · gba", kindBadgeOf(rom.copy(systemId = "gba"), names).text)
        val steam = game("/steam", kind = LibraryEntryKind.WINE_PROFILE, pcInfo = PcInfo(storeId = "steam:1", installed = true))
        assertEquals("PC · Steam", kindBadgeOf(steam, names).text)
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
    fun `the hero card is Steam's featured card, 3_2 capsules wide at a capsule's height`() {
        assertEquals(3.2f, heroWidth(androidx.compose.ui.unit.Dp(1f)).value, 0.001f)
        assertEquals(CAPSULE_ASPECT * 3.2f, HERO_ASPECT, 0.001f)
    }

    @Test
    fun `capsules take Steam's width for the window's tier`() {
        assertEquals(110f, capsuleWidthFor(411f).value, 0.001f)
        assertEquals(110f, capsuleWidthFor(768f).value, 0.001f)
        assertEquals(110f, capsuleWidthFor(853f).value, 0.001f)
        assertEquals(134f, capsuleWidthFor(854f).value, 0.001f)
        assertEquals(134f, capsuleWidthFor(1279f).value, 0.001f)
        assertEquals(172f, capsuleWidthFor(1280f).value, 0.001f)
    }

    @Test
    fun `only art noticeably wider than tall is shown whole in a portrait capsule`() {
        assertTrue(isWideArt(640f, 480f))
        assertFalse(isWideArt(600f, 900f))
        assertFalse(isWideArt(105f, 100f))
        assertFalse(isWideArt(0f, 0f))
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

        val store = PcInfo(installed = false)
        val mixed = pcShelves(listOf(game("/a"), game("steam:1", kind = LibraryEntryKind.WINE_PROFILE, pcInfo = store)), now)
        assertEquals(listOf("/a"), mixed.first { it.id == SHELF_INSTALLED }.entries.map { it.id })

        val gone = pcShelves(listOf(game("/a"), game("/b", missing = true)), now)
        assertEquals(listOf("/a"), gone.first { it.id == SHELF_INSTALLED }.entries.map { it.id })
    }

    @Test
    fun `more than one source shelves per source, one source per engine family`() {
        val shelves = pcShelves(
            listOf(
                game("steam:1", kind = LibraryEntryKind.WINE_PROFILE, pcInfo = PcInfo(installed = true)),
                game("gog:1", kind = LibraryEntryKind.WINE_PROFILE, pcInfo = PcInfo(installed = true)),
                game("gog:2", kind = LibraryEntryKind.WINE_PROFILE, pcInfo = PcInfo(installed = true)),
                game("/vn1"),
                game("/vn2"),
                game("/rm", kind = LibraryEntryKind.RPG_MAKER_MV),
            ),
            now,
        )

        // Stores in the registry's order (which other tests may fill), then the folders.
        assertEquals(setOf("source:gog", "source:steam", "source:folder:"), shelves.map { it.id }.toSet())
        assertEquals("source:folder:", shelves.last().id)
        assertEquals("Folder", shelves.last().title)

        val foldersOnly = pcShelves(listOf(game("/vn1"), game("/vn2"), game("/rm", kind = LibraryEntryKind.RPG_MAKER_MV)), now)
        assertEquals(listOf("kind:Visual Novels", "kind:RPG Maker"), foldersOnly.map { it.id })
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
    fun `home keeps the recent shelves across libraries and leaves the rest to PC Games`() {
        val pc = game("/pc", lastPlayed = now - 30, firstSeen = now - 300, favorite = true, update = "2.0")
        val rom = game("/rom", kind = LibraryEntryKind.CONSOLE_ROM, lastPlayed = now - 10).copy(systemId = "snes")

        val home = homeShelves(listOf(pc, game("/other")), listOf(rom), now)
        assertEquals(listOf(SHELF_CONTINUE, SHELF_RECENTLY_ADDED, SHELF_UPDATES), home.map { it.id })
        assertEquals(listOf("/rom", "/pc"), home.first().entries.map { it.id })

        // PC Games' Overview is the PC fold's own, Retro games never on it.
        val overview = pcShelves(listOf(pc, game("/other")), now)
        assertTrue(overview.any { it.id == SHELF_FAVOURITES })
        assertTrue(overview.flatMap { it.entries }.none { it.id == "/rom" })
    }

    @Test
    fun `not played yet appears once something was played, newest added first`() {
        assertNull(pcShelves(listOf(game("/a"), game("/b")), now).firstOrNull { it.id == SHELF_NOT_PLAYED })

        val shelf = pcShelves(
            listOf(
                game("/played", lastPlayed = now - 5),
                game("/old", firstSeen = now - 50),
                game("/new", firstSeen = now - 5),
                game("/gone", missing = true),
            ),
            now,
        ).first { it.id == SHELF_NOT_PLAYED }
        assertEquals("Not played yet", shelf.title)
        assertEquals(listOf("/new", "/old"), shelf.entries.map { it.id })
    }

    @Test
    fun `the hero card is the first card of the first shelf, whichever shelf leads`() {
        assertTrue(isHeroCard(0, 0))
        assertEquals(false, isHeroCard(0, 1))
        assertEquals(false, isHeroCard(1, 0))
    }

    @Test
    fun `the cursor stays on its game when the shelves move under it`() {
        fun shelf(id: String, vararg games: String) = PcShelf(id, id, games.map { game(it) }, games.size)
        val before = listOf(shelf("kind:A", "/a1", "/a2"), shelf("kind:B", "/b1", "/b2", "/b3"))
        // A scan grew shelf A past B, and added a game ahead of /b3.
        val after = listOf(shelf("kind:B", "/b0", "/b1", "/b2", "/b3"), shelf("kind:A", "/a1", "/a2"))

        assertEquals(0 to 3, cursorAfter(before, after, shelf = 1, item = 2))
        assertEquals(1 to 1, cursorAfter(before, after, shelf = 0, item = 1))
        // The shelf went away: the same places, clamped.
        assertEquals(0 to 1, cursorAfter(before, listOf(shelf("kind:C", "/c1", "/c2")), shelf = 1, item = 2))
        assertEquals(0 to 0, cursorAfter(emptyList(), after, shelf = 0, item = 0))
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

    @Test
    fun `a plugin's shelf holds only entries Home has, in its order, and names the plugin`() {
        val entries = listOf(game("/a"), game("/b"), game("/c"))
        val shelves = pluginHomeShelves(
            listOf(
                dev.droidtop.library.integrations.PluginShelves.Shelf(
                    "acme.picks",
                    "Acme Picks",
                    dev.droidtop.pluginhost.PluginShelf("short", "Short games", listOf("/c", "/gone", "/a")),
                ),
                dev.droidtop.library.integrations.PluginShelves.Shelf(
                    "acme.picks",
                    "Acme Picks",
                    dev.droidtop.pluginhost.PluginShelf("empty", "All gone", listOf("/gone")),
                ),
            ),
            entries,
        )
        assertEquals(1, shelves.size)
        assertEquals("plugin:acme.picks/short", shelves.single().id)
        assertEquals("Short games, from Acme Picks", shelves.single().title)
        assertEquals(listOf("/c", "/a"), shelves.single().entries.map { it.id })
        assertTrue("no built-in shelf id starts like a plugin's", HOME_SHELF_IDS.none { it.startsWith("plugin:") })
    }
}
