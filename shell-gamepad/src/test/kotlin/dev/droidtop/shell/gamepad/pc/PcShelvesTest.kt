package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.InstalledAppFacts
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.PcInfo
import dev.droidtop.shell.gamepad.query.LibraryQuery
import dev.droidtop.shell.gamepad.query.LibrarySortKey
import dev.droidtop.shell.gamepad.query.addedEpochMs
import dev.droidtop.shell.gamepad.query.comparator
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
    fun `added is the install time when known, else first seen, and the sort, recently added and what's new agree`() {
        // Both seen by droidtop's first scan an hour ago; the app was installed ten days ago, the folder game is
        // known only by that scan, and a second game arrived yesterday.
        val day = 24L * 60 * 60 * 1000
        val app = game("com.example.app", kind = LibraryEntryKind.NATIVE_ANDROID_APP, firstSeen = now - 3_600_000)
            .copy(appFacts = InstalledAppFacts(firstInstalledEpochMs = now - 10 * day))
        val scanned = game("/scanned", firstSeen = now - 3_600_000)
        val newer = game("/newer", firstSeen = now - day)
        assertEquals(now - 10 * day, app.addedEpochMs())
        assertEquals(now - day, newer.addedEpochMs())

        val expected = listOf("/scanned", "/newer", "com.example.app")
        // The Added sort (LibraryQuery), newest first.
        assertEquals(expected, listOf(app, newer, scanned).sortedWith(LibrarySortKey.ADDED.comparator()).map { it.id })
        // PC Games' Recently added, with the app merged in.
        val shelf = pcShelves(listOf(scanned, newer), now, others = listOf(app)).first { it.id == SHELF_RECENTLY_ADDED }
        assertEquals(expected, shelf.entries.map { it.id })
        // Home's What's new: one card per place and day, in the same order.
        val added = HomeModel.added(listOf(app, scanned, newer), now, placeOf = { it.id }, systemOf = { null })
        val whatsNew = HomeModel.whatsNew(emptyList(), added, pluginUpdates = 0, attention = 0)
        assertEquals(expected, whatsNew.map { (it as HomeCard.Added).place })
    }

    @Test
    fun `a kind badge names pc, engine, app or retro, and a pc game's source only when there are several`() {
        val names = mapOf("snes" to "Super Nintendo")
        val rom = game("/rom", kind = LibraryEntryKind.CONSOLE_ROM).copy(systemId = "snes")
        val app = game("com.example.game", kind = LibraryEntryKind.NATIVE_ANDROID_APP).copy(appFacts = InstalledAppFacts())
        val engine = game("/vn", kind = LibraryEntryKind.RENPY)

        assertEquals(KindBadge(BadgeKind.PC, null), kindBadgeOf(game("/pc", kind = LibraryEntryKind.WINE_PROFILE), names))
        assertEquals(KindBadge(BadgeKind.APP, null), kindBadgeOf(app, names))
        // An engine game is "Engine": its engine is an implementation category, not a badge (#496).
        assertEquals(KindBadge(BadgeKind.ENGINE, null), kindBadgeOf(engine, names))
        assertEquals("Retro · Super Nintendo", kindBadgeOf(rom, names)?.text)
        assertEquals("Retro · gba", kindBadgeOf(rom.copy(systemId = "gba"), names)?.text)
        val steam = game("/steam", kind = LibraryEntryKind.WINE_PROFILE, pcInfo = PcInfo(storeId = "steam:1", installed = true))
        // A Steam-only library reads "PC"; with a second origin the store is named.
        assertEquals("PC", kindBadgeOf(steam, names)?.text)
        assertEquals("PC · Steam", kindBadgeOf(steam, names, origins = 2)?.text)
        assertEquals("PC", kindBadgeOf(steam, names, origins = 2, style = dev.droidtop.library.CapsuleBadgeStyle.KIND_ONLY)?.text)
        assertEquals(null, kindBadgeOf(steam, names, origins = 2, style = dev.droidtop.library.CapsuleBadgeStyle.OFF))
        // A Retro badge is not the PC option's to drop.
        assertEquals("Retro · Super Nintendo", kindBadgeOf(rom, names, style = dev.droidtop.library.CapsuleBadgeStyle.OFF)?.text)
    }

    @Test
    fun `a badge part every card of a view shares is dropped, and a badge left with nothing is not drawn`() {
        val pc = KindBadge(BadgeKind.PC, null)
        val pcSteam = KindBadge(BadgeKind.PC, "Steam")
        val pcGog = KindBadge(BadgeKind.PC, "GOG")
        val snes = KindBadge(BadgeKind.RETRO, "Super Nintendo")
        val gba = KindBadge(BadgeKind.RETRO, "Game Boy Advance")
        // All PC from one place: nothing to tell apart.
        assertEquals(null, ViewBadges(listOf(pc, pc, pc)).of(pc))
        // All PC from two stores: the store, without the word PC.
        val stores = ViewBadges(listOf(pcSteam, pcGog))
        assertEquals("Steam", stores.of(pcSteam)?.text)
        assertEquals(listOf<Pair<String?, String?>>(null to "GOG"), stores.of(pcGog)?.forms)
        // Retro games of two systems: the system only.
        assertEquals("Super Nintendo", ViewBadges(listOf(snes, gba)).of(snes)?.text)
        // A mixed page keeps the kind and the detail.
        val mixed = ViewBadges(listOf(pcSteam, snes, KindBadge(BadgeKind.APP, null)))
        assertEquals("PC · Steam", mixed.of(pcSteam)?.text)
        assertEquals("Retro · Super Nintendo", mixed.of(snes)?.text)
        // A view of one card keeps its badge; no badge stays none.
        assertEquals("Retro · Super Nintendo", ViewBadges(listOf(snes)).of(snes)?.text)
        assertEquals(null, mixed.of(null))
    }

    @Test
    fun `a badge that does not fit drops the kind word, then the name, never cutting a word`() {
        // The capsule draws the first form that fits its card (tracker#434: "ENGINE · RPG MAK...").
        assertEquals(
            listOf("Engine" to "RPG Maker MV", null to "RPG Maker MV", "Engine" to null),
            KindBadge(BadgeKind.ENGINE, "RPG Maker MV").forms,
        )
        assertEquals(listOf("App" to null), KindBadge(BadgeKind.APP, null).forms)
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
    fun `on the shelves the games Up and Down land on are read ahead too`() {
        val hero = listOf(game("/h0").copy(artworkUri = "box-h0", heroUri = "hero-h0"))
        val posters = (0..5).map { game("/p$it").copy(artworkUri = "box-p$it") }
        val more = (0..3).map { game("/m$it").copy(artworkUri = "box-m$it") }
        val shelves = listOf(hero, posters, more)
        // On the hero: Down lands on the posters' first game (no place kept yet).
        assertEquals(listOf("box-p0", "box-p1"), shelfNeighbourBackdrops(shelves, 0, 0, landing = { 0 }, reach = 2))
        // On the posters at 4, with the place 2 kept on the shelf below: Up to the hero, Down to box-m2 and beside it.
        val fromPosters = shelfNeighbourBackdrops(shelves, 1, 4, landing = { if (it == 2) 2 else 0 }, reach = 2)
        assertEquals(listOf("box-p2", "box-p3", "box-p5", "hero-h0", "box-m2", "box-m1", "box-m3"), fromPosters)
        // A kept place past a shorter shelf's end lands on its last game.
        assertTrue("box-m3" in shelfNeighbourBackdrops(shelves, 1, 0, landing = { 9 }, reach = 0))
    }

    @Test
    fun `in the grid the rows above and below are read ahead`() {
        val grid = (0..11).map { game("/g$it").copy(artworkUri = "box$it") }
        assertEquals(listOf("box4", "box6", "box1", "box9"), neighbourBackdrops(grid, 5, reach = 1, columns = 4))
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
    fun `Card size steps a capsule from the window's tier, in whole dp`() {
        // On the console's 1280dp canvas: 172dp at Standard (List options > Card size, Droidtop/tracker#363).
        assertEquals(129f, capsuleWidthFor(1280f, CardSize.SMALLEST).value, 0.001f)
        assertEquals(150f, capsuleWidthFor(1280f, CardSize.SMALL).value, 0.001f)
        assertEquals(172f, capsuleWidthFor(1280f, CardSize.STANDARD).value, 0.001f)
        assertEquals(206f, capsuleWidthFor(1280f, CardSize.LARGE).value, 0.001f)
        assertEquals(249f, capsuleWidthFor(1280f, CardSize.LARGEST).value, 0.001f)
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
    fun `home keeps one recent games row across libraries and leaves the rest to PC Games`() {
        val pc = game("/pc", lastPlayed = now - 30, firstSeen = now - 300, favorite = true, update = "2.0")
        val rom = game("/rom", kind = LibraryEntryKind.CONSOLE_ROM, lastPlayed = now - 10).copy(systemId = "snes")

        val home = homeShelves(listOf(pc, game("/other")), listOf(rom), now)
        assertEquals(listOf(SHELF_RECENT_GAMES), home.map { it.id })
        assertEquals("Recent games", home.single().title)
        assertEquals(listOf("/rom", "/pc"), home.first().entries.map { it.id })
        // Nothing played yet: the row is what was added most recently.
        val fresh = homeShelves(listOf(game("/new", firstSeen = now - 5)), emptyList(), now)
        assertEquals(listOf("/new"), fresh.single().entries.map { it.id })

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
    fun `the hero card is the one the cursor is on, else the one it left, else the first`() {
        assertEquals(3, heroItem(cursorOnShelf = true, cursorItem = 3, remembered = 1))
        assertEquals(1, heroItem(cursorOnShelf = false, cursorItem = 5, remembered = 1))
        assertEquals(0, heroItem(cursorOnShelf = false, cursorItem = 5, remembered = null))
        assertTrue(isHeroCard(0, 3, heroItem = 3))
        assertEquals(false, isHeroCard(0, 0, heroItem = 3))
        assertEquals(false, isHeroCard(1, 3, heroItem = 3))
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


    @Test
    fun `games on a wishlist have a shelf of their own, last, and are in no other shelf`() {
        fun wished(id: String) = game(
            id,
            kind = LibraryEntryKind.WINE_PROFILE,
            pcInfo = PcInfo(storeId = "gog:$id", installed = false, holding = dev.droidtop.library.stores.StoreHolding.WISHLIST),
        )
        val owned = game("/a", lastPlayed = now - 10, firstSeen = now - 100)
        val shelves = pcShelves(listOf(owned, wished("1"), wished("2")), now)
        assertEquals(SHELF_WISHLIST, shelves.last().id)
        assertEquals("Wishlist (not owned)", shelves.last().title)
        assertEquals(listOf("1", "2"), shelves.last().entries.map { it.id })
        // Nowhere else: not in Recently added or any source or kind shelf.
        assertTrue(shelves.dropLast(1).none { shelf -> shelf.entries.any { it.id == "1" || it.id == "2" } })
        // No wishlist, no shelf.
        assertTrue(pcShelves(listOf(owned), now).none { it.id == SHELF_WISHLIST })
        // and Home never carries it
        assertTrue(homeShelves(listOf(owned, wished("1")), emptyList(), now).none { it.id == SHELF_WISHLIST })
    }

    @Test
    fun `Home's most played row takes games played at least twice, most first, and needs two of them`() {
        // Owner, 2026-10-23: Home shows "the user's most common games" (Droidtop/tracker#611).
        val a = game("/a", lastPlayed = now - 10).copy(playCount = 5)
        val b = game("/b", lastPlayed = now - 5).copy(playCount = 2)
        val once = game("/once", lastPlayed = now - 1).copy(playCount = 1)
        assertEquals(listOf("/a", "/b"), mostPlayedShelf(listOf(b, once, a))?.entries?.map { it.id })
        assertEquals(null, mostPlayedShelf(listOf(a, once)))
        assertEquals(listOf(SHELF_RECENT_GAMES, SHELF_MOST_PLAYED), homeShelves(listOf(a, b, once), emptyList(), now).map { it.id })
    }

    @Test
    fun `Home's recommended row is the recommender's titles matched to the library, in its order`() {
        val all = listOf(game("Alpha"), game("Beta"), game("Gamma"))
        assertEquals(listOf("Gamma", "Alpha"), recommendedShelf(listOf("gamma", "Missing", "Alpha"), all)?.entries?.map { it.id })
        assertEquals(null, recommendedShelf(listOf("Missing"), all))
    }

    @Test
    fun `the hero never shows a screenshot, so a game with only a screenshot and a cover gets the cover over its blur`() {
        val media = "/storage/emulated/0/ROMs/downloaded_media/snes"
        // The scan's box art is the cover (droidtop's order puts covers first); there is no landscape art.
        val coverOnly = game("/roms/snes/Game.sfc").copy(artworkUri = "$media/covers/Game.png")
        assertEquals(HeroArt(landscape = null, boxOverBlur = "$media/covers/Game.png"), coverOnly.heroArt())
        // A screenshot is neither the landscape art nor the box art behind it: the plate carries the name.
        val screensOnly = game("/roms/snes/Other.sfc").copy(artworkUri = "$media/screenshots/Other.png", heroUri = "$media/titlescreens/Other.png")
        assertEquals(HeroArt(landscape = null, boxOverBlur = null), screensOnly.heroArt())
        // A SteamGridDB hero leads, with the box art kept for when it does not load.
        val pc = game("folder:A").copy(heroUri = "/media/pc/fanart/A.png", artworkUri = "/media/pc/covers/A.png")
        assertEquals(HeroArt("/media/pc/fanart/A.png", "/media/pc/covers/A.png"), pc.heroArt())
    }

    @Test
    fun `a game whose files are missing is offered no update and is not installed, whatever its store row says`() {
        val gone = game("steam:9", kind = LibraryEntryKind.WINE_PROFILE, update = "1.1", pcInfo = PcInfo(storeId = "steam:9", installed = true), missing = true)
        val here = game("steam:8", kind = LibraryEntryKind.WINE_PROFILE, update = "1.1", pcInfo = PcInfo(storeId = "steam:8", installed = true))
        val updates = pcShelves(listOf(gone, here)).firstOrNull { it.id == SHELF_UPDATES }
        assertEquals(listOf("steam:8"), updates?.entries?.map { it.id })
        assertEquals(false, gone.isInstalled)
        assertEquals(true, here.isInstalled)
    }
}
