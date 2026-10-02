package dev.droidtop.shell.gamepad.query

import dev.droidtop.library.AppCategoryRules
import dev.droidtop.library.InstalledAppFacts
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.PcInfo
import dev.droidtop.library.SwitchGameFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one search/filter/sort pass every library list shares (docs/SPEC.md
 * 7i): values OR within a facet, facets AND, the search text on top, and
 * the sorts that have data behind them -- never a sort on a fact the
 * model does not carry.
 */
class LibraryQueryTest {

    private fun game(
        id: String,
        title: String = id,
        favorite: Boolean = false,
        hidden: Boolean = false,
        pcInfo: PcInfo? = null,
        playtimeSeconds: Long = 0,
        lastPlayedEpochMs: Long? = null,
        releaseDate: String? = null,
        rating: Float? = null,
        genre: String? = null,
        developer: String? = null,
        artworkUri: String? = null,
        availableUpdate: String? = null,
        playCount: Int = 0,
        appFacts: InstalledAppFacts? = null,
    ) = LibraryEntry(
        id = id,
        title = title,
        kind = LibraryEntryKind.WINE_PROFILE,
        favorite = favorite,
        hidden = hidden,
        pcInfo = pcInfo,
        playtimeSeconds = playtimeSeconds,
        lastPlayedEpochMs = lastPlayedEpochMs,
        releaseDate = releaseDate,
        rating = rating,
        genre = genre,
        developer = developer,
        artworkUri = artworkUri,
        availableUpdate = availableUpdate,
        playCount = playCount,
        appFacts = appFacts,
    )

    private val scope = LibraryQueryScope(
        id = "test",
        facets = LibraryFacet.entries.toList(),
        sorts = LibrarySortKey.entries.toList(),
        context = LibraryQueryContext(now = { 1_000_000_000L }),
    )

    private val steamInstalled = game(
        "steam:1", "Half", pcInfo = PcInfo(source = "Steam", installed = true, sizeBytes = 20),
        playtimeSeconds = 300, lastPlayedEpochMs = 999_999_000, rating = 0.8f,
        releaseDate = "20041116T000000", genre = "Shooter", developer = "Valve",
        artworkUri = "file:///half.png",
    )
    private val gogNotInstalled = game(
        "gog:2", "Wiedzm", pcInfo = PcInfo(source = "GOG", installed = false, sizeBytes = 30),
        favorite = true,
    )
    private val folder = game("/games/folder/Game Three", pcInfo = null, hidden = true)

    @Test
    fun `values within one facet are or`() {
        val query = LibraryQuery(facets = mapOf(LibraryFacet.STORE.key to setOf("Steam", "GOG")))

        val shown = query.applyTo(listOf(steamInstalled, gogNotInstalled, folder), scope)

        assertEquals(listOf("Half", "Wiedzm"), shown.map { it.title })
    }

    @Test
    fun `facets are and`() {
        val query = LibraryQuery(facets = mapOf(LibraryFacet.STORE.key to setOf("Steam"), LibraryFacet.INSTALLED.key to setOf("Installed")))

        val shown = query.applyTo(listOf(steamInstalled, gogNotInstalled, folder), scope)

        assertEquals(listOf("Half"), shown.map { it.title })
    }

    @Test
    fun `an entry the facet does not apply to is excluded while it filters`() {
        // A folder game has no install state, so "Installed" cannot match
        // it -- a filter that cannot match is not a filter a game
        // silently passes.
        val query = LibraryQuery(facets = mapOf(LibraryFacet.INSTALLED.key to setOf("Not installed")))

        val shown = query.applyTo(listOf(steamInstalled, gogNotInstalled, folder), scope)

        assertEquals(listOf("Wiedzm"), shown.map { it.title })
    }

    @Test
    fun `a facet with nothing selected stops filtering`() {
        val selected = LibraryQuery(facets = mapOf(LibraryFacet.STORE.key to setOf("Steam")))
            .withToggled(LibraryFacet.STORE, "Steam", on = false)

        assertTrue(selected.facets.isEmpty())
        // The folder game is hidden, and a hidden game is out of the list unless the Hidden facet asks.
        assertEquals(2, selected.applyTo(listOf(steamInstalled, gogNotInstalled, folder), scope).size)
    }

    @Test
    fun `the search text matches the title, genre and developer, case-insensitively`() {
        val query = LibraryQuery(text = "valve")

        val byDeveloper = query.applyTo(listOf(steamInstalled), scope)
        val byGenre = LibraryQuery(text = "shooter").applyTo(listOf(steamInstalled), scope)
        val byTitle = LibraryQuery(text = "HALF").applyTo(listOf(steamInstalled), scope)

        assertEquals(listOf("Half"), byDeveloper.map { it.title })
        assertEquals(listOf("Half"), byGenre.map { it.title })
        assertEquals(listOf("Half"), byTitle.map { it.title })
        assertTrue(LibraryQuery(text = "nothing called this").matches(steamInstalled, scope).not())
    }

    @Test
    fun `recently played means the last fourteen days`() {
        val fresh = game("a", lastPlayedEpochMs = 1_000_000_000 - 1)
        val stale = game("b", lastPlayedEpochMs = 1_000_000_000 - RECENT_WINDOW_MS - 1)
        val never = game("c")
        val query = LibraryQuery(facets = mapOf(LibraryFacet.RECENTLY_PLAYED.key to setOf(RECENT_YES)))

        val shown = query.applyTo(listOf(fresh, stale, never), scope)

        assertEquals(listOf("a"), shown.map { it.title })
    }

    @Test
    fun `the year sort is oldest first with unknown after every dated game`() {
        val dated = listOf(game("newer", releaseDate = "20100101T000000"), game("older", releaseDate = "19990101T000000"))
        val undated = game("undated")

        val shown = LibraryQuery(sort = LibrarySortKey.YEAR).applyTo(dated + undated, scope)

        assertEquals(listOf("older", "newer", "undated"), shown.map { it.title })
    }

    @Test
    fun `playtime, rating and size sorts read only real facts`() {
        val big = game("big", pcInfo = PcInfo(source = "Steam", installed = true, sizeBytes = 30), playtimeSeconds = 10, rating = 0.5f)
        val small = game("small", pcInfo = PcInfo(source = "Steam", installed = true, sizeBytes = 10), playtimeSeconds = 90, rating = 0.9f)

        assertEquals(
            listOf("small", "big"),
            LibraryQuery(sort = LibrarySortKey.PLAYTIME).applyTo(listOf(big, small), scope).map { it.title },
        )
        assertEquals(
            listOf("small", "big"),
            LibraryQuery(sort = LibrarySortKey.RATING).applyTo(listOf(big, small), scope).map { it.title },
        )
        assertEquals(
            listOf("big", "small"),
            LibraryQuery(sort = LibrarySortKey.SIZE).applyTo(listOf(big, small), scope).map { it.title },
        )
    }

    @Test
    fun `ready and runner facets read what the scope knows, and say nothing when it does not`() {
        val ready = game("ready")
        val setup = game("setup")
        val knows = scope.copy(
            context = LibraryQueryContext(
                runnerReadyOf = { if (it.title == "ready") true else if (it.title == "setup") false else null },
                runnerLabelOf = { if (it.title == "ready") "enginehost (Ren'Py)" else null },
                now = { 1_000_000_000L },
            ),
        )

        val readyQuery = LibraryQuery(facets = mapOf(LibraryFacet.READY.key to setOf(READY_YES)))
        val setupQuery = LibraryQuery(facets = mapOf(LibraryFacet.READY.key to setOf(READY_NO)))
        val runnerQuery = LibraryQuery(facets = mapOf(LibraryFacet.RUNNER.key to setOf("enginehost (Ren'Py)")))

        assertEquals(listOf("ready"), readyQuery.applyTo(listOf(ready, setup), knows).map { it.title })
        assertEquals(listOf("setup"), setupQuery.applyTo(listOf(ready, setup), knows).map { it.title })
        assertEquals(listOf("ready"), runnerQuery.applyTo(listOf(ready, setup), knows).map { it.title })
    }

    @Test
    fun `update, artwork, hidden and favourites facets read the entry's own flags`() {
        // Every entry but `artless` carries real art -- the MISSING_ART
        // check needs at least one entry WITH artworkUri to actually be a
        // test: `game()` defaults artworkUri to null, so leaving these
        // three at the default made every entry in `base` "missing art"
        // and the assertion below passed only by accident of list order
        // (real bug this fixes, caught live: CI's own testDebugUnitTest,
        // 2026-09-28).
        val updating = game("updating", availableUpdate = "1.1", artworkUri = "art://updating")
        val artless = game("artless", artworkUri = null)
        val hidden = game("hidden", hidden = true, artworkUri = "art://hidden")
        val favourite = game("favourite", favorite = true, artworkUri = "art://favourite")

        val base = listOf(updating, artless, hidden, favourite)
        assertEquals(
            listOf("updating"),
            LibraryQuery(facets = mapOf(LibraryFacet.UPDATE.key to setOf(UPDATE_YES))).applyTo(base, scope).map { it.title },
        )
        assertEquals(
            listOf("artless"),
            LibraryQuery(facets = mapOf(LibraryFacet.MISSING_ART.key to setOf(MISSING_ART_YES))).applyTo(base, scope).map { it.title },
        )
        assertEquals(
            listOf("hidden"),
            LibraryQuery(facets = mapOf(LibraryFacet.HIDDEN.key to setOf(HIDDEN_YES))).applyTo(base, scope).map { it.title },
        )
        assertEquals(
            listOf("favourite"),
            LibraryQuery(facets = mapOf(LibraryFacet.FAVOURITES.key to setOf(FAVOURITES_YES))).applyTo(base, scope).map { it.title },
        )
    }

    @Test
    fun `a query and its saved views survive the store's round trip`() {
        val query = LibraryQuery(
            text = "witcher",
            facets = mapOf(LibraryFacet.STORE.key to setOf("GOG", "Steam"), LibraryFacet.INSTALLED.key to setOf(INSTALLED_YES)),
            sort = LibrarySortKey.PLAYTIME,
        )

        assertEquals(query, LibraryViewPrefs.decodeQuery(LibraryViewPrefs.encodeQuery(query)))
    }

    @Test
    fun `a stored query this build cannot read is the default view, never an error`() {
        assertNull(LibraryViewPrefs.decodeQuery(null))
        assertEquals(LibraryQuery(), LibraryViewPrefs.decodeQuery("not json at all"))
        assertEquals(LibraryQuery(), LibraryViewPrefs.decodeQuery("""{"sort":"NO_LONGER_A_SORT"}"""))
    }

    @Test
    fun `saved views keep their order and a re-saved name replaces its view`() {
        val first = NamedLibraryView("Mine", LibraryQuery(sort = LibrarySortKey.RATING))
        val second = NamedLibraryView("Yours", LibraryQuery(text = "x"))
        val raw = LibraryViewPrefs.encodeViews(listOf(first, second))

        assertEquals(listOf(first, second), LibraryViewPrefs.decodeViews(raw))

        val replaced = first.copy(query = LibraryQuery(sort = LibrarySortKey.SIZE))
        val reEncoded = LibraryViewPrefs.encodeViews(listOf(second, replaced))

        assertEquals(listOf(second, replaced), LibraryViewPrefs.decodeViews(reEncoded))
    }

    @Test
    fun `the year facet reads only a real four-digit year`() {
        assertEquals("2004", LibraryFacet.YEAR.valuesOf(steamInstalled, scope.context).single())
        assertTrue(LibraryFacet.YEAR.valuesOf(gogNotInstalled, scope.context).isEmpty())
        assertFalse(LibraryFacet.YEAR.valuesOf(game("x", releaseDate = "unknown"), scope.context).isNotEmpty())
    }

    @Test
    fun `picking the active sort again flips it and a different sort starts natural`() {
        val byName = LibraryQuery()
        val flipped = byName.withSort(LibrarySortKey.NAME)
        assertTrue(flipped.reversed)
        assertFalse(flipped.withSort(LibrarySortKey.NAME).reversed)

        val other = flipped.withSort(LibrarySortKey.RATING)
        assertEquals(LibrarySortKey.RATING, other.sort)
        assertFalse(other.reversed)
        assertEquals("Z to A", LibrarySortKey.NAME.orderLabel(reversed = true))
        assertEquals("Highest first", LibrarySortKey.RATING.orderLabel(reversed = false))
    }

    @Test
    fun `a flipped name sort runs Z to A and a flipped sort keeps entries with no fact last`() {
        val a = game("a", lastPlayedEpochMs = 100)
        val b = game("b", lastPlayedEpochMs = 200)
        val never = game("never")

        assertEquals(listOf("b", "a", "never"), LibraryQuery(sort = LibrarySortKey.RECENT).applyTo(listOf(never, a, b), scope).map { it.title })
        assertEquals(
            listOf("a", "b", "never"),
            LibraryQuery(sort = LibrarySortKey.RECENT, reversed = true).applyTo(listOf(never, a, b), scope).map { it.title },
        )
        assertEquals(listOf("b", "a"), LibraryQuery(reversed = true).applyTo(listOf(a, b), scope).map { it.title })
    }

    @Test
    fun `hidden entries are out of the list unless the Hidden facet asks for them`() {
        val shown = game("shown")
        val hidden = game("hidden", hidden = true)

        assertEquals(listOf("shown"), LibraryQuery().applyTo(listOf(shown, hidden), scope).map { it.title })
        assertEquals(
            listOf("hidden"),
            LibraryQuery(facets = mapOf(LibraryFacet.HIDDEN.key to setOf(HIDDEN_YES))).applyTo(listOf(shown, hidden), scope).map { it.title },
        )
        // A scope that does not offer the facet keeps every entry, as a console list always did.
        val noHiddenFacet = scope.copy(facets = listOf(LibraryFacet.STORE))
        assertEquals(2, LibraryQuery().applyTo(listOf(shown, hidden), noHiddenFacet).size)
        assertEquals(1, LibraryQuery().totalIn(listOf(shown, hidden), scope))
    }

    @Test
    fun `facets are offered with counts, and one that would narrow nothing is not`() {
        val a = game("a", pcInfo = PcInfo(source = "Steam", installed = true), favorite = true)
        val b = game("b", pcInfo = PcInfo(source = "Steam", installed = true))
        val c = game("c", pcInfo = PcInfo(source = "GOG", installed = true))

        val offers = LibraryQuery().facetOffers(listOf(a, b, c), scope)

        val store = offers.first { it.facet == LibraryFacet.STORE }
        assertEquals(listOf(FacetValueCount("GOG", 1), FacetValueCount("Steam", 2)), store.values)
        // Every game is installed: that facet narrows nothing, so it is not offered.
        assertTrue(offers.none { it.facet == LibraryFacet.INSTALLED })
        assertEquals(listOf(FacetValueCount(FAVOURITES_YES, 1)), offers.first { it.facet == LibraryFacet.FAVOURITES }.values)
    }

    @Test
    fun `a selected value nothing has right now stays listed at zero so it can be taken off`() {
        val query = LibraryQuery(facets = mapOf(LibraryFacet.RUNNING.key to setOf(RUNNING_YES)))

        val offer = query.facetOffers(listOf(game("a")), scope).first { it.facet == LibraryFacet.RUNNING }

        assertEquals(listOf(FacetValueCount(RUNNING_YES, 0)), offer.values)
    }

    @Test
    fun `active chips follow the scope's facet order, a cleared view keeps the sort, a chip comes off alone`() {
        val query = LibraryQuery(
            text = " quest ",
            facets = mapOf(LibraryFacet.INSTALLED.key to setOf(INSTALLED_YES), LibraryFacet.STORE.key to setOf("Steam", "GOG")),
            sort = LibrarySortKey.RATING,
            reversed = true,
        )

        val chips = query.activeChips(scope)

        assertEquals(listOf("\"quest\"", "GOG", "Steam", INSTALLED_YES), chips.map { it.label })
        assertEquals(setOf("Steam"), query.without(chips[1]).selected(LibraryFacet.STORE))
        assertEquals("", query.without(chips[0]).text)
        val cleared = query.cleared
        assertTrue(cleared.isEmpty)
        assertEquals(LibrarySortKey.RATING, cleared.sort)
        assertTrue(cleared.reversed)
        assertTrue(LibraryQuery().activeChips(scope).isEmpty())
    }

    @Test
    fun `the state line names the count, the sort and its direction`() {
        assertEquals("171 games \u00b7 Sort: Name, A to Z", querySummaryLine(171, 171, LibraryQuery(), scope))
        assertEquals(
            "1 of 80 games \u00b7 Sort: Last played, Earliest first",
            querySummaryLine(1, 80, LibraryQuery(text = "quest", sort = LibrarySortKey.RECENT, reversed = true), scope),
        )
        assertEquals("1 game", queryCountLine(1, 1, filtering = false, scope = scope))
    }

    @Test
    fun `the sort direction survives the store's round trip`() {
        val query = LibraryQuery(sort = LibrarySortKey.ADDED, reversed = true)

        assertEquals(query, LibraryViewPrefs.decodeQuery(LibraryViewPrefs.encodeQuery(query)))
        assertFalse(LibraryViewPrefs.decodeQuery("""{"sort":"NAME"}""")!!.reversed)
    }

    private val now = 100_000_000_000L
    private fun app(
        id: String,
        installedAgoMs: Long = 100L * 24 * 60 * 60 * 1000,
        flaggedGame: Boolean = false,
        system: Boolean = false,
        installer: String? = "com.android.vending",
        lastPlayed: Long? = null,
        favorite: Boolean = false,
        hidden: Boolean = false,
    ) = game(
        id,
        favorite = favorite,
        hidden = hidden,
        lastPlayedEpochMs = lastPlayed,
        appFacts = InstalledAppFacts(firstInstalledEpochMs = now - installedAgoMs, flaggedGame = flaggedGame, system = system, installer = installer),
    )

    @Test
    fun `the apps scope filters by category, recency, install time and source`() {
        val rules = AppCategoryRules(emulatorPackages = setOf("emu"), markedGames = setOf("marked"), markedNotGames = setOf("notgame"))
        val usage = mapOf("outside" to now - 1_000L)
        val apps = listOf(
            app("flagged", flaggedGame = true),
            app("emu"),
            app("marked"),
            app("notgame", flaggedGame = true),
            app("settings", system = true, installer = null),
            app("outside"),
            app("fresh", installedAgoMs = 1_000L, installer = null),
        )
        val appsScope = appsQueryScope(rules, usage, running = setOf("emu")).let {
            it.copy(context = it.context.copy(now = { now }))
        }

        fun titles(facet: LibraryFacet, value: String) =
            LibraryQuery(facets = mapOf(facet.key to setOf(value))).applyTo(apps, appsScope).map { it.title }

        assertEquals(listOf("flagged", "marked"), titles(LibraryFacet.CATEGORY, "Games"))
        assertEquals(listOf("emu"), titles(LibraryFacet.CATEGORY, "Emulators"))
        assertEquals(listOf("settings"), titles(LibraryFacet.CATEGORY, "System"))
        assertEquals(listOf("emu"), titles(LibraryFacet.RUNNING, RUNNING_YES))
        // Usage access adds an app opened outside droidtop to Recently used.
        assertEquals(listOf("outside"), titles(LibraryFacet.RECENTLY_USED, USED_YES))
        assertEquals(listOf("fresh"), titles(LibraryFacet.RECENTLY_INSTALLED, INSTALLED_RECENTLY_YES))
        assertEquals(listOf("fresh"), titles(LibraryFacet.APP_SOURCE, "Sideloaded"))
        assertEquals("Recently used", appsScope.sortLabel(LibrarySortKey.RECENT))
        assertEquals("apps", appsScope.nounPlural)
    }

    @Test
    fun `apps sort by recently installed, newest first, and most used`() {
        val old = app("old", installedAgoMs = 50L * 24 * 60 * 60 * 1000)
        val new = app("new", installedAgoMs = 1_000L)
        val appsScope = appsQueryScope(AppCategoryRules(), emptyMap(), emptySet())

        assertEquals(listOf("new", "old"), LibraryQuery(sort = LibrarySortKey.ADDED).applyTo(listOf(old, new), appsScope).map { it.title })
        assertEquals(listOf("old", "new"), LibraryQuery(sort = LibrarySortKey.ADDED, reversed = true).applyTo(listOf(old, new), appsScope).map { it.title })
        val often = game("often", playCount = 9)
        val rarely = game("rarely", playCount = 1)
        assertEquals(listOf("often", "rarely"), LibraryQuery(sort = LibrarySortKey.MOST_USED).applyTo(listOf(rarely, often), appsScope).map { it.title })
    }

    @Test
    fun `the gamelist scope reads the completed flag and a Switch game's missing update and DLC`() {
        val retro = retroQueryScope("Switch")
        val done = game("done").copy(completed = true)
        val base = game("base").copy(switchFacts = SwitchGameFacts(hasUpdate = false, dlcCount = 2))
        val updated = game("upd").copy(switchFacts = SwitchGameFacts(hasUpdate = true))
        val loose = game("loose").copy(switchFacts = SwitchGameFacts(loose = true))
        val all = listOf(done, base, updated, loose)

        fun titles(facet: LibraryFacet, value: String) =
            LibraryQuery(facets = mapOf(facet.key to setOf(value))).applyTo(all, retro).map { it.title }

        assertEquals(listOf("done"), titles(LibraryFacet.COMPLETED, COMPLETED_YES))
        assertEquals(listOf("base"), titles(LibraryFacet.SWITCH_CONTENT, SWITCH_HAS_DLC))
        assertEquals(listOf("base"), titles(LibraryFacet.SWITCH_CONTENT, SWITCH_MISSING_UPDATE))
        assertEquals(listOf("loose"), titles(LibraryFacet.SWITCH_CONTENT, SWITCH_LOOSE_DLC))
        assertEquals("retro:Switch", retro.id)
        assertEquals("Release date", retro.sortLabel(LibrarySortKey.YEAR))
    }
}
